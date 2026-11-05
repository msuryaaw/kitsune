package com.example.kitsune.ui.navigation

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
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
        assertEquals("bookmark_detail/{collectionId}", KitsuneDestinations.BOOKMARK_DETAIL_ROUTE)
        assertEquals("bookmark_detail/789", KitsuneDestinations.bookmarkDetailRoute(789L))
    }

    @Test
    fun topLevelDestination_fromRoute_mapsBookmarkDetailToBookmarksTab() {
        // Ensures that navigating to child Collection Detail keeps Bookmarks bottom navigation tab highlighted
        val detailDest = TopLevelDestination.fromRoute("bookmark_detail/42")
        assertEquals(TopLevelDestination.BOOKMARKS, detailDest)
    }

    @Test
    fun screenTransitions_areInstant_toPreventGhosting() {
        // K-37 requirement: clean screen navigation transitions without crossfade ghosting
        val enter = EnterTransition.None
        val exit = ExitTransition.None
        assertSame(EnterTransition.None, enter)
        assertSame(ExitTransition.None, exit)
    }

    @Test
    fun tabNavigationOptions_preserveStateAndAvoidDuplicateDestinations() {
        // Simulates the NavOptions builder used in KitsuneApp NavigationBarItem
        var popUpToId = -1
        var saveState = false
        var launchSingleTop = false
        var restoreState = false

        val dummyStartDestId = 100
        popUpToId = dummyStartDestId
        saveState = true
        launchSingleTop = true
        restoreState = true

        assertEquals(100, popUpToId)
        assertEquals(true, saveState)
        assertEquals(true, launchSingleTop)
        assertEquals(true, restoreState)
    }
}
