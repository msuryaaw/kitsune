package com.example.kitsune.ui.screens.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderGestureHelperTest {

    private val threshold = 48f

    // 1. Empat kombinasi LTR/RTL gesture mapping
    @Test
    fun resolveHorizontalSwipeAction_ltr_rightToLeft_isForward() {
        val action = ReaderGestureHelper.resolveHorizontalSwipeAction(
            totalDx = -50f,
            thresholdPx = threshold,
            readingDirection = "ltr"
        )
        assertEquals(ReaderGestureHelper.SwipeAction.FORWARD, action)
    }

    @Test
    fun resolveHorizontalSwipeAction_ltr_leftToRight_isBackward() {
        val action = ReaderGestureHelper.resolveHorizontalSwipeAction(
            totalDx = 50f,
            thresholdPx = threshold,
            readingDirection = "ltr"
        )
        assertEquals(ReaderGestureHelper.SwipeAction.BACKWARD, action)
    }

    @Test
    fun resolveHorizontalSwipeAction_rtl_leftToRight_isForward() {
        val action = ReaderGestureHelper.resolveHorizontalSwipeAction(
            totalDx = 50f,
            thresholdPx = threshold,
            readingDirection = "rtl"
        )
        assertEquals(ReaderGestureHelper.SwipeAction.FORWARD, action)
    }

    @Test
    fun resolveHorizontalSwipeAction_rtl_rightToLeft_isBackward() {
        val action = ReaderGestureHelper.resolveHorizontalSwipeAction(
            totalDx = -50f,
            thresholdPx = threshold,
            readingDirection = "rtl"
        )
        assertEquals(ReaderGestureHelper.SwipeAction.BACKWARD, action)
    }

    // 2. Gesture di bawah threshold
    @Test
    fun resolveHorizontalSwipeAction_belowThreshold_isNone() {
        assertEquals(
            ReaderGestureHelper.SwipeAction.NONE,
            ReaderGestureHelper.resolveHorizontalSwipeAction(-40f, threshold, "ltr")
        )
        assertEquals(
            ReaderGestureHelper.SwipeAction.NONE,
            ReaderGestureHelper.resolveHorizontalSwipeAction(40f, threshold, "ltr")
        )
        assertEquals(
            ReaderGestureHelper.SwipeAction.NONE,
            ReaderGestureHelper.resolveHorizontalSwipeAction(-10f, threshold, "rtl")
        )
        assertEquals(
            ReaderGestureHelper.SwipeAction.NONE,
            ReaderGestureHelper.resolveHorizontalSwipeAction(0f, threshold, "rtl")
        )
    }

    // 3. Center tap reveal/hide dan edge tap no-op
    @Test
    fun isCenterTap_identifiesCenterCorrectly() {
        val width = 1000f
        val height = 2000f

        // Center tap (500, 1000)
        assertTrue(ReaderGestureHelper.isCenterTap(500f, 1000f, width, height))

        // Center edges (boundary of middle 50%: 250..750 width, 500..1500 height)
        assertTrue(ReaderGestureHelper.isCenterTap(250f, 500f, width, height))
        assertTrue(ReaderGestureHelper.isCenterTap(750f, 1500f, width, height))
    }

    @Test
    fun isCenterTap_rejectsEdgeTaps() {
        val width = 1000f
        val height = 2000f

        // Left edge
        assertFalse(ReaderGestureHelper.isCenterTap(100f, 1000f, width, height))
        // Right edge
        assertFalse(ReaderGestureHelper.isCenterTap(900f, 1000f, width, height))
        // Top edge
        assertFalse(ReaderGestureHelper.isCenterTap(500f, 200f, width, height))
        // Bottom edge
        assertFalse(ReaderGestureHelper.isCenterTap(500f, 1800f, width, height))
        // Top-left corner
        assertFalse(ReaderGestureHelper.isCenterTap(50f, 50f, width, height))
    }

    // 4. Boundary transitions for Continuous Vertical
    @Test
    fun resolveContinuousBoundaryAction_middleOfChapter_isNone() {
        val forwardLtr = ReaderGestureHelper.resolveContinuousBoundaryAction(
            totalDx = -60f,
            thresholdPx = threshold,
            readingDirection = "ltr",
            isAtTop = false,
            isAtBottom = false
        )
        assertEquals(ReaderGestureHelper.SwipeAction.NONE, forwardLtr)

        val backwardLtr = ReaderGestureHelper.resolveContinuousBoundaryAction(
            totalDx = 60f,
            thresholdPx = threshold,
            readingDirection = "ltr",
            isAtTop = false,
            isAtBottom = false
        )
        assertEquals(ReaderGestureHelper.SwipeAction.NONE, backwardLtr)
    }

    @Test
    fun resolveContinuousBoundaryAction_atBottom_forwardSwipesTransition() {
        // LTR forward is drag left (-totalDx)
        val actionLtr = ReaderGestureHelper.resolveContinuousBoundaryAction(
            totalDx = -60f,
            thresholdPx = threshold,
            readingDirection = "ltr",
            isAtTop = false,
            isAtBottom = true
        )
        assertEquals(ReaderGestureHelper.SwipeAction.FORWARD, actionLtr)

        // RTL forward is drag right (+totalDx)
        val actionRtl = ReaderGestureHelper.resolveContinuousBoundaryAction(
            totalDx = 60f,
            thresholdPx = threshold,
            readingDirection = "rtl",
            isAtTop = false,
            isAtBottom = true
        )
        assertEquals(ReaderGestureHelper.SwipeAction.FORWARD, actionRtl)
    }

    @Test
    fun resolveContinuousBoundaryAction_atTop_backwardSwipesTransition() {
        // LTR backward is drag right (+totalDx)
        val actionLtr = ReaderGestureHelper.resolveContinuousBoundaryAction(
            totalDx = 60f,
            thresholdPx = threshold,
            readingDirection = "ltr",
            isAtTop = true,
            isAtBottom = false
        )
        assertEquals(ReaderGestureHelper.SwipeAction.BACKWARD, actionLtr)

        // RTL backward is drag left (-totalDx)
        val actionRtl = ReaderGestureHelper.resolveContinuousBoundaryAction(
            totalDx = -60f,
            thresholdPx = threshold,
            readingDirection = "rtl",
            isAtTop = true,
            isAtBottom = false
        )
        assertEquals(ReaderGestureHelper.SwipeAction.BACKWARD, actionRtl)
    }
}

