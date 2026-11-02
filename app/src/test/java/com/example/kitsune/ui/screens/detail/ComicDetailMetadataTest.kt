package com.example.kitsune.ui.screens.detail

import com.example.kitsune.data.entity.ChapterEntity
import com.example.kitsune.data.entity.ComicEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ComicDetailMetadataTest {

    @Test
    fun comicDetail_metadataFields_typeLanguageAuthorPreservedInComicEntity() {
        val comic = ComicEntity(
            id = 1L,
            rootUriString = "content://root",
            relativePath = "Comics/[Manga] [ID] [Oda] One Piece",
            title = "One Piece",
            type = "Manga",
            language = "ID",
            author = "Oda"
        )

        assertEquals("Manga", comic.type)
        assertEquals("ID", comic.language)
        assertEquals("Oda", comic.author)
        assertEquals("One Piece", comic.title)
    }

    @Test
    fun comicDetail_nullableMetadataFields_defaultToNullWithoutPlaceholders() {
        val legacyComic = ComicEntity(
            id = 2L,
            rootUriString = "content://root",
            relativePath = "Comics/Bleach",
            title = "Bleach"
        )

        assertNull("Type MUST be null when unavailable", legacyComic.type)
        assertNull("Language MUST be null when unavailable", legacyComic.language)
        assertNull("Author MUST be null when unavailable", legacyComic.author)
    }

    @Test
    fun cleanChapterDisplay_stripsCbzSuffix_caseInsensitively() {
        assertEquals("Chapter 1", "Chapter 1.cbz".removeSuffixCaseInsensitive(".cbz"))
        assertEquals("Chapter 2", "Chapter 2.CBZ".removeSuffixCaseInsensitive(".cbz"))
        assertEquals("Chapter 3", "Chapter 3.Cbz".removeSuffixCaseInsensitive(".cbz"))
        assertEquals("Chapter 4", "Chapter 4".removeSuffixCaseInsensitive(".cbz"))
        assertEquals("Chapter 5.cbz.bak", "Chapter 5.cbz.bak".removeSuffixCaseInsensitive(".cbz"))
    }

    @Test
    fun cleanChapterDisplay_doesNotModifyOriginalChapterEntityData() {
        val originalChapter = ChapterEntity(
            id = 10L,
            comicId = 1L,
            relativePath = "Comics/One Piece/Chapter 1.cbz",
            chapterNumber = 1,
            displayName = "Chapter 1.cbz",
            pageCount = 10
        )

        val cleanTitle = originalChapter.displayName.removeSuffixCaseInsensitive(".cbz")

        assertEquals("Presentation title MUST be stripped", "Chapter 1", cleanTitle)
        assertEquals("Original displayName MUST remain untouched in entity", "Chapter 1.cbz", originalChapter.displayName)
        assertEquals("Original relativePath MUST remain untouched in entity", "Comics/One Piece/Chapter 1.cbz", originalChapter.relativePath)
        assertEquals("Original chapter id MUST remain untouched", 10L, originalChapter.id)
    }
}
