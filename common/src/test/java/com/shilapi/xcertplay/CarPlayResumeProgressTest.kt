package com.shilapi.xcertplay

import android.graphics.SurfaceTexture
import android.os.Handler
import android.os.Looper
import android.view.Surface
import android.view.SurfaceView
import android.view.TextureView
import android.view.View
import android.widget.TextView
import com.shilapi.xcertplay.airplay.AirPlaySession
import com.shilapi.xcertplay.airplay.AirPlaySessionListener
import com.shilapi.xcertplay.media.AndroidMediaSink
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
@LooperMode(LooperMode.Mode.PAUSED)
class CarPlayResumeProgressTest {
    private lateinit var activity: CarPlayHostActivity
    private lateinit var renderer: AndroidMediaSink
    private lateinit var panel: View
    private val outputs = mutableListOf<Pair<SurfaceTexture, Surface>>()

    @Before fun prepare() {
        activity = Robolectric.buildActivity(CarPlayHostActivity::class.java).get()
        renderer = AndroidMediaSink()
        panel = View(activity)
        set("sink", renderer)
        set("connectionPanel", panel)
        set("stageStatusView", TextView(activity))
        set("stageDiagnosticView", TextView(activity))
        set("isActivityStarted", true)
    }

    @After fun close() {
        (get("mainHandler") as Handler).removeCallbacksAndMessages(null)
        renderer.close()
        for (field in listOf("teardownExecutor", "airPlayCommandExecutor")) {
            (get(field) as ExecutorService).shutdownNow()
        }
        outputs.forEach { (texture, surface) -> surface.release(); texture.release() }
    }

    @Test fun activeStreamsAndReplayedSessionFirstFrameDoNotHideProgress() {
        streams().addAll(listOf(110, 111))
        invoke("updateDebugOverlays")
        assertEquals(View.VISIBLE, panel.visibility)
        val session = mock(AirPlaySession::class.java)
        set("activeAirPlaySession", session)
        val listener = activity.javaClass.getDeclaredMethod("createSessionListener", Int::class.javaPrimitiveType)
            .apply { isAccessible = true }.invoke(activity, get("restartGeneration")) as AirPlaySessionListener
        listener.onVideoFrameRendered(session)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(View.VISIBLE, panel.visibility)
    }

    @Test fun textureWaitsForCurrentTextureContentAndIgnoresDashboardOnlyUpdates() {
        val texture = output().first
        val listener = get("textureListener") as TextureView.SurfaceTextureListener
        set("currentSurfaceTexture", texture)
        streams().add(111)
        listener.onSurfaceTextureUpdated(texture)
        assertEquals(View.VISIBLE, panel.visibility)
        streams().add(110)
        listener.onSurfaceTextureUpdated(output().first)
        assertEquals(View.VISIBLE, panel.visibility)
        listener.onSurfaceTextureUpdated(texture)
        assertEquals(View.GONE, panel.visibility)
    }

    @Test fun surfaceViewKeepsProgressForTwentySecondsUntilNewOutputThenHonoursPacingDelay() {
        val surface = prepareSurfaceView()
        attach(surface)
        shadowOf(Looper.getMainLooper()).idleFor(20, TimeUnit.SECONDS)
        assertEquals(View.VISIBLE, panel.visibility)
        submit(surface, 80)
        shadowOf(Looper.getMainLooper()).idleFor(79, TimeUnit.MILLISECONDS)
        assertEquals(View.VISIBLE, panel.visibility)
        shadowOf(Looper.getMainLooper()).idleFor(1, TimeUnit.MILLISECONDS)
        assertEquals(View.GONE, panel.visibility)
    }

    @Test fun oldSurfaceAndQueuedCallbackCannotHideANewWait() {
        val old = prepareSurfaceView()
        attach(old)
        submit(old, 80)
        val next = prepareSurfaceView()
        attach(next)
        submit(old)
        shadowOf(Looper.getMainLooper()).idleFor(100, TimeUnit.MILLISECONDS)
        assertEquals(View.VISIBLE, panel.visibility)
        submit(next)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(View.GONE, panel.visibility)
    }

    @Test fun stoppedHostIgnoresQueuedOutputAndSameSurfaceCanResumeAgain() {
        val surface = prepareSurfaceView()
        attach(surface)
        submit(surface, 80)
        invoke("onStop")
        shadowOf(Looper.getMainLooper()).idleFor(100, TimeUnit.MILLISECONDS)
        assertEquals(View.VISIBLE, panel.visibility)
        set("isActivityStarted", true)
        attach(surface)
        submit(surface)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(View.GONE, panel.visibility)
        assertSame("Resuming the picture must keep the same live sink", renderer, get("sink"))
    }

    @Test fun mainStreamStoppingShowsProgressAgain() {
        set("mainPictureVisible", true)
        streams().add(110)
        invoke("updateDebugOverlays")
        assertEquals(View.GONE, panel.visibility)
        activity.javaClass.getDeclaredMethod("onScreenStreamStateChanged", Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType, Boolean::class.javaPrimitiveType)
            .apply { isAccessible = true }.invoke(activity, get("restartGeneration"), 110, false)
        assertEquals(View.VISIBLE, panel.visibility)
    }

    @Test fun connectionStageKeepsNormalTextAndElapsedTimeDuringRecovery() {
        activity.javaClass.getDeclaredMethod("setConnectionStage", String::class.java, Boolean::class.javaPrimitiveType)
            .apply { isAccessible = true }.invoke(activity, "Opening CarPlay…", true)
        shadowOf(Looper.getMainLooper()).idleFor(20, TimeUnit.SECONDS)
        assertEquals("Opening CarPlay…", (get("stageStatusView") as TextView).text.toString())
        assertTrue((get("stageDiagnosticView") as TextView).text.toString().contains("20"))
        assertEquals(View.VISIBLE, panel.visibility)
    }

    private fun prepareSurfaceView(): Surface {
        set("fallbackVideoView", SurfaceView(activity))
        val surface = output().second
        @Suppress("UNCHECKED_CAST")
        val owner = get("videoSurfaceOwner") as CarPlayVideoSurfaceOwner<Surface>
        owner.replace(surface, releaseOnDetach = false)
        return surface
    }

    private fun attach(surface: Surface) {
        activity.javaClass.getDeclaredMethod("attachSurface", Surface::class.java)
            .apply { isAccessible = true }.invoke(activity, surface)
    }

    private fun submit(surface: Surface, delayMillis: Long = 0) {
        val waiter = AndroidMediaSink::class.java.getDeclaredField("mainSurfaceFrameWaiter")
            .apply { isAccessible = true }.get(renderer)
        waiter.javaClass.getDeclaredMethod("onFrameSubmitted", Surface::class.java, Long::class.javaPrimitiveType)
            .apply { isAccessible = true }.invoke(waiter, surface, delayMillis)
    }

    private fun output(): Pair<SurfaceTexture, Surface> {
        val texture = SurfaceTexture(0)
        return (texture to Surface(texture)).also(outputs::add)
    }

    @Suppress("UNCHECKED_CAST")
    private fun streams() = get("activeScreenStreamTypes") as MutableSet<Int>

    private fun invoke(name: String) = activity.javaClass.getDeclaredMethod(name)
        .apply { isAccessible = true }.invoke(activity)

    private fun get(name: String): Any? = activity.javaClass.getDeclaredField(name)
        .apply { isAccessible = true }.get(activity)

    private fun set(name: String, value: Any) = activity.javaClass.getDeclaredField(name)
        .apply { isAccessible = true }.set(activity, value)
}
