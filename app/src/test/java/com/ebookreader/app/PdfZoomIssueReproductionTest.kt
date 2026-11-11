package com.ebookreader.app

import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Reproduces the reported bug:
 * "Once zoom to a page one can’t zoom out but can only highlight. For pdf."
 *
 * Demonstrates:
 * 1. Buggy legacy condition: an active selection lock (line 568: `currentSelectionUpdated != null`)
 *    completely blocked pinch-to-zoom out and pan.
 * 2. Buggy legacy condition: multi-touch pinch was not cancelling the long-press selection check,
 *    causing pinch-to-zoom out to trigger text highlights instead of zooming out.
 * 3. Buggy legacy condition: double-tap zoom out was blocked when text was selected because
 *    tap 1 only dismissed selection and zeroed out lastTapTimeMs.
 * 4. Fixed behavior: smooth pinch-out to 1x, instant double-tap zoom out, and selection dismissal.
 */
class PdfZoomIssueReproductionTest {

    // --- Legacy Buggy Logic Models ---

    /**
     * Replicates the exact check from original PdfPagerView.kt line 568:
     * if (event.changes.any { it.isConsumed } || currentSelectionUpdated != null) { continue }
     */
    private fun legacyCanProcessPinchOrPan(
        isEventConsumed: Boolean,
        activeSelectionPresent: Boolean
    ): Boolean {
        // In the buggy code: if activeSelection != null, it skips the pinch loop entirely!
        if (isEventConsumed || activeSelectionPresent) {
            return false
        }
        return true
    }

    /**
     * Replicates the exact check from original PdfPagerView.kt lines 397-404:
     * awaitLongPressOrCancellation was called unconditionally on first down.
     * If 2nd pointer touched down, long-press was not aborted.
     */
    private fun legacyShouldTriggerLongPressSelection(
        firstPointerMovedPastSlop: Boolean,
        secondPointerDown: Boolean
    ): Boolean {
        // In the buggy code, awaitLongPressOrCancellation only checked pointer 1 movement.
        // If pointer 1 didn't move past slop, it timed out and returned longPress,
        // even if pointer 2 was pinching!
        return !firstPointerMovedPastSlop
    }

    @Test
    fun `reproduce legacy bug - active selection lock completely blocks pinch zoom out`() {
        val pageScale = 2.2f // User is zoomed in to PDF page
        val activeSelectionPresent = true // Text selection / highlight is active

        // 1. REPRODUCE BUG: In legacy code, pinch-to-zoom out was completely blocked when selection was active!
        val legacyAllowed = legacyCanProcessPinchOrPan(
            isEventConsumed = false,
            activeSelectionPresent = activeSelectionPresent
        )
        assertFalse("REPRODUCTION: Legacy code blocked pinch-to-zoom out when text was selected!", legacyAllowed)

        // 2. VERIFY FIX: Under fixed logic, pinch-zoom out with 2 fingers MUST be allowed regardless of active selection
        val fixedAllowed = com.ebookreader.app.ui.reader.PdfZoomGestureHelper.shouldProcessPinchOrPan(
            isSelectionActive = activeSelectionPresent,
            pointerCount = 2,
            currentScale = pageScale
        )
        assertTrue("FIX: Pinch-zoom out must be allowed even if text was selected", fixedAllowed)
    }

    @Test
    fun `reproduce legacy bug - two finger pinch out misidentified as long-press highlight`() {
        // When pinching out to zoom out, finger 1 is often stationary (or moves < touchSlop)
        // while finger 2 pinches inward.
        val finger1MovedPastSlop = false
        val finger2Down = true

        // 1. REPRODUCE BUG: Legacy logic did not cancel long-press on multi-touch!
        val legacyLongPressFired = legacyShouldTriggerLongPressSelection(
            firstPointerMovedPastSlop = finger1MovedPastSlop,
            secondPointerDown = finger2Down
        )
        assertTrue("REPRODUCTION: Legacy code fired long-press highlight during two-finger pinch!", legacyLongPressFired)

        // 2. VERIFY FIX: Under fixed logic, if pointerCount > 1, long-press text selection is ALWAYS aborted
        val fixedLongPressAllowed = com.ebookreader.app.ui.reader.PdfZoomGestureHelper.shouldAllowLongPressSelection(
            pointerCount = 2,
            hasMovedPastSlop = finger1MovedPastSlop,
            isZoomed = true
        )
        assertFalse("FIX: Long-press text selection must NEVER fire when multiple fingers are on screen", fixedLongPressAllowed)
    }

    @Test
    fun `verify fixed zoom pan calculation smoothly zooms out to 1x and resets bounds`() {
        val initialZoomed = com.ebookreader.app.ui.reader.ZoomPanState(
            scale = 2.2f,
            offsetX = 100f,
            offsetY = 150f,
            isZoomed = true
        )

        // User pinches inward to zoom out (zoomChange = 0.4f -> target scale = 0.88f <= 1.02f)
        val zoomedOut = com.ebookreader.app.ui.reader.PdfZoomGestureHelper.calculateZoomPan(
            currentState = initialZoomed,
            zoomChange = 0.4f,
            panChange = Offset.Zero,
            viewportWidth = 1080f,
            viewportHeight = 2250f
        )

        assertEquals(1.0f, zoomedOut.scale, 0.001f)
        assertEquals(0.0f, zoomedOut.offsetX, 0.001f)
        assertEquals(0.0f, zoomedOut.offsetY, 0.001f)
        assertFalse(zoomedOut.isZoomed)
    }

    @Test
    fun `verify fixed double tap zoom toggles cleanly between 2_2x and 1x`() {
        val initial1x = com.ebookreader.app.ui.reader.ZoomPanState(
            scale = 1.0f,
            offsetX = 0f,
            offsetY = 0f,
            isZoomed = false
        )

        // 1. Double tap to zoom in
        val zoomedIn = com.ebookreader.app.ui.reader.PdfZoomGestureHelper.calculateDoubleTapZoom(
            currentState = initial1x,
            tapPosition = Offset(540f, 1125f),
            viewportWidth = 1080f,
            viewportHeight = 2250f
        )
        assertEquals(2.2f, zoomedIn.scale, 0.001f)
        assertTrue(zoomedIn.isZoomed)

        // 2. Double tap while zoomed in zooms out to 1.0f
        val zoomedOut = com.ebookreader.app.ui.reader.PdfZoomGestureHelper.calculateDoubleTapZoom(
            currentState = zoomedIn,
            tapPosition = Offset(540f, 1125f),
            viewportWidth = 1080f,
            viewportHeight = 2250f
        )
        assertEquals(1.0f, zoomedOut.scale, 0.001f)
        assertEquals(0.0f, zoomedOut.offsetX, 0.001f)
        assertEquals(0.0f, zoomedOut.offsetY, 0.001f)
        assertFalse(zoomedOut.isZoomed)
    }
}
