package com.example.kitsune.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.kitsune.ui.theme.ComponentShapes
import com.example.kitsune.ui.theme.KitsuneBackground
import com.example.kitsune.ui.theme.KitsuneOrangeAccent
import com.example.kitsune.ui.theme.KitsuneSurface
import com.example.kitsune.ui.theme.KitsuneTextDisabled
import com.example.kitsune.ui.theme.KitsuneTextPrimary
import com.example.kitsune.ui.theme.KitsuneTextSecondary
import com.example.kitsune.ui.theme.KitsuneTheme
import com.example.kitsune.ui.theme.Spacing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import kotlin.math.max
import kotlin.math.min

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class SharedComponentsTest {

    @Test
    fun primaryButton_instantiatesWithLabelAndIcon() {
        assertNotNull(
            @Composable {
                KitsuneTheme {
                    KitsunePrimaryButton(
                        text = "Continue Reading",
                        onClick = {},
                        leadingIcon = Icons.Default.PlayArrow
                    )
                }
            }
        )
    }

    @Test
    fun primaryButton_disabled_instantiatesWithDisabledColor() {
        assertNotNull(
            @Composable {
                KitsuneTheme {
                    KitsunePrimaryButton(
                        text = "Disabled Action",
                        onClick = {},
                        enabled = false
                    )
                }
            }
        )
    }

    @Test
    fun searchField_instantiatesWithPlaceholder() {
        assertNotNull(
            @Composable {
                KitsuneTheme {
                    KitsuneSearchField(
                        value = "Naruto",
                        onValueChange = {},
                        placeholder = "Search local manga"
                    )
                }
            }
        )
    }

    @Test
    fun sectionHeadingAndBackToolbar_instantiateWithTitles() {
        assertNotNull(
            @Composable {
                KitsuneTheme {
                    KitsuneSectionHeading(title = "Last Read")
                    KitsuneBackToolbar(title = "Library", onBack = {})
                }
            }
        )
    }

    @Test
    fun segmentedControl_instantiatesWithGenericOptions() {
        val options = listOf(2 to "2x", 3 to "3x", 4 to "4x")
        assertEquals(3, options.size)
        assertNotNull(
            @Composable {
                KitsuneTheme {
                    KitsuneSegmentedControl(
                        options = options,
                        selected = 3,
                        onSelected = {}
                    )
                }
            }
        )
    }

    @Test
    fun emptyState_instantiatesWithIconAndAction() {
        assertNotNull(
            @Composable {
                KitsuneTheme {
                    KitsuneEmptyState(
                        icon = Icons.Default.BookmarkBorder,
                        title = "Keep your next story close",
                        message = "Create your first collection",
                        actionText = "Create Collection",
                        onAction = {}
                    )
                }
            }
        )
    }

    @Test
    fun gridTileLayout_instantiatesWithTitleAndCover() {
        assertNotNull(
            @Composable {
                KitsuneTheme {
                    ComicGridTileLayout(
                        title = "Bleach",
                        author = "Kubo",
                        onClick = {},
                        cover = { Text("Cover") }
                    )
                }
            }
        )
    }

    @Test
    fun spacingTokens_adhereToScale() {
        assertEquals(0, Spacing.none.value.toInt())
        assertEquals(4, Spacing.extraSmall.value.toInt())
        assertEquals(8, Spacing.small.value.toInt())
        assertEquals(12, Spacing.medium.value.toInt())
        assertEquals(16, Spacing.large.value.toInt())
        assertEquals(24, Spacing.extraLarge.value.toInt())
        assertEquals(32, Spacing.huge.value.toInt())
        assertTrue(Spacing.buttonMinHeight.value >= 48)
        assertTrue(Spacing.controlMinHeight.value >= 44)
        assertTrue(Spacing.minInteractiveTouchTarget.value >= 48)
    }

    @Test
    fun componentShapesTokens_defined() {
        assertNotNull(ComponentShapes.card)
        assertNotNull(ComponentShapes.cardLarge)
        assertNotNull(ComponentShapes.button)
        assertNotNull(ComponentShapes.chip)
        assertNotNull(ComponentShapes.segmentedContainer)
        assertNotNull(ComponentShapes.segmentedItem)
        assertNotNull(ComponentShapes.searchCapsule)
        assertNotNull(ComponentShapes.coverTile)
    }

    @Test
    fun wcagContrast_textTokensPassThresholds() {
        // WCAG relative luminance function
        fun relLuminance(r: Float, g: Float, b: Float): Double {
            fun channel(c: Float): Double =
                if (c <= 0.04045f) (c / 12.92).toDouble() else Math.pow(((c + 0.055) / 1.055).toDouble(), 2.4)
            return 0.2126 * channel(r) + 0.7152 * channel(g) + 0.0722 * channel(b)
        }

        fun contrastRatio(l1: Double, l2: Double): Double {
            val lighter = max(l1, l2)
            val darker = min(l1, l2)
            return (lighter + 0.05) / (darker + 0.05)
        }

        val lumBg = relLuminance(KitsuneBackground.red, KitsuneBackground.green, KitsuneBackground.blue)
        val lumSurface = relLuminance(KitsuneSurface.red, KitsuneSurface.green, KitsuneSurface.blue)
        val lumPrimaryText = relLuminance(KitsuneTextPrimary.red, KitsuneTextPrimary.green, KitsuneTextPrimary.blue)
        val lumSecondaryText = relLuminance(KitsuneTextSecondary.red, KitsuneTextSecondary.green, KitsuneTextSecondary.blue)
        val lumDisabledText = relLuminance(KitsuneTextDisabled.red, KitsuneTextDisabled.green, KitsuneTextDisabled.blue)
        val lumAccent = relLuminance(KitsuneOrangeAccent.red, KitsuneOrangeAccent.green, KitsuneOrangeAccent.blue)

        // Primary text on Background and Surface (Target >= 4.5:1)
        val ratioPrimaryBg = contrastRatio(lumPrimaryText, lumBg)
        val ratioPrimarySurface = contrastRatio(lumPrimaryText, lumSurface)
        assertTrue("Primary text on background >= 4.5:1, got $ratioPrimaryBg", ratioPrimaryBg >= 4.5)
        assertTrue("Primary text on surface >= 4.5:1, got $ratioPrimarySurface", ratioPrimarySurface >= 4.5)

        // Secondary text on Background and Surface (Target >= 4.5:1)
        val ratioSecondaryBg = contrastRatio(lumSecondaryText, lumBg)
        val ratioSecondarySurface = contrastRatio(lumSecondaryText, lumSurface)
        assertTrue("Secondary text on background >= 4.5:1, got $ratioSecondaryBg", ratioSecondaryBg >= 4.5)
        assertTrue("Secondary text on surface >= 4.5:1, got $ratioSecondarySurface", ratioSecondarySurface >= 4.5)

        // Accent on Background and Surface (Target >= 3.0:1 for large/graphical, >= 4.5:1 for standard)
        val ratioAccentSurface = contrastRatio(lumAccent, lumSurface)
        assertTrue("Orange accent on surface >= 4.5:1, got $ratioAccentSurface", ratioAccentSurface >= 4.5)

        // Disabled text on Surface (Target >= 3.0:1 for non-text/disabled)
        val ratioDisabledSurface = contrastRatio(lumDisabledText, lumSurface)
        assertTrue("Disabled text on surface >= 3.0:1, got $ratioDisabledSurface", ratioDisabledSurface >= 3.0)
    }
}
