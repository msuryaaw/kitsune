package com.kitsune.app.domain.model

/**
 * Model statistik perubahan media per kategori (Komik / Video).
 */
data class MediaScanMetrics(
    val newCount: Int = 0,
    val updatedCount: Int = 0,
    val deletedCount: Int = 0
) {
    val totalChanges: Int get() = newCount + updatedCount + deletedCount
    val hasChanges: Boolean get() = totalChanges > 0
}

/**
 * Status akhir dari eksekusi scanner.
 */
enum class ScanStatus {
    SUCCESS,
    PARTIAL,
    FAILED,
    CANCELLED
}

/**
 * Ringkasan lengkap hasil pemindaian library (TASK-06).
 */
data class ScanSummary(
    val comicMetrics: MediaScanMetrics = MediaScanMetrics(),
    val videoMetrics: MediaScanMetrics = MediaScanMetrics(),
    val status: ScanStatus = ScanStatus.SUCCESS,
    val errorMessage: String? = null,
    val timestamp: Long = System.currentTimeMillis()
) {
    val totalChanges: Int get() = comicMetrics.totalChanges + videoMetrics.totalChanges
    val hasChanges: Boolean get() = totalChanges > 0
}
