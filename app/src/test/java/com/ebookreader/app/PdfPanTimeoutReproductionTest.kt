package com.ebookreader.app

import androidx.compose.ui.input.pointer.PointerEventTimeoutCancellationException
import kotlinx.coroutines.TimeoutCancellationException
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * Inside `awaitEachGesture { }`, `withTimeout` resolves to the AwaitPointerEventScope member,
 * which throws PointerEventTimeoutCancellationException. If the gesture code catches
 * kotlinx TimeoutCancellationException instead, the timeout escapes, awaitEachGesture
 * cancels the gesture, and every finger-hold-then-drag (pan) on a zoomed PDF page is dropped.
 */
class PdfPanTimeoutReproductionTest {
    @Test
    fun `pointer scope timeout is NOT a kotlinx TimeoutCancellationException`() {
        val thrown: Throwable = PointerEventTimeoutCancellationException(400L)
        assertFalse(
            "catch(TimeoutCancellationException) would not catch the pointer-scope timeout",
            thrown is TimeoutCancellationException
        )
    }
}
