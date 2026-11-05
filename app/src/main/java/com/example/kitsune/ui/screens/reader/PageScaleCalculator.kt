package com.example.kitsune.ui.screens.reader

/**
 * Page fitting mode options and mathematical scaling computations.
 */
object PageScaleCalculator {

    /**
     * Calculates the scale factor and rendered dimensions for an image within a viewport.
     *
     * Semantics:
     * - "fit_screen": Constrained by both width and height of the viewport. Entire image is visible
     *   without cropping or overflow.
     * - "fit_width": Image width matches the viewport width; height scales proportionally.
     *   Tall images (e.g. webtoon) extend vertically and are readable via vertical scroll.
     * - "fit_height": Image height matches the viewport height, provided the scaled width does not
     *   exceed the viewport width. If the scaled width would exceed the viewport width (very wide image),
     *   width is capped at viewportWidth to prevent unnavigable horizontal overflow and accidental clipping,
     *   ensuring all content remains accessible within the viewport.
     *
     * @param imageWidth Original intrinsic width of the image (> 0).
     * @param imageHeight Original intrinsic height of the image (> 0).
     * @param viewportWidth Available width of the viewport (> 0).
     * @param viewportHeight Available height of the viewport (> 0).
     * @param pageFit The fitting mode: "fit_screen", "fit_width", or "fit_height".
     * @return Pair of (renderedWidth, renderedHeight) in pixels, maintaining aspect ratio.
     */
    fun calculateDimensions(
        imageWidth: Int,
        imageHeight: Int,
        viewportWidth: Int,
        viewportHeight: Int,
        pageFit: String
    ): Pair<Int, Int> {
        if (imageWidth <= 0 || imageHeight <= 0 || viewportWidth <= 0 || viewportHeight <= 0) {
            return 0 to 0
        }

        val imageAspect = imageWidth.toDouble() / imageHeight.toDouble()

        return when (pageFit) {
            "fit_width" -> {
                val targetW = viewportWidth
                val targetH = (targetW / imageAspect).toInt().coerceAtLeast(1)
                targetW to targetH
            }
            "fit_height" -> {
                // Height matches viewport height subject to width constraint (no unhandled horizontal overflow)
                val targetH = viewportHeight
                val targetW = (targetH * imageAspect).toInt().coerceAtLeast(1)
                if (targetW > viewportWidth) {
                    // Wide image would overflow horizontally; clamp to viewport width to keep all content visible
                    val clampedW = viewportWidth
                    val clampedH = (clampedW / imageAspect).toInt().coerceAtLeast(1)
                    clampedW to clampedH
                } else {
                    targetW to targetH
                }
            }
            else -> { // "fit_screen"
                val scaleX = viewportWidth.toDouble() / imageWidth.toDouble()
                val scaleY = viewportHeight.toDouble() / imageHeight.toDouble()
                val scale = minOf(scaleX, scaleY)
                val targetW = (imageWidth * scale).toInt().coerceAtLeast(1)
                val targetH = (imageHeight * scale).toInt().coerceAtLeast(1)
                targetW to targetH
            }
        }
    }

    /**
     * Resolves the target decode dimensions for the decoder.
     * - "fit_width": In Single Page and Continuous Vertical, height expands naturally up to [maxDimension].
     * - "fit_height": Height is bounded by viewportHeight; width is bounded by viewportWidth to prevent
     *   unnecessary decode memory usage for wide images.
     * - "fit_screen": Both dimensions are bounded by the viewport.
     */
    fun calculateTargetDecodeDimensions(
        viewportWidth: Int,
        viewportHeight: Int,
        pageFit: String,
        maxDimension: Int = 4096
    ): Pair<Int, Int> {
        val safeW = viewportWidth.coerceAtLeast(1)
        val safeH = viewportHeight.coerceAtLeast(1)

        return when (pageFit) {
            "fit_width" -> {
                // In fit_width, allow height up to maxDimension so tall pages stay sharp
                safeW.coerceIn(1, maxDimension) to maxDimension
            }
            "fit_height" -> {
                safeW.coerceIn(1, maxDimension) to safeH.coerceIn(1, maxDimension)
            }
            else -> { // "fit_screen"
                safeW.coerceIn(1, maxDimension) to safeH.coerceIn(1, maxDimension)
            }
        }
    }
}
