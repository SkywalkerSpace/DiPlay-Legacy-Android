package com.shilapi.xcertplay.media

import android.graphics.SurfaceTexture
import android.view.Surface
import java.io.Closeable
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class MainSurfaceFrameWaiterTest {
    private val textures = mutableListOf<SurfaceTexture>()
    private val surfaces = mutableListOf<Surface>()

    @After fun close() {
        surfaces.forEach { it.release() }
        textures.forEach { it.release() }
    }

    @Test fun matchingOutputIsOneShotAndCanBeRearmedIncludingInsideCallback() {
        val waiter = MainSurfaceFrameWaiter()
        val target = surface()
        val delays = mutableListOf<Long>()
        waiter.await(target) {
            delays += it
            waiter.await(target) { next -> delays += next }
        }
        waiter.onFrameSubmitted(surface(), 0)
        assertTrue(delays.isEmpty())
        waiter.onFrameSubmitted(target, 45)
        waiter.onFrameSubmitted(target, 12)
        waiter.onFrameSubmitted(target, 0)
        assertEquals(listOf(45L, 12L), delays)
    }

    @Test fun replacementAndCancellationDoNotNotifyOldWaiters() {
        val waiter = MainSurfaceFrameWaiter()
        val old = surface()
        val next = surface()
        var count = 0
        waiter.await(old) { fail("Replaced waiter") }
        waiter.await(next) { count++ }
        waiter.cancel(old)
        waiter.onFrameSubmitted(old, 0)
        waiter.onFrameSubmitted(next, 0)
        assertEquals(1, count)
        waiter.await(next) { fail("Cancelled waiter") }
        waiter.cancel()
        waiter.onFrameSubmitted(next, 0)
        waiter.await(next) { fail("Detached waiter") }
        waiter.cancel(next)
        waiter.onFrameSubmitted(next, 0)
    }

    @Test fun closeCancelsCurrentAndFutureWaits() {
        val waiter = MainSurfaceFrameWaiter()
        val target = surface()
        waiter.await(target) { fail("Closed waiter") }
        waiter.close()
        waiter.onFrameSubmitted(target, 0)
        waiter.await(target) { fail("Wait after close") }
        waiter.onFrameSubmitted(target, 0)
    }

    @Test fun onlyMainDisplayDecoderNotClusterOrMirrorNotifiesTheHost() {
        val sink = AndroidMediaSink()
        val target = surface()
        var count = 0
        val diagnostics = mutableListOf<String>()
        sink.setVideoDiagnosticHandler(110) { diagnostics += it }
        try {
            sink.awaitMainSurfaceFrame(target) { count++ }
            fromDecoder(sink, 111, null, target)
            fromDecoder(sink, 110, "mirror=test", target)
            assertEquals(0, count)
            assertTrue("Mirror must not claim the session first frame", diagnostics.isEmpty())
            fromDecoder(sink, 110, null, target)
            assertEquals(1, count)
            assertEquals(listOf("first frame rendered"), diagnostics)
        } finally { sink.close() }
    }

    @Test fun sinkDetachExplicitCancelAndCloseCancelPendingWaits() {
        val sink = AndroidMediaSink()
        val target = surface()
        try {
            sink.setSurface(110, target)
            sink.awaitMainSurfaceFrame(target) { fail("Cleared output") }
            sink.clearSurface(110, target)
            fromDecoder(sink, 110, null, target)
            sink.awaitMainSurfaceFrame(target) { fail("Detached output") }
            assertTrue(sink.beginSurfaceDetach(target, false).await())
            fromDecoder(sink, 110, null, target)
            sink.awaitMainSurfaceFrame(target) { fail("Explicitly cancelled") }
            sink.cancelMainSurfaceFrameWait()
            fromDecoder(sink, 110, null, target)
            sink.awaitMainSurfaceFrame(target) { fail("Closed output") }
            sink.close()
            fromDecoder(sink, 110, null, target)
            sink.awaitMainSurfaceFrame(target) { fail("Wait after close") }
            fromDecoder(sink, 110, null, target)
        } finally { sink.close() }
    }

    private fun fromDecoder(sink: AndroidMediaSink, type: Int, label: String?, surface: Surface) {
        val decoder = AndroidMediaSink::class.java.getDeclaredMethod("newVideoDecoder", Int::class.javaPrimitiveType,
            Surface::class.java, String::class.java, Boolean::class.javaPrimitiveType)
            .apply { isAccessible = true }.invoke(sink, type, surface, label, false)
        try {
            @Suppress("UNCHECKED_CAST")
            val callback = decoder.javaClass.getDeclaredField("onFrameSubmitted").apply { isAccessible = true }
                .get(decoder) as (Surface, Long) -> Unit
            callback(surface, 0)
            @Suppress("UNCHECKED_CAST")
            val report = decoder.javaClass.getDeclaredField("report").apply { isAccessible = true }
                .get(decoder) as (String) -> Unit
            report("first frame rendered")
        } finally { (decoder as Closeable).close() }
    }

    private fun surface(): Surface {
        val texture = SurfaceTexture(0).also(textures::add)
        return Surface(texture).also(surfaces::add)
    }
}
