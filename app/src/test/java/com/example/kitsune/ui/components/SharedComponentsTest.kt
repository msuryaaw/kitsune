package com.example.kitsune.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.kitsune.ui.theme.KitsuneTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

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
}
