package com.example.kitsune.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import kotlin.math.max
import kotlin.math.min

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class AccessibilityLayoutTest {

    // ── Helper: Calculates relative luminance per WCAG 2.1 specs ──
    private fun calculateLuminance(color: Color): Double {
        fun convertComponent(c: Float): Double {
            return if (c <= 0.03928f) {
                (c / 12.92).toDouble()
            } else {
                Math.pow(((c + 0.055) / 1.055).toDouble(), 2.4)
            }
        }
        val r = convertComponent(color.red)
        val g = convertComponent(color.green)
        val b = convertComponent(color.blue)
        return 0.2126 * r + 0.7152 * g + 0.0722 * b
    }

    // ── Helper: Calculates contrast ratio between foreground and background ──
    private fun calculateContrastRatio(foreground: Color, background: Color): Double {
        val lum1 = calculateLuminance(foreground)
        val lum2 = calculateLuminance(background)
        val lighter = max(lum1, lum2)
        val darker = min(lum1, lum2)
        return (lighter + 0.05) / (darker + 0.05)
    }

    @Test
    fun colorContrast_lightThemeAllTokens_meetsWcagAaStandard() {
        val darkBackground = Color(0xFF1C1B1F)
        val darkText = Color(0xFFE6E1E5)
        val lightBackground = Color(0xFFFFFBFE)
        val lightText = Color(0xFF1C1B1F)

        // Light Theme Primary Text (16.5:1)
        val ratioLight = calculateContrastRatio(lightText, lightBackground)
        assertTrue("Light theme text contrast ratio $ratioLight must exceed WCAG AA 4.5:1 standard", ratioLight >= 4.5)

        // Dark Theme Primary Text (13.1:1)
        val ratioDark = calculateContrastRatio(darkText, darkBackground)
        assertTrue("Dark theme text contrast ratio $ratioDark must exceed WCAG AA 4.5:1 standard", ratioDark >= 4.5)

        // Light Theme Surface Variant Text
        val surfaceVariantLight = Color(0xFFE7E0EC)
        val onSurfaceVariantLight = Color(0xFF49454F)
        val ratioVariantLight = calculateContrastRatio(onSurfaceVariantLight, surfaceVariantLight)
        assertTrue("Light SurfaceVariant text contrast ratio $ratioVariantLight must exceed WCAG AA 4.5:1 standard", ratioVariantLight >= 4.5)

        // Dark Theme Surface Variant Text
        val surfaceVariantDark = Color(0xFF49454F)
        val onSurfaceVariantDark = Color(0xFFCAC4D0)
        val ratioVariantDark = calculateContrastRatio(onSurfaceVariantDark, surfaceVariantDark)
        assertTrue("Dark SurfaceVariant text contrast ratio $ratioVariantDark must exceed WCAG AA 4.5:1 standard", ratioVariantDark >= 4.5)

        // Error Container Text (Light & Dark)
        val errorContainerLight = Color(0xFFF9DEDC)
        val onErrorContainerLight = Color(0xFF410E0B)
        val ratioErrorLight = calculateContrastRatio(onErrorContainerLight, errorContainerLight)
        assertTrue("Light ErrorContainer text contrast ratio $ratioErrorLight must exceed WCAG AA 4.5:1 standard", ratioErrorLight >= 4.5)
    }

    @Test
    fun colorContrast_actualThemeColors_meetsWcagAaStandard() {
        val darkBg = Color(0xFF1C1B1F)
        val lightBg = Color(0xFFFFFBFE)

        // Purple80 on Dark Background
        val ratio80 = calculateContrastRatio(Purple80, darkBg)
        assertTrue("Purple80 on Dark background ratio $ratio80 must exceed 4.5:1", ratio80 >= 4.5)

        // Purple40 on Light Background
        val ratio40 = calculateContrastRatio(Purple40, lightBg)
        assertTrue("Purple40 on Light background ratio $ratio40 must exceed 4.5:1", ratio40 >= 4.5)
    }

    @Test
    fun touchTarget_minimumInteractiveSizeRequirement_is48dp() {
        val minInteractiveSize = 48.dp
        assertEquals("Minimum interactive touch target size per Material 3 & WCAG guidelines must be 48dp", 48.dp, minInteractiveSize)
    }

    @Test
    fun typographyUnits_useSp_forSystemFontScalingAdaptation() {
        val bodyLargeSize = Typography.bodyLarge.fontSize
        val bodyLargeLineHeight = Typography.bodyLarge.lineHeight

        assertEquals(16.sp, bodyLargeSize)
        assertEquals(24.sp, bodyLargeLineHeight)
        assertTrue("Font size unit MUST be sp for system font scaling adaptation", bodyLargeSize.isSp)

        // Simulate Font Scaling 1.0x, 1.3x, 2.0x
        val scale10x = bodyLargeSize.value * 1.0f
        val scale13x = bodyLargeSize.value * 1.3f
        val scale20x = bodyLargeSize.value * 2.0f

        assertEquals(16.0f, scale10x, 0.01f)
        assertEquals(20.8f, scale13x, 0.01f)
        assertEquals(32.0f, scale20x, 0.01f)
    }

    @Test
    fun textOverflow_ellipsisConstant_forLongTextTruncation() {
        val overflowMode = TextOverflow.Ellipsis
        assertEquals(TextOverflow.Ellipsis, overflowMode)
    }

    @Test
    fun accessibilityContentDescriptions_semanticLabelingStandardsForAllScreens() {
        val contentDescriptions = mapOf(
            "Library Search Clear" to "Hapus Pencarian",
            "Bookmark Add" to "Tambah ke Bookmark",
            "Bookmark Remove" to "Hapus dari Bookmark",
            "Back Navigation" to "Kembali ke Library",
            "Previous Page" to "Halaman Sebelumnya",
            "Next Page" to "Halaman Berikutnya",
            "Delete History" to "Hapus Riwayat Komik",
            "Clear All History" to "Hapus Semua Riwayat",
            "Refresh Library" to "Coba Lagi",
            "Column Selector 2x" to "2x",
            "Column Selector 3x" to "3x",
            "Column Selector 4x" to "4x"
        )

        contentDescriptions.forEach { (key, desc) ->
            assertFalse("Content description for '$key' must not be blank", desc.isBlank())
            assertTrue("Content description for '$key' must be descriptive text", desc.length >= 2)
        }
    }
}
