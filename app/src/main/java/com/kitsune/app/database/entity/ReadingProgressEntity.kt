package com.kitsune.app.database.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Entity untuk menyimpan progres membaca komik.
 * Menggunakan composite relative path (comic + chapter) sebagai identifier unik
 * agar setiap chapter memiliki progres masing-masing.
 */
@Entity(
    tableName = "reading_progress",
    indices = [
        Index(value = ["comicRelativePath", "chapterRelativePath"], unique = true),
        Index(value = ["chapterRelativePath"])
    ]
)
data class ReadingProgressEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val comicRelativePath: String,
    val chapterRelativePath: String,
    val pageNumber: Int,
    val totalPages: Int,
    val lastReadAt: Long = System.currentTimeMillis()
)
