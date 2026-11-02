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
@Config(sdk = [28])
class MainSurfaceFrameWaiterTest {
    private val textures = mutableListOf<SurfaceTexture>()
    private val surfaces = mutableListOf<Surface>()

    @After fun close() {
        surfaces.forEach { it.release() }
        textures.forEach { it.release() }
    }

    @Test fun onlyMatchingSurfaceConsumesTheWaitAndItCanBeRearmed() {
        val waiter = MainSurfaceFrameWaiter()
        val target = surface()
        val other = surface()
        val delays = mutableListOf<Long>()
        waiter.await(target) { delays += it }
        waiter.onFrameSubmitted(other, 0)
        assertTrue(delays.isEmpty())
        waiter.onFrameSubmitted(target, 45)
        waiter.onFrameSubmitted(target, 0)
        assertEquals(listOf(45L), delays)
        waiter.await(target) { delays += it }
        waiter.onFrameSubmitted(target, 12)
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
        try {
            sink.awaitMainSurfaceFrame(target) { count++ }
            submitFromDecoder(sink, 111, null, target)
            submitFromDecoder(sink, 110, "mirror=test", target)
            assertEquals(0, count)
            submitFromDecoder(sink, 110, null, target)
            assertEquals(1, count)
        } finally {
            sink.close()
        }
    }

    @Test fun sinkDetachAndCloseCancelPendingWaits() {
        val sink = AndroidMediaSink()
        val target = surface()
        try {
            sink.setSurface(110, target)
            sink.awaitMainSurfaceFrame(target) { fail("Cleared output") }
            sink.clearSurface(110, target)
            submitFromDecoder(sink, 110, null, target)
            sink.awaitMainSurfaceFrame(target) { fail("Detached output") }
            assertTrue(sink.beginSurfaceDetach(target, false).await())
            submitFromDecoder(sink, 110, null, target)
            sink.awaitMainSurfaceFrame(target) { fail("Closed output") }
            sink.close()
            submitFromDecoder(sink, 110, null, target)
        } finally {
            sink.close()
        }
    }

    private fun submitFromDecoder(sink: AndroidMediaSink, type: Int, label: String?, surface: Surface) {
        val decoder = AndroidMediaSink::class.java.getDeclaredMethod("newVideoDecoder", Int::class.javaPrimitiveType,
            Surface::class.java, String::class.java, Boolean::class.javaPrimitiveType)
            .apply { isAccessible = true }.invoke(sink, type, surface, label, false)
        try {
            @Suppress("UNCHECKED_CAST")
            val callback = decoder.javaClass.getDeclaredField("onFrameSubmitted").apply { isAccessible = true }
                .get(decoder) as (Surface, Long) -> Unit
            callback(surface, 0)
        } finally {
            (decoder as Closeable).close()
        }
    }

    private fun surface(): Surface {
        val texture = SurfaceTexture(0).also(textures::add)
        return Surface(texture).also(surfaces::add)
    }
}
