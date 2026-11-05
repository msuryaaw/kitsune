package com.example.kitsune.ui.theme

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * Centralized Material 3 Shapes tokens for Phase 11 & Phase 12 Warm Dark Design System.
 */
val Shapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(24.dp)
)

/**
 * Standard component shape tokens for cross-screen UI consistency (K-34).
 */
object ComponentShapes {
    val card = RoundedCornerShape(12.dp)
    val cardLarge = RoundedCornerShape(16.dp)
    val button = RoundedCornerShape(28.dp)
    val chip = RoundedCornerShape(8.dp)
    val segmentedContainer = RoundedCornerShape(12.dp)
    val segmentedItem = RoundedCornerShape(10.dp)
    val searchCapsule = CircleShape
    val coverTile = RoundedCornerShape(8.dp)
}
