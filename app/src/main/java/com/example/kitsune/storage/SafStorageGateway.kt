package com.example.kitsune.storage

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import com.example.kitsune.core.RootFolderPreferences
import com.example.kitsune.core.RootFolderState
import com.example.kitsune.core.SafStorageHelper
import java.io.FileNotFoundException
import java.io.InputStream

/**
 * Concrete implementation of [StorageGateway] backed by Android Storage Access Framework (SAF)
 * and [ContentResolver].
 *
 * Traverses SAF tree hierarchies using [DocumentsContract.buildChildDocumentsUriUsingTree]
 * and converts them to domain [StorageEntry] records with normalized relative paths.
 */
class SafStorageGateway(
    private val context: Context,
    private val preferences: RootFolderPreferences
) : StorageGateway {

    private val resolver get() = context.contentResolver

    override fun getRootState(): StorageResult<String> {
        val rootUriStr = preferences.getRootUriString() ?: return StorageResult.RootNotConfigured
        val uri = try {
            val parsed = Uri.parse(rootUriStr)
            if (DocumentsContract.isTreeUri(parsed)) {
                parsed
            } else {
                return StorageResult.Error(IllegalArgumentException("Root URI bukan tree URI yang valid: $rootUriStr"))
            }
        } catch (_: Exception) {
            return StorageResult.Error(IllegalArgumentException("Format root URI tidak valid: $rootUriStr"))
        }

        return when (val state = SafStorageHelper.checkRootAccess(context, uri)) {
            is RootFolderState.Ready -> StorageResult.Success(state.uriString)
            is RootFolderState.PermissionLost -> StorageResult.PermissionDenied(state.reason)
            is RootFolderState.Inaccessible -> StorageResult.Error(IllegalStateException(state.error))
            is RootFolderState.NotConfigured -> StorageResult.RootNotConfigured
        }
    }

    override fun listChildren(parentRelativePath: String): StorageResult<List<StorageEntry>> {
        val rootUri = when (val state = getVerifiedRootUri()) {
            is StorageResult.Success -> state.data
            is StorageResult.RootNotConfigured -> return StorageResult.RootNotConfigured
            is StorageResult.PermissionDenied -> return StorageResult.PermissionDenied(state.message)
            is StorageResult.Error -> return StorageResult.Error(state.exception)
            is StorageResult.NotFound -> return StorageResult.NotFound(state.relativePath)
        }

        val normalizedParent = try {
            StoragePathUtils.normalizeRelativePath(parentRelativePath)
        } catch (e: SecurityException) {
            return StorageResult.Error(e)
        }

        return try {
            val parentDocUri = resolveDocumentUri(rootUri, normalizedParent)
                ?: return StorageResult.NotFound(normalizedParent)

            val parentDocId = DocumentsContract.getDocumentId(parentDocUri)
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(rootUri, parentDocId)

            val projection = arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_SIZE,
                DocumentsContract.Document.COLUMN_LAST_MODIFIED
            )

            val cursor = resolver.query(childrenUri, projection, null, null, null)
                ?: return StorageResult.NotFound(normalizedParent)

            val entries = mutableListOf<StorageEntry>()
            cursor.use { c ->
                val idIdx = c.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameIdx = c.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val mimeIdx = c.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
                val sizeIdx = c.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
                val modifiedIdx = c.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)

                while (c.moveToNext()) {
                    val name = if (nameIdx >= 0) c.getString(nameIdx) else null
                    if (name.isNullOrBlank()) continue

                    val mime = if (mimeIdx >= 0) c.getString(mimeIdx) else null
                    val isDir = mime == DocumentsContract.Document.MIME_TYPE_DIR
                    val size = if (sizeIdx >= 0 && !c.isNull(sizeIdx)) c.getLong(sizeIdx) else null
                    val lastMod = if (modifiedIdx >= 0 && !c.isNull(modifiedIdx)) c.getLong(modifiedIdx) else null

                    val childRelativePath = StoragePathUtils.combineRelativePath(normalizedParent, name)

                    entries.add(
                        StorageEntry(
                            name = name,
                            relativePath = childRelativePath,
                            isDirectory = isDir,
                            sizeBytes = size,
                            lastModifiedMillis = lastMod,
                            mimeType = mime
                        )
                    )
                }
            }

            StorageResult.Success(entries)
        } catch (se: SecurityException) {
            StorageResult.PermissionDenied(se.localizedMessage ?: "Izin akses SAF ditolak")
        } catch (e: Exception) {
            StorageResult.Error(e)
        }
    }

    override fun findEntry(relativePath: String): StorageResult<StorageEntry> {
        val rootUri = when (val state = getVerifiedRootUri()) {
            is StorageResult.Success -> state.data
            is StorageResult.RootNotConfigured -> return StorageResult.RootNotConfigured
            is StorageResult.PermissionDenied -> return StorageResult.PermissionDenied(state.message)
            is StorageResult.Error -> return StorageResult.Error(state.exception)
            is StorageResult.NotFound -> return StorageResult.NotFound(state.relativePath)
        }

        val normalized = try {
            StoragePathUtils.normalizeRelativePath(relativePath)
        } catch (e: SecurityException) {
            return StorageResult.Error(e)
        }

        if (normalized.isEmpty()) {
            return StorageResult.Success(
                StorageEntry(
                    name = "",
                    relativePath = "",
                    isDirectory = true
                )
            )
        }

        return try {
            val docUri = resolveDocumentUri(rootUri, normalized)
                ?: return StorageResult.NotFound(normalized)

            val projection = arrayOf(
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_SIZE,
                DocumentsContract.Document.COLUMN_LAST_MODIFIED
            )

            val cursor = resolver.query(docUri, projection, null, null, null)
                ?: return StorageResult.NotFound(normalized)

            cursor.use { c ->
                if (c.moveToFirst()) {
                    val nameIdx = c.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                    val mimeIdx = c.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
                    val sizeIdx = c.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
                    val modIdx = c.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)

                    val name = if (nameIdx >= 0) c.getString(nameIdx) ?: normalized.substringAfterLast('/') else normalized.substringAfterLast('/')
                    val mime = if (mimeIdx >= 0) c.getString(mimeIdx) else null
                    val isDir = mime == DocumentsContract.Document.MIME_TYPE_DIR
                    val size = if (sizeIdx >= 0 && !c.isNull(sizeIdx)) c.getLong(sizeIdx) else null
                    val lastMod = if (modIdx >= 0 && !c.isNull(modIdx)) c.getLong(modIdx) else null

                    StorageResult.Success(
                        StorageEntry(
                            name = name,
                            relativePath = normalized,
                            isDirectory = isDir,
                            sizeBytes = size,
                            lastModifiedMillis = lastMod,
                            mimeType = mime
                        )
                    )
                } else {
                    StorageResult.NotFound(normalized)
                }
            }
        } catch (se: SecurityException) {
            StorageResult.PermissionDenied(se.localizedMessage ?: "Izin akses SAF ditolak")
        } catch (e: Exception) {
            StorageResult.Error(e)
        }
    }

    override fun openInputStream(relativePath: String): StorageResult<InputStream> {
        val rootUri = when (val state = getVerifiedRootUri()) {
            is StorageResult.Success -> state.data
            is StorageResult.RootNotConfigured -> return StorageResult.RootNotConfigured
            is StorageResult.PermissionDenied -> return StorageResult.PermissionDenied(state.message)
            is StorageResult.Error -> return StorageResult.Error(state.exception)
            is StorageResult.NotFound -> return StorageResult.NotFound(state.relativePath)
        }

        val normalized = try {
            StoragePathUtils.normalizeRelativePath(relativePath)
        } catch (e: SecurityException) {
            return StorageResult.Error(e)
        }

        return try {
            val docUri = resolveDocumentUri(rootUri, normalized)
                ?: return StorageResult.NotFound(normalized)

            val stream = resolver.openInputStream(docUri)
                ?: return StorageResult.Error(FileNotFoundException("Gagal membuka InputStream untuk $normalized"))

            StorageResult.Success(stream)
        } catch (se: SecurityException) {
            StorageResult.PermissionDenied(se.localizedMessage ?: "Izin akses SAF ditolak")
        } catch (e: Exception) {
            StorageResult.Error(e)
        }
    }

    override fun openFileDescriptor(relativePath: String, mode: String): StorageResult<ParcelFileDescriptor> {
        val rootUri = when (val state = getVerifiedRootUri()) {
            is StorageResult.Success -> state.data
            is StorageResult.RootNotConfigured -> return StorageResult.RootNotConfigured
            is StorageResult.PermissionDenied -> return StorageResult.PermissionDenied(state.message)
            is StorageResult.Error -> return StorageResult.Error(state.exception)
            is StorageResult.NotFound -> return StorageResult.NotFound(state.relativePath)
        }

        val normalized = try {
            StoragePathUtils.normalizeRelativePath(relativePath)
        } catch (e: SecurityException) {
            return StorageResult.Error(e)
        }

        return try {
            val docUri = resolveDocumentUri(rootUri, normalized)
                ?: return StorageResult.NotFound(normalized)

            val pfd = resolver.openFileDescriptor(docUri, mode)
                ?: return StorageResult.Error(FileNotFoundException("Gagal membuka FileDescriptor untuk $normalized"))

            StorageResult.Success(pfd)
        } catch (se: SecurityException) {
            StorageResult.PermissionDenied(se.localizedMessage ?: "Izin akses SAF ditolak")
        } catch (e: Exception) {
            StorageResult.Error(e)
        }
    }

    private fun getVerifiedRootUri(): StorageResult<Uri> {
        val raw = preferences.getRootUriString() ?: return StorageResult.RootNotConfigured
        val uri = try {
            val parsed = Uri.parse(raw)
            if (DocumentsContract.isTreeUri(parsed)) {
                parsed
            } else {
                return StorageResult.Error(IllegalArgumentException("Root URI bukan tree URI yang valid: $raw"))
            }
        } catch (e: Exception) {
            return StorageResult.Error(IllegalArgumentException("Format root URI tidak valid: $raw", e))
        }

        return when (val state = SafStorageHelper.checkRootAccess(context, uri)) {
            is RootFolderState.Ready -> StorageResult.Success(uri)
            is RootFolderState.PermissionLost -> StorageResult.PermissionDenied(state.reason)
            is RootFolderState.Inaccessible -> StorageResult.Error(IllegalStateException(state.error))
            is RootFolderState.NotConfigured -> StorageResult.RootNotConfigured
        }
    }

    private fun resolveDocumentUri(rootUri: Uri, normalizedRelativePath: String): Uri? {
        if (normalizedRelativePath.isEmpty()) {
            val rootDocId = DocumentsContract.getTreeDocumentId(rootUri)
            return DocumentsContract.buildDocumentUriUsingTree(rootUri, rootDocId)
        }

        val segments = normalizedRelativePath.split('/')
        var currentDocId = DocumentsContract.getTreeDocumentId(rootUri)

        for (segment in segments) {
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(rootUri, currentDocId)
            val projection = arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME
            )

            val cursor = resolver.query(childrenUri, projection, null, null, null) ?: return null
            var matchedDocId: String? = null

            cursor.use { c ->
                val idIdx = c.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameIdx = c.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)

                while (c.moveToNext()) {
                    val name = if (nameIdx >= 0) c.getString(nameIdx) else null
                    if (name.equals(segment, ignoreCase = false)) {
                        matchedDocId = if (idIdx >= 0) c.getString(idIdx) else null
                        break
                    }
                }
            }

            if (matchedDocId == null) {
                return null
            }
            currentDocId = matchedDocId
        }

        return DocumentsContract.buildDocumentUriUsingTree(rootUri, currentDocId)
    }
}
