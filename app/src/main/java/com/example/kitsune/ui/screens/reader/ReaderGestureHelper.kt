package com.example.kitsune.ui.screens.reader

/**
 * Pure helper for reader gesture navigation mapping and tap zone classification.
 * Keeps logic deterministic, cleanly testable, and isolated from Android framework classes.
 */
object ReaderGestureHelper {

    enum class SwipeAction {
        NONE,
        FORWARD,
        BACKWARD
    }

    /**
     * Resolves swipe action given horizontal delta in pixels, swipe threshold, and reading direction ("ltr" or "rtl").
     *
     * @param totalDx Total accumulated horizontal drag delta in pixels.
     * @param thresholdPx Minimum distance required to trigger navigation.
     * @param readingDirection "ltr" or "rtl" (case-insensitive).
     * @return SwipeAction.FORWARD, SwipeAction.BACKWARD, or SwipeAction.NONE if below threshold.
     */
    fun resolveHorizontalSwipeAction(
        totalDx: Float,
        thresholdPx: Float,
        readingDirection: String
    ): SwipeAction {
        if (kotlin.math.abs(totalDx) < thresholdPx) {
            return SwipeAction.NONE
        }

        val isRtl = readingDirection.equals("rtl", ignoreCase = true)

        return if (totalDx < 0f) {
            // Dragged Right-to-Left (finger moved left)
            if (isRtl) SwipeAction.BACKWARD else SwipeAction.FORWARD
        } else {
            // Dragged Left-to-Right (finger moved right)
            if (isRtl) SwipeAction.FORWARD else SwipeAction.BACKWARD
        }
    }

    /**
     * Evaluates whether a tap at (tapX, tapY) within a container of (width, height)
     * falls into the center area (middle 50% width and middle 50% height).
     *
     * Center zone:
     * - X between 25% and 75% of width.
     * - Y between 25% and 75% of height.
     *
     * Edge taps (outside center 50%) return false.
     */
    fun isCenterTap(
        tapX: Float,
        tapY: Float,
        containerWidth: Float,
        containerHeight: Float
    ): Boolean {
        if (containerWidth <= 0f || containerHeight <= 0f) return false

        val minX = containerWidth * 0.25f
        val maxX = containerWidth * 0.75f
        val minY = containerHeight * 0.25f
        val maxY = containerHeight * 0.75f

        return tapX in minX..maxX && tapY in minY..maxY
    }

    /**
     * Evaluates boundary transitions for Continuous Vertical reading.
     * Only triggers when at the boundaries (top or bottom).
     */
    fun resolveContinuousBoundaryAction(
        totalDx: Float,
        thresholdPx: Float,
        readingDirection: String,
        isAtTop: Boolean,
        isAtBottom: Boolean
    ): SwipeAction {
        val baseAction = resolveHorizontalSwipeAction(totalDx, thresholdPx, readingDirection)
        return when (baseAction) {
            SwipeAction.FORWARD -> if (isAtBottom) SwipeAction.FORWARD else SwipeAction.NONE
            SwipeAction.BACKWARD -> if (isAtTop) SwipeAction.BACKWARD else SwipeAction.NONE
            SwipeAction.NONE -> SwipeAction.NONE
        }
    }
}

