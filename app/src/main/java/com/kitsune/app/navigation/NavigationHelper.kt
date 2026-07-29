package com.kitsune.app.navigation

import androidx.navigation.NavController
import androidx.navigation.NavOptionsBuilder

/**
 * Extension for NavController to prevent duplicate navigation to the same destination.
 * REVISION 10.5.2: Implemented navigation guard to improve responsiveness and prevent backstack bloat.
 */
fun NavController.navigateSafe(
    route: String,
    builder: NavOptionsBuilder.() -> Unit = {}
) {
    val currentRoute = currentBackStackEntry?.destination?.route
    // Guard: Only navigate if the target route is different from the current one
    // Note: We use startsWith for routes with arguments
    if (currentRoute != null && currentRoute == route.substringBefore("/")) {
        return
    }
    
    try {
        navigate(route, builder)
    } catch (e: Exception) {
        e.printStackTrace()
    }
}
