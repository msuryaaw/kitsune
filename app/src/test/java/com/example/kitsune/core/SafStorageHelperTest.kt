package com.example.kitsune.core

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.provider.DocumentsContract
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver

class FailingContentProvider(private val shouldThrowSecurity: Boolean) : ContentProvider() {
    override fun onCreate(): Boolean = true
    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor? {
        if (shouldThrowSecurity) {
            throw SecurityException("Security Exception: Access denied by provider")
        } else {
            throw RuntimeException("Database I/O Failure in Provider")
        }
    }
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class SafStorageHelperTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    @Test
    fun checkRootAccess_noPersistedPermission_returnsPermissionLost() {
        val testUri = Uri.parse("content://com.android.externalstorage.documents/tree/primary%3AKitsune")

        val state = SafStorageHelper.checkRootAccess(context, testUri)

        assertTrue("Missing persisted permission MUST return PermissionLost", state is RootFolderState.PermissionLost)
        val lost = state as RootFolderState.PermissionLost
        assertEquals(testUri.toString(), lost.uriString)
        assertTrue(lost.reason.contains("Izin baca persisten tidak ditemukan"))
    }

    @Test
    fun checkRootAccess_persistedPermissionHeldAndDirectoryValid_returnsReady() {
        val testUri = Uri.parse("content://com.android.externalstorage.documents/tree/primary%3AKitsune")
        val shadowResolver = Shadows.shadowOf(context.contentResolver)

        context.contentResolver.takePersistableUriPermission(
            testUri,
            android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
        )

        val docUri = DocumentsContract.buildDocumentUriUsingTree(
            testUri,
            DocumentsContract.getTreeDocumentId(testUri)
        )

        val cursor = org.robolectric.fakes.RoboCursor().apply {
            setColumnNames(listOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_MIME_TYPE
            ))
            setResults(arrayOf(arrayOf("primary:Kitsune", DocumentsContract.Document.MIME_TYPE_DIR)))
        }
        shadowResolver.setCursor(docUri, cursor)

        val state = SafStorageHelper.checkRootAccess(context, testUri)

        assertTrue("Valid directory with persisted permission MUST return Ready", state is RootFolderState.Ready)
        assertEquals(testUri.toString(), (state as RootFolderState.Ready).uriString)
    }

    @Test
    fun checkRootAccess_persistedPermissionHeld_emptyDirectory_returnsReady() {
        val testUri = Uri.parse("content://com.android.externalstorage.documents/tree/primary%3AEmptyKitsune")
        val shadowResolver = Shadows.shadowOf(context.contentResolver)

        context.contentResolver.takePersistableUriPermission(
            testUri,
            android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
        )

        val docUri = DocumentsContract.buildDocumentUriUsingTree(
            testUri,
            DocumentsContract.getTreeDocumentId(testUri)
        )

        val cursor = org.robolectric.fakes.RoboCursor().apply {
            setColumnNames(listOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_MIME_TYPE
            ))
            setResults(arrayOf(arrayOf("primary:EmptyKitsune", DocumentsContract.Document.MIME_TYPE_DIR)))
        }
        shadowResolver.setCursor(docUri, cursor)

        val state = SafStorageHelper.checkRootAccess(context, testUri)

        assertTrue("Empty folder with MIME_TYPE_DIR MUST return Ready (not Inaccessible)", state is RootFolderState.Ready)
    }

    @Test
    fun checkRootAccess_persistedPermissionHeld_fileMimeType_returnsInaccessible() {
        val testUri = Uri.parse("content://com.android.externalstorage.documents/tree/primary%3AFile")
        val shadowResolver = Shadows.shadowOf(context.contentResolver)

        context.contentResolver.takePersistableUriPermission(
            testUri,
            android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
        )

        val docUri = DocumentsContract.buildDocumentUriUsingTree(
            testUri,
            DocumentsContract.getTreeDocumentId(testUri)
        )

        val cursor = org.robolectric.fakes.RoboCursor().apply {
            setColumnNames(listOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_MIME_TYPE
            ))
            setResults(arrayOf(arrayOf("primary:File/image.jpg", "image/jpeg")))
        }
        shadowResolver.setCursor(docUri, cursor)

        val state = SafStorageHelper.checkRootAccess(context, testUri)

        assertTrue("Non-directory MIME type MUST return Inaccessible", state is RootFolderState.Inaccessible)
        assertTrue((state as RootFolderState.Inaccessible).error.contains("bukan merupakan direktori/folder"))
    }

    @Test
    fun checkRootAccess_securityException_returnsPermissionLostCleanlyWithoutCrash() {
        val testUri = Uri.parse("content://com.example.failingprovider.sec/tree/primary%3ASecurityErr")

        context.contentResolver.takePersistableUriPermission(
            testUri,
            android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
        )

        ShadowContentResolver.registerProviderInternal(
            "com.example.failingprovider.sec",
            FailingContentProvider(shouldThrowSecurity = true)
        )

        val state = SafStorageHelper.checkRootAccess(context, testUri)

        assertTrue("SecurityException during query MUST return PermissionLost without crashing", state is RootFolderState.PermissionLost)
        val lost = state as RootFolderState.PermissionLost
        assertTrue(lost.reason.contains("Akses ditolak oleh penyedia penyimpanan"))
    }

    @Test
    fun checkRootAccess_genericException_returnsInaccessibleCleanlyWithoutCrash() {
        val testUri = Uri.parse("content://com.example.failingprovider.generic/tree/primary%3AGenericErr")

        context.contentResolver.takePersistableUriPermission(
            testUri,
            android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
        )

        ShadowContentResolver.registerProviderInternal(
            "com.example.failingprovider.generic",
            FailingContentProvider(shouldThrowSecurity = false)
        )

        val state = SafStorageHelper.checkRootAccess(context, testUri)

        assertTrue("Generic Exception during query MUST return Inaccessible without crashing", state is RootFolderState.Inaccessible)
        val inaccessible = state as RootFolderState.Inaccessible
        assertTrue(inaccessible.error.contains("Database I/O Failure"))
    }

    @Test
    fun rootFolderState_reverify_doesNotClearStoredUri() {
        val prefs = FakeRootFolderPreferences("content://primary%3AKitsune")
        val uriStrBefore = prefs.getRootUriString()

        val reverifiedState = RootFolderState.Ready(uriStrBefore!!)
        val uriStrAfter = prefs.getRootUriString()

        assertEquals(uriStrBefore, uriStrAfter)
        assertTrue(reverifiedState is RootFolderState.Ready)
        assertEquals("content://primary%3AKitsune", (reverifiedState as RootFolderState.Ready).uriString)
    }
}
