package com.example.kitsune.reader

import com.example.kitsune.data.dao.ChapterDao
import com.example.kitsune.storage.StorageGateway
import com.example.kitsune.storage.StorageResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Contract for opening and spooling CBZ archives from SAF storage into private application cache.
 */
interface CbzArchiveProvider {
    /**
     * Opens or spools the CBZ archive for [chapterId].
     */
    suspend fun openArchive(chapterId: Long): CbzArchiveResult
}

/**
 * Default implementation of [CbzArchiveProvider] with streaming spooling,
 * max single archive size limits, thread-safe admission control,
 * cancellation safety, and active handle tracking.
 */
class DefaultCbzArchiveProvider(
    private val spoolDir: File,
    private val storageGateway: StorageGateway,
    private val chapterDao: ChapterDao,
    private val maxSpoolSizeBytes: Long = DEFAULT_MAX_SPOOL_SIZE_BYTES,
    private val maxAggregateSpoolBudgetBytes: Long = DEFAULT_MAX_AGGREGATE_SPOOL_BUDGET_BYTES,
    private val sessionStartTimeMs: Long = System.currentTimeMillis()
) : CbzArchiveProvider {

    /** Active handle reference counter tracking spooled files in active use. */
    private val activeSpoolRefs = ConcurrentHashMap<String, AtomicInteger>()

    /** Tracked total bytes currently spooled or in-flight in reader_spool. */
    private val currentSpooledBytes = AtomicLong(0L)

    init {
        if (!spoolDir.exists()) {
            spoolDir.mkdirs()
        } else {
            cleanupOrphanedSpoolFilesOnStartup()
        }
    }

    companion object {
        /** Default maximum allowed size for a single spooled CBZ archive (500 MB). */
        const val DEFAULT_MAX_SPOOL_SIZE_BYTES = 500L * 1024L * 1024L

        /** Default maximum aggregate cache budget for spooled archives (1.5 GB). */
        const val DEFAULT_MAX_AGGREGATE_SPOOL_BUDGET_BYTES = 1500L * 1024L * 1024L
    }

    /** Test-only flag to simulate initial atomic publish returning false */
    internal var forceRenameFailure = false

    /** Test-only flag to simulate staging fallback copy/verification failure */
    internal var forceStagingFailure = false

    /** Test-only flag to simulate staging publish to final file returning false */
    internal var forceStagingPublishFailure = false

    override suspend fun openArchive(chapterId: Long): CbzArchiveResult = withContext(Dispatchers.IO) {
        val chapter = chapterDao.getById(chapterId)
            ?: return@withContext CbzArchiveResult.ChapterMissing

        if (!chapter.isValid) {
            return@withContext CbzArchiveResult.ChapterMissing
        }

        // Validate metadata size before streaming if available
        val expectedSize = chapter.fileSize ?: 0L
        if (expectedSize > maxSpoolSizeBytes) {
            return@withContext CbzArchiveResult.ArchiveTooLarge(maxSpoolSizeBytes, expectedSize)
        }

        // Thread-safe admission control & LRU eviction check
        val admitted = calculateAndEvictUnreferencedFiles(expectedSize)
        if (!admitted) {
            return@withContext CbzArchiveResult.SpoolBudgetExceeded(
                maxAggregateSpoolBudgetBytes,
                expectedSize
            )
        }

        val relativePath = chapter.relativePath
        val streamResult = storageGateway.openInputStream(relativePath)
        val inputStream: InputStream = when (streamResult) {
            is StorageResult.Success -> streamResult.data
            is StorageResult.NotFound -> return@withContext CbzArchiveResult.ChapterMissing
            is StorageResult.PermissionDenied -> return@withContext CbzArchiveResult.PermissionDenied(streamResult.message)
            is StorageResult.RootNotConfigured -> return@withContext CbzArchiveResult.ChapterMissing
            is StorageResult.Error -> return@withContext CbzArchiveResult.StorageError(streamResult.exception)
        }

        val uuid = UUID.randomUUID().toString().replace("-", "").take(8)
        val nano = System.nanoTime()
        val partFile = File(spoolDir, "temp_spool_${chapterId}_${uuid}_$nano.part")
        val finalFile = File(spoolDir, "spool_${chapterId}_${uuid}_$nano.cbz")

        try {
            inputStream.use { input ->
                FileOutputStream(partFile).use { output ->
                    val buffer = ByteArray(8192)
                    var totalWritten = 0L
                    while (true) {
                        kotlinx.coroutines.currentCoroutineContext().ensureActive()
                        val bytesRead = input.read(buffer)
                        if (bytesRead < 0) break

                        totalWritten += bytesRead
                        if (totalWritten > maxSpoolSizeBytes) {
                            output.flush()
                            if (partFile.exists()) partFile.delete()
                            return@withContext CbzArchiveResult.ArchiveTooLarge(maxSpoolSizeBytes, totalWritten)
                        }

                        // Check running total against aggregate budget during streaming
                        val currentTotal = currentSpooledBytes.addAndGet(bytesRead.toLong())
                        if (currentTotal > maxAggregateSpoolBudgetBytes) {
                            currentSpooledBytes.addAndGet(-bytesRead.toLong())
                            output.flush()
                            if (partFile.exists()) partFile.delete()
                            return@withContext CbzArchiveResult.SpoolBudgetExceeded(
                                maxAggregateSpoolBudgetBytes,
                                currentTotal
                            )
                        }

                        output.write(buffer, 0, bytesRead)
                    }
                    output.flush()

                    if (totalWritten == 0L) {
                        if (partFile.exists()) partFile.delete()
                        return@withContext CbzArchiveResult.StorageError(
                            IllegalStateException("CBZ source file is empty (0 bytes)")
                        )
                    }
                }
            }

            // Commit atomically with staging fallback
            var committed = if (!forceRenameFailure) atomicPublish(partFile, finalFile) else false
            if (!committed) {
                val stagingFile = File(spoolDir, "staging_spool_${chapterId}_${uuid}_$nano.tmp")
                try {
                    if (!forceStagingFailure) {
                        partFile.copyTo(stagingFile, overwrite = true)
                        if (stagingFile.length() == partFile.length() && contentEqualsStreaming(stagingFile, partFile)) {
                            partFile.delete()
                            committed = if (!forceStagingPublishFailure) atomicPublish(stagingFile, finalFile) else false
                            if (!committed && stagingFile.exists()) {
                                stagingFile.delete()
                            }
                        } else {
                            if (stagingFile.exists()) stagingFile.delete()
                        }
                    } else {
                        if (stagingFile.exists()) stagingFile.delete()
                    }
                } catch (_: Exception) {
                    if (stagingFile.exists()) stagingFile.delete()
                }
            }

            if (!committed) {
                if (partFile.exists()) partFile.delete()
                return@withContext CbzArchiveResult.StorageError(IOException("Failed atomic commit to $finalFile"))
            }

            // Register active handle reference
            retainSpoolFile(finalFile)

            val handle = CbzArchiveHandle(
                chapterId = chapterId,
                file = finalFile,
                onClose = { fileToDelete ->
                    releaseSpoolFile(fileToDelete)
                }
            )

            CbzArchiveResult.Success(handle)
        } catch (ce: CancellationException) {
            if (partFile.exists()) partFile.delete()
            throw ce
        } catch (e: Exception) {
            if (partFile.exists()) partFile.delete()
            CbzArchiveResult.StorageError(e)
        }
    }

    private fun retainSpoolFile(file: File) {
        activeSpoolRefs.computeIfAbsent(file.name) { AtomicInteger(0) }.incrementAndGet()
    }

    private fun releaseSpoolFile(file: File) {
        val ref = activeSpoolRefs[file.name]
        val remaining = ref?.decrementAndGet() ?: 0
        if (remaining <= 0) {
            activeSpoolRefs.remove(file.name)
            try {
                if (file.exists() && file.isFile) {
                    val size = file.length()
                    if (file.delete()) {
                        currentSpooledBytes.addAndGet(-size)
                    }
                }
            } catch (_: Exception) {}
        }
    }

    fun isSpoolFileActive(file: File): Boolean {
        return (activeSpoolRefs[file.name]?.get() ?: 0) > 0
    }

    /**
     * Calculates current aggregate spooled byte usage and performs LRU eviction of unreferenced files.
     * Returns true if capacity is available for [requiredNewBytes].
     */
    private fun calculateAndEvictUnreferencedFiles(requiredNewBytes: Long): Boolean {
        synchronized(spoolDir) {
            val files = spoolDir.listFiles() ?: return true
            var activeAndTempBytes = 0L
            val unreferencedFiles = mutableListOf<File>()

            for (file in files) {
                if (!file.isFile) continue
                if (file.name.startsWith("temp_spool_") || file.name.startsWith("staging_spool_") || isSpoolFileActive(file)) {
                    activeAndTempBytes += file.length()
                } else if (file.name.startsWith("spool_")) {
                    unreferencedFiles.add(file)
                }
            }

            unreferencedFiles.sortBy { it.lastModified() }

            var unreferencedTotal = unreferencedFiles.sumOf { it.length() }

            for (file in unreferencedFiles) {
                if (activeAndTempBytes + unreferencedTotal + requiredNewBytes <= maxAggregateSpoolBudgetBytes) {
                    break
                }
                val size = file.length()
                if (file.delete()) {
                    unreferencedTotal -= size
                }
            }

            val totalAfterEviction = activeAndTempBytes + unreferencedTotal
            currentSpooledBytes.set(totalAfterEviction)

            return (totalAfterEviction + requiredNewBytes) <= maxAggregateSpoolBudgetBytes
        }
    }

    /**
     * Cleans up orphaned `.part`, `.tmp` staging, and unreferenced `spool_*` files left over from prior sessions.
     */
    private fun cleanupOrphanedSpoolFilesOnStartup() {
        synchronized(spoolDir) {
            val files = spoolDir.listFiles() ?: return
            for (file in files) {
                val isTemp = file.name.startsWith("temp_spool_") || file.name.startsWith("staging_spool_")
                val isSpool = file.name.startsWith("spool_")
                val isPriorSession = file.lastModified() < sessionStartTimeMs
                if (isTemp || (isSpool && isPriorSession && !isSpoolFileActive(file))) {
                    try {
                        file.delete()
                    } catch (_: Exception) {}
                }
            }
        }
    }

    /**
     * Executes atomic move via java.nio.file.Files.move(ATOMIC_MOVE) on Android minSdk 26+ (ext4/f2fs).
     * Falls back to File.renameTo if POSIX atomic move is unsupported.
     */
    private fun atomicPublish(sourceFile: File, targetFile: File): Boolean {
        return try {
            java.nio.file.Files.move(
                sourceFile.toPath(),
                targetFile.toPath(),
                java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                java.nio.file.StandardCopyOption.REPLACE_EXISTING
            )
            true
        } catch (_: Exception) {
            sourceFile.renameTo(targetFile)
        }
    }

    /**
     * Stream-based memory-safe comparison of two files in 8KB chunks without loading full content to RAM.
     */
    private fun contentEqualsStreaming(file1: File, file2: File): Boolean {
        if (file1.length() != file2.length()) return false
        return try {
            file1.inputStream().buffered().use { in1 ->
                file2.inputStream().buffered().use { in2 ->
                    val buf1 = ByteArray(8192)
                    val buf2 = ByteArray(8192)
                    var equal = true
                    while (equal) {
                        val read1 = in1.read(buf1)
                        val read2 = in2.read(buf2)
                        if (read1 != read2) {
                            equal = false
                            break
                        }
                        if (read1 < 0) break
                        for (i in 0 until read1) {
                            if (buf1[i] != buf2[i]) {
                                equal = false
                                break
                            }
                        }
                    }
                    equal
                }
            }
        } catch (_: Exception) {
            false
        }
    }
}
