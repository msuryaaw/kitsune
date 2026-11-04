package com.example.kitsune.ui

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.kitsune.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class EnglishCopyTest {

    @Test
    fun systemUiStrings_areInEnglish() {
        val context = ApplicationProvider.getApplicationContext<Context>()

        assertEquals("Bookmarks", context.getString(R.string.nav_bookmarks))
        assertEquals("Home", context.getString(R.string.nav_home))
        assertEquals("Settings", context.getString(R.string.nav_settings))

        assertEquals("Search titles or authors", context.getString(R.string.search_placeholder_titles_authors))
        assertEquals("All", context.getString(R.string.filter_all))
        assertEquals("Completed", context.getString(R.string.filter_completed))
        assertEquals("Not Completed", context.getString(R.string.filter_not_completed))

        assertEquals("Continue Reading", context.getString(R.string.action_continue_reading))
        assertEquals("Open Library", context.getString(R.string.action_open_library))
        assertEquals("Create Collection", context.getString(R.string.action_create_collection))

        assertEquals("Single Page", context.getString(R.string.reader_single_page))
        assertEquals("Vertical Continuous", context.getString(R.string.reader_vertical_continuous))
        assertEquals("LTR", context.getString(R.string.reader_direction_ltr))
        assertEquals("RTL", context.getString(R.string.reader_direction_rtl))
    }
}
