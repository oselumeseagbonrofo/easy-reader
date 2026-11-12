package com.ebookreader.app.ui.reader

import androidx.compose.ui.geometry.Offset

data class ZoomPanState(
    val scale: Float = 1f,
    val offsetX: Float = 0f,
    val offsetY: Float = 0f,
    val isZoomed: Boolean = false
)

/**
 * Helper utility for handling PDF viewport pinch-zoom, pan, double-tap and gesture arbitration.
 * Prevents race conditions where multi-touch pinch-to-zoom out was previously misidentified as
 * long-press text selection (causing unwanted highlights instead of zooming out), and ensures
 * active selections do not lock the user out from zooming out.
 */
object PdfZoomGestureHelper {
    const val MIN_SCALE = 1f
    const val MAX_SCALE = 5f
    const val ZOOM_THRESHOLD = 1.02f
    const val DEFAULT_ZOOM_IN_SCALE = 2.2f

    /**
     * Calculates the new zoom and pan state given a zoom multiplier and pan delta.
     * When zoomed all the way out to 1.0x (<= ZOOM_THRESHOLD), offsets and zoom state reset cleanly.
     */
    fun calculateZoomPan(
        currentState: ZoomPanState,
        zoomChange: Float,
        panChange: Offset,
        viewportWidth: Float,
        viewportHeight: Float
    ): ZoomPanState {
        val newScale = (currentState.scale * zoomChange).coerceIn(MIN_SCALE, MAX_SCALE)
        return if (newScale > ZOOM_THRESHOLD) {
            val maxX = (viewportWidth * (newScale - 1f)) / 2f
            val maxY = (viewportHeight * (newScale - 1f)) / 2f
            ZoomPanState(
                scale = newScale,
                offsetX = (currentState.offsetX + panChange.x).coerceIn(-maxX, maxX),
                offsetY = (currentState.offsetY + panChange.y).coerceIn(-maxY, maxY),
                isZoomed = true
            )
        } else {
            ZoomPanState(
                scale = 1f,
                offsetX = 0f,
                offsetY = 0f,
                isZoomed = false
            )
        }
    }

    /**
     * Handles double tap zoom toggle.
     * When zoomed in (scale > 1.05f), double tap always resets zoom to 1.0f.
     * When at 1.0f, double tap zooms in to targetScale centered around the tap position.
     */
    fun calculateDoubleTapZoom(
        currentState: ZoomPanState,
        tapPosition: Offset,
        viewportWidth: Float,
        viewportHeight: Float,
        targetScale: Float = DEFAULT_ZOOM_IN_SCALE
    ): ZoomPanState {
        return if (currentState.scale > 1.05f) {
            ZoomPanState(
                scale = 1f,
                offsetX = 0f,
                offsetY = 0f,
                isZoomed = false
            )
        } else {
            val maxX = (viewportWidth * (targetScale - 1f)) / 2f
            val maxY = (viewportHeight * (targetScale - 1f)) / 2f
            val centerX = viewportWidth / 2f
            val centerY = viewportHeight / 2f
            ZoomPanState(
                scale = targetScale,
                offsetX = ((centerX - tapPosition.x) * (targetScale - 1f)).coerceIn(-maxX, maxX),
                offsetY = ((centerY - tapPosition.y) * (targetScale - 1f)).coerceIn(-maxY, maxY),
                isZoomed = true
            )
        }
    }

    /**
     * Evaluates whether a pinch or pan gesture should be processed.
     * An active selection must NEVER block zooming out or panning.
     */
    fun shouldProcessPinchOrPan(
        isSelectionActive: Boolean,
        pointerCount: Int,
        currentScale: Float
    ): Boolean {
        return pointerCount >= 2 || currentScale > ZOOM_THRESHOLD
    }

    /**
     * Determines whether a long-press text selection should be allowed to trigger.
     * If multiple pointers are detected (pinch-to-zoom) or the finger has moved past slop,
     * text selection is strictly aborted so pinch-to-zoom out is never hijacked by highlight.
     */
    fun shouldAllowLongPressSelection(
        pointerCount: Int,
        hasMovedPastSlop: Boolean,
        isZoomed: Boolean
    ): Boolean {
        if (pointerCount > 1) return false
        if (hasMovedPastSlop) return false
        return true
    }

    internal fun shouldResetZoomOnSettledPage(
        settledPage: Int,
        currentPage: Int
    ): Boolean = settledPage != currentPage

    internal fun reconcileLayoutEffect(
        currentState: ZoomPanState,
        isActivePage: Boolean
    ): Pair<ZoomPanState, Boolean> {
        if (!isActivePage) {
            val wasZoomed = currentState.isZoomed || currentState.scale > ZOOM_THRESHOLD
            val resetState = if (wasZoomed || currentState.scale != 1f || currentState.offsetX != 0f || currentState.offsetY != 0f) {
                ZoomPanState(scale = 1f, offsetX = 0f, offsetY = 0f, isZoomed = false)
            } else {
                currentState
            }
            return resetState to wasZoomed
        }
        return if (currentState.scale > ZOOM_THRESHOLD || currentState.isZoomed) {
            currentState to false
        } else {
            currentState.copy(offsetY = 0f) to false
        }
    }

    internal fun canApplyAsyncSelection(
        isActivePage: Boolean,
        isGestureInProgress: Boolean
    ): Boolean = isActivePage && !isGestureInProgress

    internal fun isPdfPagerScrollEnabled(
        activePageZoomed: Boolean,
        activeSelectionPage: Int?,
        currentPage: Int
    ): Boolean = !activePageZoomed && activeSelectionPage != currentPage

    internal fun shouldRecycleDisposedZoomTileBitmap(): Boolean = false
}
