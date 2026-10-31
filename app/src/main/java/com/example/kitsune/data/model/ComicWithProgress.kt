package com.example.kitsune.data.model

import androidx.room.Embedded
import androidx.room.Relation
import com.example.kitsune.data.entity.ComicEntity
import com.example.kitsune.data.entity.ReadingProgressEntity

/**
 * Embedded relation combining a recently-read [ComicEntity] with its latest [ReadingProgressEntity].
 */
data class ComicWithProgress(
    @Embedded val comic: ComicEntity,
    @Relation(
        parentColumn = "id",
        entityColumn = "comicId"
    )
    val progress: ReadingProgressEntity?
)
