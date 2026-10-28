package com.example.kitsune.storage

import android.os.ParcelFileDescriptor
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream

/**
 * In-memory / temporary file test implementation of [StorageGateway] for unit testing scanners,
 * catalogs, and repository reconciliation without an Android emulator or SAF provider.
 * Supports seekable [ParcelFileDescriptor] by spooling byte contents to a temporary test file.
 */
class FakeStorageGateway(
    var rootConfigured: Boolean = true,
    var permissionGranted: Boolean = true,
    var rootValidTree: Boolean = true
) : StorageGateway {

    private val entries = mutableMapOf<String, StorageEntry>()
    private val fileContents = mutableMapOf<String, ByteArray>()
    private val tempFiles = mutableListOf<File>()

    fun addDirectory(relativePath: String) {
        val norm = StoragePathUtils.normalizeRelativePath(relativePath)
        val name = if (norm.isEmpty()) "" else norm.substringAfterLast('/')
        entries[norm] = StorageEntry(
            name = name,
            relativePath = norm,
            isDirectory = true
        )
    }

    fun addFile(relativePath: String, content: ByteArray = ByteArray(0), mimeType: String? = null) {
        val norm = StoragePathUtils.normalizeRelativePath(relativePath)
        val name = norm.substringAfterLast('/')
        entries[norm] = StorageEntry(
            name = name,
            relativePath = norm,
            isDirectory = false,
            sizeBytes = content.size.toLong(),
            lastModifiedMillis = 1000L,
            mimeType = mimeType
        )
        fileContents[norm] = content
    }

    override fun getRootState(): StorageResult<String> {
        if (!rootConfigured) return StorageResult.RootNotConfigured
        if (!rootValidTree) return StorageResult.Error(IllegalArgumentException("Format root URI tidak valid"))
        if (!permissionGranted) return StorageResult.PermissionDenied("Fake permission revoked")
        return StorageResult.Success("content://fake/root")
    }

    override fun listChildren(parentRelativePath: String): StorageResult<List<StorageEntry>> {
        val state = getRootState()
        if (state !is StorageResult.Success) {
            return when (state) {
                is StorageResult.RootNotConfigured -> StorageResult.RootNotConfigured
                is StorageResult.PermissionDenied -> StorageResult.PermissionDenied(state.message)
                is StorageResult.Error -> StorageResult.Error(state.exception)
                else -> StorageResult.Error(IllegalStateException("Unknown state"))
            }
        }

        val normParent = try {
            StoragePathUtils.normalizeRelativePath(parentRelativePath)
        } catch (e: SecurityException) {
            return StorageResult.Error(e)
        }

        if (normParent.isNotEmpty() && !entries.containsKey(normParent)) {
            return StorageResult.NotFound(normParent)
        }

        val children = entries.values.filter { entry ->
            val entryParent = if (entry.relativePath.contains('/')) {
                entry.relativePath.substringBeforeLast('/')
            } else {
                ""
            }
            entryParent == normParent && entry.relativePath.isNotEmpty()
        }

        return StorageResult.Success(children)
    }

    override fun findEntry(relativePath: String): StorageResult<StorageEntry> {
        val state = getRootState()
        if (state !is StorageResult.Success) {
            return when (state) {
                is StorageResult.RootNotConfigured -> StorageResult.RootNotConfigured
                is StorageResult.PermissionDenied -> StorageResult.PermissionDenied(state.message)
                is StorageResult.Error -> StorageResult.Error(state.exception)
                else -> StorageResult.Error(IllegalStateException("Unknown state"))
            }
        }

        val norm = try {
            StoragePathUtils.normalizeRelativePath(relativePath)
        } catch (e: SecurityException) {
            return StorageResult.Error(e)
        }

        if (norm.isEmpty()) {
            return StorageResult.Success(
                StorageEntry(
                    name = "",
                    relativePath = "",
                    isDirectory = true
                )
            )
        }

        val entry = entries[norm] ?: return StorageResult.NotFound(norm)
        return StorageResult.Success(entry)
    }

    override fun openInputStream(relativePath: String): StorageResult<InputStream> {
        val state = getRootState()
        if (state !is StorageResult.Success) {
            return when (state) {
                is StorageResult.RootNotConfigured -> StorageResult.RootNotConfigured
                is StorageResult.PermissionDenied -> StorageResult.PermissionDenied(state.message)
                is StorageResult.Error -> StorageResult.Error(state.exception)
                else -> StorageResult.Error(IllegalStateException("Unknown state"))
            }
        }

        val norm = try {
            StoragePathUtils.normalizeRelativePath(relativePath)
        } catch (e: SecurityException) {
            return StorageResult.Error(e)
        }

        val bytes = fileContents[norm] ?: return StorageResult.NotFound(norm)
        return StorageResult.Success(ByteArrayInputStream(bytes))
    }

    override fun openFileDescriptor(relativePath: String, mode: String): StorageResult<ParcelFileDescriptor> {
        val state = getRootState()
        if (state !is StorageResult.Success) {
            return when (state) {
                is StorageResult.RootNotConfigured -> StorageResult.RootNotConfigured
                is StorageResult.PermissionDenied -> StorageResult.PermissionDenied(state.message)
                is StorageResult.Error -> StorageResult.Error(state.exception)
                else -> StorageResult.Error(IllegalStateException("Unknown state"))
            }
        }

        val norm = try {
            StoragePathUtils.normalizeRelativePath(relativePath)
        } catch (e: SecurityException) {
            return StorageResult.Error(e)
        }

        val bytes = fileContents[norm] ?: return StorageResult.NotFound(norm)

        return try {
            val tempFile = File.createTempFile("fake_cbz_", ".tmp")
            tempFile.deleteOnExit()
            FileOutputStream(tempFile).use { it.write(bytes) }
            tempFiles.add(tempFile)

            val pfdMode = when (mode) {
                "r" -> ParcelFileDescriptor.MODE_READ_ONLY
                "w" -> ParcelFileDescriptor.MODE_WRITE_ONLY
                "rw" -> ParcelFileDescriptor.MODE_READ_WRITE
                else -> ParcelFileDescriptor.MODE_READ_ONLY
            }
            val pfd = ParcelFileDescriptor.open(tempFile, pfdMode)
            StorageResult.Success(pfd)
        } catch (e: Exception) {
            StorageResult.Error(e)
        } catch (e: LinkageError) {
            // Menangani lingkungan JVM standar / unit test lokal tanpa Android runtime atau Robolectric
            StorageResult.Error(UnsupportedOperationException("ParcelFileDescriptor.open tidak didukung di JVM standar tanpa Android runtime / Robolectric", e))
        }
    }

    fun cleanUp() {
        for (f in tempFiles) {
            try { f.delete() } catch (_: Exception) {}
        }
        tempFiles.clear()
    }
}
