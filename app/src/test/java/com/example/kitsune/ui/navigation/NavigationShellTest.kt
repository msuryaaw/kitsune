package com.example.kitsune.ui.navigation

import org.junit.Assert.assertEquals
import org.junit.Test

class NavigationShellTest {

    @Test
    fun topLevelDestinations_order_isBookmarksHomeSettings() {
        val destinations = TopLevelDestination.entries
        assertEquals(3, destinations.size)
        assertEquals(TopLevelDestination.BOOKMARKS, destinations[0])
        assertEquals(TopLevelDestination.HOME, destinations[1])
        assertEquals(TopLevelDestination.SETTINGS, destinations[2])
    }

    @Test
    fun topLevelDestination_startDestination_isHome() {
        val homeDest = TopLevelDestination.fromRoute("home")
        assertEquals(TopLevelDestination.HOME, homeDest)
    }

    @Test
    fun topLevelDestination_fromRoute_defaultsToHome() {
        val defaultDest = TopLevelDestination.fromRoute("unknown_route")
        assertEquals(TopLevelDestination.HOME, defaultDest)
    }

    @Test
    fun kitsuneDestinations_routesStructuredCorrectly() {
        assertEquals("home", KitsuneDestinations.HOME_ROUTE)
        assertEquals("bookmarks", KitsuneDestinations.BOOKMARKS_ROUTE)
        assertEquals("settings", KitsuneDestinations.SETTINGS_ROUTE)
        assertEquals("library", KitsuneDestinations.LIBRARY_ROUTE)
        assertEquals("history", KitsuneDestinations.HISTORY_ROUTE)

        assertEquals("comic_detail/123", KitsuneDestinations.comicDetailRoute(123L))
        assertEquals("reader/456", KitsuneDestinations.readerRoute(456L))
    }
}
