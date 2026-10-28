package com.example.kitsune.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FakeRootFolderPreferences : RootFolderPreferences {
    private var uri: String? = null

    override fun getRootUriString(): String? = uri

    override fun setRootUriString(uriString: String?) {
        this.uri = uriString?.trim()?.ifEmpty { null }
    }
}

class RootFolderPreferencesTest {

    @Test
    fun getRootUriString_initiallyReturnsNull() {
        val prefs = FakeRootFolderPreferences()
        assertNull(prefs.getRootUriString())
    }

    @Test
    fun setRootUriString_validUri_storesAndRetrieves() {
        val prefs = FakeRootFolderPreferences()
        val uriStr = "content://com.android.externalstorage.documents/tree/primary%3AKitsune"

        prefs.setRootUriString(uriStr)
        assertEquals(uriStr, prefs.getRootUriString())
    }

    @Test
    fun setRootUriString_emptyOrBlank_clearsStoredValue() {
        val prefs = FakeRootFolderPreferences()
        prefs.setRootUriString("content://valid/path")
        assertEquals("content://valid/path", prefs.getRootUriString())

        prefs.setRootUriString("   ")
        assertNull(prefs.getRootUriString())

        prefs.setRootUriString("")
        assertNull(prefs.getRootUriString())

        prefs.setRootUriString(null)
        assertNull(prefs.getRootUriString())
    }

    @Test
    fun rootFolderState_dataClassesDistinguishCorrectly() {
        val notConfigured: RootFolderState = RootFolderState.NotConfigured
        val permissionLost = RootFolderState.PermissionLost("content://uri", "Izin dicabut")
        val inaccessible = RootFolderState.Inaccessible("content://uri", "Error I/O")
        val ready = RootFolderState.Ready("content://uri")

        assertNotNull(notConfigured)
        assertEquals("Izin dicabut", permissionLost.reason)
        assertEquals("Error I/O", inaccessible.error)
        assertEquals("content://uri", ready.uriString)
    }

    @Test
    fun rootFolderState_equalityAndStateBranches() {
        val state1: RootFolderState = RootFolderState.Ready("content://uri1")
        val state2: RootFolderState = RootFolderState.Ready("content://uri1")
        val state3: RootFolderState = RootFolderState.Ready("content://uri2")

        assertEquals(state1, state2)
        assertFalse(state1 == state3)

        val states = listOf(
            RootFolderState.NotConfigured,
            RootFolderState.PermissionLost("u", "p"),
            RootFolderState.Inaccessible("u", "err"),
            RootFolderState.Ready("u")
        )

        val readyCount = states.count { it is RootFolderState.Ready }
        val problemCount = states.count { it is RootFolderState.PermissionLost || it is RootFolderState.Inaccessible }

        assertEquals(1, readyCount)
        assertEquals(2, problemCount)
    }
}
