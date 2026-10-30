package com.example.kitsune.reader

import java.io.Closeable
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Handle to an opened CBZ archive, providing access to the local spooled file for reading.
 * Implements [Closeable] to ensure private spool resources are cleaned up when closed.
 */
class CbzArchiveHandle(
    val chapterId: Long,
    val file: File,
    private val onClose: (File) -> Unit
) : Closeable {
    private val closed = AtomicBoolean(false)

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            onClose(file)
        }
    }

    val isClosed: Boolean get() = closed.get()
}

/**
 * Result state of attempting to open a CBZ archive for reading via SAF.
 */
sealed interface CbzArchiveResult {
    /**
     * Archive was successfully opened/spooled and is ready for reading.
     */
    data class Success(val handle: CbzArchiveHandle) : CbzArchiveResult

    /**
     * The requested chapter or file was not found.
     */
    object ChapterMissing : CbzArchiveResult

    /**
     * SAF storage permission was revoked or lost.
     */
    data class PermissionDenied(val message: String) : CbzArchiveResult

    /**
     * Archive file exceeds the maximum allowed single spool size limit.
     */
    data class ArchiveTooLarge(val maxSizeBytes: Long, val actualSizeBytes: Long?) : CbzArchiveResult

    /**
     * Aggregate spool budget cannot accommodate the request because active handles hold capacity.
     */
    data class SpoolBudgetExceeded(val maxBudgetBytes: Long, val requiredBytes: Long) : CbzArchiveResult

    /**
     * An unexpected I/O or storage error occurred during open/spooling.
     */
    data class StorageError(val exception: Throwable) : CbzArchiveResult
}
