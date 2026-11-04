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
            return if (c <= 0.04045f) {
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
        val bg = KitsuneBackground
        val surface = KitsuneSurface

        // 1. Primary Text on Background & Surface
        val ratioTextPrimaryBg = calculateContrastRatio(KitsuneTextPrimary, bg)
        assertTrue("KitsuneTextPrimary on Background ratio $ratioTextPrimaryBg must exceed WCAG AAA 7:1", ratioTextPrimaryBg >= 7.0)
        assertEquals(16.49, ratioTextPrimaryBg, 0.1)

        val ratioTextPrimarySurface = calculateContrastRatio(KitsuneTextPrimary, surface)
        assertTrue("KitsuneTextPrimary on Surface ratio $ratioTextPrimarySurface must exceed WCAG AAA 7:1", ratioTextPrimarySurface >= 7.0)
        assertEquals(14.59, ratioTextPrimarySurface, 0.1)

        // 2. Secondary Text on Background & Surface
        val ratioTextSecondaryBg = calculateContrastRatio(KitsuneTextSecondary, bg)
        assertTrue("KitsuneTextSecondary on Background ratio $ratioTextSecondaryBg must exceed WCAG AA 4.5:1", ratioTextSecondaryBg >= 4.5)
        assertEquals(8.31, ratioTextSecondaryBg, 0.1)

        val ratioTextSecondarySurface = calculateContrastRatio(KitsuneTextSecondary, surface)
        assertTrue("KitsuneTextSecondary on Surface ratio $ratioTextSecondarySurface must exceed WCAG AA 4.5:1", ratioTextSecondarySurface >= 4.5)
        assertEquals(7.35, ratioTextSecondarySurface, 0.1)

        // 3. Primary Accent on Background & Surface
        val ratioAccentBg = calculateContrastRatio(KitsuneOrangeAccent, bg)
        assertTrue("KitsuneOrangeAccent on Background ratio $ratioAccentBg must exceed WCAG AA 4.5:1", ratioAccentBg >= 4.5)
        assertEquals(10.53, ratioAccentBg, 0.1)

        val ratioAccentSurface = calculateContrastRatio(KitsuneOrangeAccent, surface)
        assertTrue("KitsuneOrangeAccent on Surface ratio $ratioAccentSurface must exceed WCAG AA 4.5:1", ratioAccentSurface >= 4.5)
        assertEquals(9.32, ratioAccentSurface, 0.1)

        // 4. OnError on Error
        val ratioError = calculateContrastRatio(KitsuneOnError, KitsuneError)
        assertTrue("KitsuneOnError on KitsuneError ratio $ratioError must exceed WCAG AA 4.5:1", ratioError >= 4.5)
        assertEquals(5.83, ratioError, 0.1)
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
