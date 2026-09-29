package com.kitsune.app.data.metadata

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Unit tests for MediaMetadata model serialization & default values.
 */
class MediaMetadataTest {

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
    }

    @Test
    fun `test default metadata values`() {
        val meta = MediaMetadata()
        assertEquals(1, meta.version)
        assertEquals(emptyList<String>(), meta.tags)
        assertNull(meta.title)
        assertNull(meta.author)
        assertNull(meta.language)
        assertNull(meta.type)
    }

    @Test
    fun `test metadata serialization and deserialization`() {
        val meta = MediaMetadata(
            version = 1,
            tags = listOf("action", "adventure"),
            title = "One Piece",
            author = "Eiichiro Oda",
            language = "EN",
            type = "Manga"
        )

        val jsonString = json.encodeToString(meta)
        val decoded = json.decodeFromString<MediaMetadata>(jsonString)

        assertEquals(meta, decoded)
    }

    @Test
    fun `test forward compatibility with missing JSON fields`() {
        val legacyJson = """
            {
                "version": 1,
                "tags": ["action"]
            }
        """.trimIndent()

        val decoded = json.decodeFromString<MediaMetadata>(legacyJson)
        assertEquals(1, decoded.version)
        assertEquals(listOf("action"), decoded.tags)
        assertNull(decoded.title)
        assertNull(decoded.author)
    }
}
