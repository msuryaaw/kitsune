package com.example.kitsune.ui.screens.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PageScaleCalculatorTest {

    @Test
    fun squareImage_fitScreen_scalesToMinDimension() {
        val (w, h) = PageScaleCalculator.calculateDimensions(
            imageWidth = 1000,
            imageHeight = 1000,
            viewportWidth = 800,
            viewportHeight = 1200,
            pageFit = "fit_screen"
        )
        // Scaled by 800 / 1000 = 0.8
        assertEquals(800, w)
        assertEquals(800, h)
        assertTrue(w <= 800 && h <= 1200)
    }

    @Test
    fun portraitImage_fitScreen_fitsWithinBoundsWithoutCrop() {
        val (w, h) = PageScaleCalculator.calculateDimensions(
            imageWidth = 1000,
            imageHeight = 1500,
            viewportWidth = 800,
            viewportHeight = 1200,
            pageFit = "fit_screen"
        )
        // Aspect ratio is 1000:1500 = 2:3.
        // Fit within 800x1200 -> 800x1200 exact fit
        assertEquals(800, w)
        assertEquals(1200, h)
    }

    @Test
    fun landscapeImage_fitScreen_constrainedByWidth() {
        val (w, h) = PageScaleCalculator.calculateDimensions(
            imageWidth = 1600,
            imageHeight = 900,
            viewportWidth = 800,
            viewportHeight = 1200,
            pageFit = "fit_screen"
        )
        // 800 / 1600 = 0.5 scale -> 800 x 450
        assertEquals(800, w)
        assertEquals(450, h)
        assertTrue(w <= 800 && h <= 1200)
    }

    @Test
    fun veryTallWebtoonImage_fitScreen_constrainedByHeight() {
        val (w, h) = PageScaleCalculator.calculateDimensions(
            imageWidth = 800,
            imageHeight = 6400, // 1:8 aspect ratio
            viewportWidth = 800,
            viewportHeight = 1600,
            pageFit = "fit_screen"
        )
        // Scale by 1600 / 6400 = 0.25 -> width becomes 200, height 1600
        assertEquals(200, w)
        assertEquals(1600, h)
        // Aspect ratio maintained: 200 / 1600 = 0.125 == 800 / 6400
        assertEquals(800.0 / 6400.0, w.toDouble() / h.toDouble(), 0.001)
    }

    @Test
    fun veryTallWebtoonImage_fitWidth_matchesViewportWidthAndExpandsHeight() {
        val (w, h) = PageScaleCalculator.calculateDimensions(
            imageWidth = 800,
            imageHeight = 6400, // 1:8 aspect ratio
            viewportWidth = 800,
            viewportHeight = 1600,
            pageFit = "fit_width"
        )
        // In fit_width, width is 800, height expands to 6400 for scrolling
        assertEquals(800, w)
        assertEquals(6400, h)
        // Aspect ratio preserved
        assertEquals(800.0 / 6400.0, w.toDouble() / h.toDouble(), 0.001)
    }

    @Test
    fun veryWideImage_fitHeight_clampsToViewportWidthPreventingOverflow() {
        // Image 3200x800 in 800x1200 viewport
        val (w, h) = PageScaleCalculator.calculateDimensions(
            imageWidth = 3200,
            imageHeight = 800, // 4:1 aspect ratio
            viewportWidth = 800,
            viewportHeight = 1200,
            pageFit = "fit_height"
        )
        // To prevent unnavigable horizontal overflow (4800px wide in an 800px viewport),
        // width is conservatively clamped to viewport width (800px) while maintaining the 4:1 aspect ratio
        assertEquals(800, w)
        assertEquals(200, h)
        // Aspect ratio strictly preserved: 800 / 200 = 4.0 == 3200 / 800
        assertEquals(3200.0 / 800.0, w.toDouble() / h.toDouble(), 0.001)
        assertTrue("Rendered width must not exceed viewport width", w <= 800)
        assertTrue("Rendered height must not exceed viewport height", h <= 1200)
    }

    @Test
    fun tallImage_fitHeight_matchesViewportHeightWhenWidthWithinBounds() {
        // Image 600x1200 (1:2 aspect ratio) in 800x1200 viewport
        val (w, h) = PageScaleCalculator.calculateDimensions(
            imageWidth = 600,
            imageHeight = 1200,
            viewportWidth = 800,
            viewportHeight = 1200,
            pageFit = "fit_height"
        )
        assertEquals(600, w)
        assertEquals(1200, h)
        assertEquals(600.0 / 1200.0, w.toDouble() / h.toDouble(), 0.001)
    }

    @Test
    fun landscapeViewport_fitScreen_handlesCorrectly() {
        val (w, h) = PageScaleCalculator.calculateDimensions(
            imageWidth = 1000,
            imageHeight = 1500,
            viewportWidth = 1600,
            viewportHeight = 900,
            pageFit = "fit_screen"
        )
        // Constrained by height: 900 / 1500 = 0.6 -> width = 600, height = 900
        assertEquals(600, w)
        assertEquals(900, h)
        assertTrue(w <= 1600 && h <= 900)
    }

    @Test
    fun zeroOrNegativeInput_returnsZeroZeroSafely() {
        val (w1, h1) = PageScaleCalculator.calculateDimensions(0, 1000, 800, 1200, "fit_screen")
        assertEquals(0, w1)
        assertEquals(0, h1)

        val (w2, h2) = PageScaleCalculator.calculateDimensions(1000, 0, 800, 1200, "fit_width")
        assertEquals(0, w2)
        assertEquals(0, h2)

        val (w3, h3) = PageScaleCalculator.calculateDimensions(1000, 1500, -100, 1200, "fit_height")
        assertEquals(0, w3)
        assertEquals(0, h3)

        val (w4, h4) = PageScaleCalculator.calculateDimensions(1000, 1500, 800, 0, "fit_screen")
        assertEquals(0, w4)
        assertEquals(0, h4)
    }

    @Test
    fun targetDecodeDimensions_fitWidthAllowsHeightExpansion() {
        val (reqW, reqH) = PageScaleCalculator.calculateTargetDecodeDimensions(
            viewportWidth = 1080,
            viewportHeight = 1920,
            pageFit = "fit_width",
            maxDimension = 4096
        )
        assertEquals(1080, reqW)
        assertEquals(4096, reqH)
    }

    @Test
    fun targetDecodeDimensions_fitHeightCapsBothDimensions() {
        val (reqW, reqH) = PageScaleCalculator.calculateTargetDecodeDimensions(
            viewportWidth = 1080,
            viewportHeight = 1920,
            pageFit = "fit_height",
            maxDimension = 4096
        )
        assertEquals(1080, reqW)
        assertEquals(1920, reqH)
    }

    @Test
    fun targetDecodeDimensions_fitScreenCapsBothDimensions() {
        val (reqW, reqH) = PageScaleCalculator.calculateTargetDecodeDimensions(
            viewportWidth = 1080,
            viewportHeight = 1920,
            pageFit = "fit_screen",
            maxDimension = 4096
        )
        assertEquals(1080, reqW)
        assertEquals(1920, reqH)
    }
}
