package com.kitsune.app.core

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Utility untuk pemformatan tanggal yang konsisten di seluruh aplikasi Kitsune.
 */
object DateUtils {

    private const val DEFAULT_DATE_FORMAT = "dd MMMM yyyy"

    /**
     * Mengonversi timestamp (milidetik) menjadi string tanggal terformat.
     * @param timestamp Waktu dalam milidetik.
     * @param locale Locale untuk internasionalisasi (default: Locale.getDefault()).
     * @return String tanggal terformat atau null jika timestamp tidak valid (<= 0).
     */
    fun formatTimestamp(
        timestamp: Long,
        locale: Locale = Locale.getDefault(),
        pattern: String = DEFAULT_DATE_FORMAT
    ): String? {
        if (timestamp <= 0) return null
        
        return try {
            val date = Date(timestamp)
            val sdf = SimpleDateFormat(pattern, locale)
            sdf.format(date)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Mengonversi durasi dalam milidetik menjadi format string waktu (HH:mm:ss atau mm:ss).
     * REVISION 7.7.3.2: Digunakan untuk Continue Watching Card.
     */
    fun formatDuration(positionMs: Long): String {
        val totalSeconds = positionMs / 1000
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        val hours = minutes / 60
        val remainingMinutes = minutes % 60

        return if (hours > 0) {
            String.format(Locale.getDefault(), "%02d:%02d:%02d", hours, remainingMinutes, seconds)
        } else {
            String.format(Locale.getDefault(), "%02d:%02d", remainingMinutes, seconds)
        }
    }
}
