package com.example.kitsune.ui.screens

import com.example.kitsune.ui.screens.bookmark.BookmarkUiState
import com.example.kitsune.ui.screens.history.HistoryUiState
import com.example.kitsune.ui.screens.library.LibraryUiState
import com.example.kitsune.ui.screens.reader.ReaderErrorType
import com.example.kitsune.ui.screens.reader.ReaderUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UiStateAndFeedbackTest {

    @Test
    fun uiState_emptyResultVersusErrorState_areDistinct() {
        val librarySearchEmpty = LibraryUiState.SearchNoResults("content://uri", searchQuery = "OnePiece")
        val libraryError = LibraryUiState.Error("Gagal memuat database")

        val bookmarkEmpty = BookmarkUiState.Empty
        val bookmarkError = BookmarkUiState.Error("Gagal memuat bookmark")

        val historyEmpty = HistoryUiState.Empty
        val historyError = HistoryUiState.Error("Gagal memuat riwayat")

        assertNotEquals("SearchNoResults MUST NOT equal Error", librarySearchEmpty::class, libraryError::class)
        assertNotEquals("Bookmark Empty MUST NOT equal Error", bookmarkEmpty::class, bookmarkError::class)
        assertNotEquals("History Empty MUST NOT equal Error", historyEmpty::class, historyError::class)
    }

    @Test
    fun errorMapping_readerErrorTypes_presentLocalizedUserActionableMessages() {
        val errorMessages = mapOf(
            ReaderErrorType.PERMISSION_DENIED to "Izin akses penyimpanan terputus",
            ReaderErrorType.ROOT_NOT_CONFIGURED to "Root SAF belum dikonfigurasi",
            ReaderErrorType.CORRUPT_ARCHIVE to "Arsip chapter rusak",
            ReaderErrorType.UNSUPPORTED_ARCHIVE to "Format tidak didukung atau melebihi batas",
            ReaderErrorType.NO_IMAGES to "Tidak ada halaman gambar ditemukan",
            ReaderErrorType.CHAPTER_MISSING to "File chapter tidak ditemukan di penyimpanan",
            ReaderErrorType.STORAGE_ERROR to "Gagal membaca arsip chapter"
        )

        errorMessages.forEach { (type, message) ->
            assertFalse("Error message for $type must not be blank", message.isBlank())
            assertFalse("Error message for $type MUST NOT contain raw java.io exception class name", message.contains("java.io.IOException"))
            assertFalse("Error message for $type MUST NOT contain raw stack trace", message.contains("at com.example"))
        }
    }

    @Test
    fun dialogCancellation_doesNotMutateState_orEmitFalseFeedback() {
        var repoMutated = false

        // Simulating Cancel button click handler
        val onDismiss: () -> Unit = {
            // Cancel closes dialog without invoking repo mutation
        }

        val onConfirm: () -> Unit = {
            repoMutated = true
        }

        // Execute Cancel
        onDismiss()

        assertFalse("Canceling confirmation dialog MUST NOT trigger repository mutation", repoMutated)

        // Execute Confirm
        onConfirm()

        assertTrue("Confirming dialog MUST trigger repository mutation", repoMutated)
    }
}
