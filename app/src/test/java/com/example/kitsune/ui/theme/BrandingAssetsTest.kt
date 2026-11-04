package com.example.kitsune.ui.theme

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class BrandingAssetsTest {

    @Test
    fun brandingAssets_officialSourceFilesExist_andMatchRequiredResolution() {
        val rootDir = File(System.getProperty("user.dir") ?: ".")
        val projectRoot = if (File(rootDir, "design").exists()) rootDir else rootDir.parentFile ?: rootDir

        val iconFile = File(projectRoot, "design/branding/kitsune-icon.png")
        val splashFile = File(projectRoot, "design/branding/kitsune-splash.png")

        assertTrue("Official source asset kitsune-icon.png MUST exist", iconFile.exists() && iconFile.length() > 0L)
        assertTrue("Official source asset kitsune-splash.png MUST exist", splashFile.exists() && splashFile.length() > 0L)
    }

    @Test
    fun launcherIconDrawables_allMipmapDensitiesGeneratedFromOfficialIcon() {
        val rootDir = File(System.getProperty("user.dir") ?: ".")
        val appMainDir = File(rootDir, "src/main/res").takeIf { it.exists() }
            ?: File(rootDir, "app/src/main/res").takeIf { it.exists() }
            ?: File("app/src/main/res")

        val foregroundFile = File(appMainDir, "drawable/ic_launcher_foreground.png")
        val splashLogoFile = File(appMainDir, "drawable/kitsune_splash_logo.png")

        assertTrue("Generated adaptive icon foreground MUST exist", foregroundFile.exists() && foregroundFile.length() > 0L)
        assertTrue("Generated splash logo MUST exist", splashLogoFile.exists() && splashLogoFile.length() > 0L)

        val densities = listOf("mipmap-mdpi", "mipmap-hdpi", "mipmap-xhdpi", "mipmap-xxhdpi", "mipmap-xxxhdpi")
        densities.forEach { density ->
            val iconBmp = File(appMainDir, "$density/ic_launcher.png")
            val iconRoundBmp = File(appMainDir, "$density/ic_launcher_round.png")

            assertTrue("Icon in $density MUST exist", iconBmp.exists() && iconBmp.length() > 0L)
            assertTrue("Round icon in $density MUST exist", iconRoundBmp.exists() && iconRoundBmp.length() > 0L)
        }
    }

    @Test
    fun splashScreenConfiguration_usesKitsuneWarmDarkBackgroundColor() {
        val rootDir = File(System.getProperty("user.dir") ?: ".")
        val appMainDir = File(rootDir, "src/main/res").takeIf { it.exists() }
            ?: File(rootDir, "app/src/main/res").takeIf { it.exists() }
            ?: File("app/src/main/res")

        val colorsFile = File(appMainDir, "values/colors.xml")
        assertTrue("colors.xml MUST exist", colorsFile.exists())

        val colorsContent = colorsFile.readText()
        assertTrue("splash_background MUST be configured to Warm Dark #0F0E0C", colorsContent.contains("<color name=\"splash_background\">#0F0E0C</color>"))
    }
}
