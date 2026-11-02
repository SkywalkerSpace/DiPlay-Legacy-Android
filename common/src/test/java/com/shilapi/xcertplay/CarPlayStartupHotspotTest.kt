package com.shilapi.xcertplay

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Looper
import android.provider.Settings
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.network.CarHotspotSettings
import com.shilapi.xcertplay.network.CarHotspotTethering
import com.shilapi.xcertplay.orchestration.WirelessHotspotMode
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowSettings
import org.robolectric.shadows.ShadowToast

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE, shadows = [CarPlayStartupHotspotTest.Hotspot::class])
class CarPlayStartupHotspotTest {
    private val controller = Robolectric.buildActivity(Activity::class.java).setup()
    private val activity get() = controller.get()
    private val overlays = mutableListOf<Intent>()
    private val writeSettings = mutableListOf<Intent>()
    private var readyCount = 0
    private var startup: CarPlayStartup? = null

    @Before fun reset() {
        for ((name, value) in listOf("overlayPermissionAsked" to false, "writeSettingsAsked" to false,
            "gaodeAttempted" to false, "gaodeReadyAt" to 0L)) {
            CarPlayStartup::class.java.getDeclaredField(name).apply { isAccessible = true }.set(null, value)
        }
        AirPlayPersistence.saveWirelessEnabled(activity, true)
        AirPlayPersistence.saveWirelessHotspotMode(activity, WirelessHotspotMode.MANUAL)
        CarHotspotSettings.setEnabled(activity, false)
        ShadowSettings.setCanDrawOverlays(true)
        Hotspot.allowed = true
        Hotspot.result = CarHotspotTethering.Result.READY
        Hotspot.calls = 0
        Hotspot.entered = CountDownLatch(1)
        Hotspot.release = CountDownLatch(1)
        Hotspot.exited = CountDownLatch(1)
        Hotspot.cancelled = false
        Hotspot.timeout = 0L
    }

    @After fun close() {
        startup?.close()
        Hotspot.release.countDown()
        if (Hotspot.calls > 0) assertTrue(Hotspot.exited.await(3, TimeUnit.SECONDS))
        shadowOf(Looper.getMainLooper()).idle()
        controller.pause().stop().destroy()
    }

    @Test fun overlayThenWriteSettingsThenHotspotThenGaode() {
        ShadowSettings.setCanDrawOverlays(false)
        Hotspot.allowed = false
        installGaode()
        val next = createStartup()
        next.start()
        assertEquals(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, overlays.single().action)
        assertTrue(writeSettings.isEmpty())
        assertEquals(0, Hotspot.calls)

        ShadowSettings.setCanDrawOverlays(true)
        next.onOverlayPermissionResult()
        assertEquals(Settings.ACTION_MANAGE_WRITE_SETTINGS, writeSettings.single().action)
        assertEquals("package:${activity.packageName}", writeSettings.single().dataString)
        assertEquals(0, Hotspot.calls)
        assertNull(shadowOf(activity).nextStartedActivity)

        Hotspot.allowed = true
        next.onWriteSettingsResult()
        assertTrue(Hotspot.entered.await(3, TimeUnit.SECONDS))
        assertFalse(next.ready)
        assertNull(shadowOf(activity).nextStartedActivity)
        completeHotspot()
        assertEquals(12_000L, Hotspot.timeout)
        assertEquals("com.autonavi.amapauto", shadowOf(activity).nextStartedActivity.component?.packageName)
        next.onResume()
        shadowOf(Looper.getMainLooper()).idleFor(2, TimeUnit.SECONDS)
        assertEquals(1, readyCount)
    }

    @Test fun deniedOverlayStillPreparesHotspotButSkipsGaodeOnAndroid10() {
        ShadowSettings.setCanDrawOverlays(false)
        installGaode()
        val next = createStartup()
        next.start()
        assertEquals(0, Hotspot.calls)
        next.onOverlayPermissionResult()
        next.onOverlayPermissionResult()
        completeHotspot()
        assertEquals(1, Hotspot.calls)
        assertEquals(1, readyCount)
        assertNull(shadowOf(activity).nextStartedActivity)
    }

    @Test fun recreatedStartupRechecksHotspotWithoutReopeningThePermissionPage() {
        Hotspot.allowed = false
        val first = createStartup()
        first.start()
        first.close()
        Hotspot.allowed = true
        createStartup().start()
        completeHotspot()
        first.onWriteSettingsResult()
        assertEquals(1, writeSettings.size)
        assertEquals(1, Hotspot.calls)
        assertEquals(1, readyCount)
    }

    @Test fun wiredStartsSavedHotspotEvenWithoutTheHomeScreenOptIn() {
        AirPlayPersistence.saveWirelessEnabled(activity, false)
        AirPlayPersistence.saveWirelessHotspotMode(activity, WirelessHotspotMode.EXISTING_WIFI)
        createStartup().start()
        completeHotspot()
        assertEquals(1, Hotspot.calls)
        assertEquals(1, readyCount)
        assertTrue(writeSettings.isEmpty())
    }

    @Test fun deniedWriteSettingsContinuesAndDoesNotAskAgainInTheSameProcess() {
        Hotspot.allowed = false
        Hotspot.result = CarHotspotTethering.Result.PERMISSION_REQUIRED
        val next = createStartup()
        next.start()
        next.onWriteSettingsResult()
        next.onWriteSettingsResult()
        completeHotspot()
        assertEquals(1, Hotspot.calls)
        assertEquals(1, readyCount)
        assertEquals(activity.getString(R.string.hotspot_control_missing), ShadowToast.getTextOfLatestToast())
        next.close()
        createStartup().start()
        completeHotspot()
        assertEquals(1, writeSettings.size)
        assertEquals(2, readyCount)
    }

    @Test fun unavailableWriteSettingsPageStillContinues() {
        Hotspot.allowed = false
        Hotspot.result = CarHotspotTethering.Result.PERMISSION_REQUIRED
        createStartup { throw IllegalStateException("No settings page") }.start()
        completeHotspot()
        assertEquals(1, readyCount)
    }

    @Test fun failedOrTimedOutHotspotShowsTheReasonAndContinuesOnlyOnce() {
        for ((result, message) in listOf(
            CarHotspotTethering.Result.FAILED to R.string.hotspot_control_failed,
            CarHotspotTethering.Result.TIMED_OUT to R.string.hotspot_control_timeout,
            CarHotspotTethering.Result.UNSUPPORTED to R.string.hotspot_control_unsupported,
        )) {
            Hotspot.result = result
            val next = createStartup()
            next.start()
            next.start()
            completeHotspot()
            next.onWriteSettingsResult()
            assertTrue(next.ready)
            assertEquals(activity.getString(message), ShadowToast.getTextOfLatestToast())
            next.close()
        }
        assertEquals(3, Hotspot.calls)
        assertEquals(3, readyCount)
    }

    @Test fun closeCancelsHotspotAndNeverLaunchesGaodeOrCarPlay() {
        installGaode()
        val next = createStartup()
        next.start()
        assertTrue(Hotspot.entered.await(3, TimeUnit.SECONDS))
        next.close()
        assertTrue(Hotspot.exited.await(3, TimeUnit.SECONDS))
        shadowOf(Looper.getMainLooper()).idleFor(30, TimeUnit.SECONDS)
        assertTrue(Hotspot.cancelled)
        assertEquals(0, readyCount)
        assertFalse(next.ready)
        assertNull(shadowOf(activity).nextStartedActivity)
    }

    @Test fun closeDropsAnAlreadyQueuedHotspotCompletion() {
        installGaode()
        val next = createStartup()
        next.start()
        Hotspot.release.countDown()
        awaitWorker()
        next.close()
        shadowOf(Looper.getMainLooper()).idleFor(30, TimeUnit.SECONDS)
        assertEquals(0, readyCount)
        assertNull(shadowOf(activity).nextStartedActivity)
    }

    @Test fun closeWhilePermissionIsPendingIgnoresLateResults() {
        Hotspot.allowed = false
        val next = createStartup()
        next.start()
        next.close()
        next.onWriteSettingsResult()
        next.start()
        assertEquals(0, Hotspot.calls)
        assertEquals(0, readyCount)
    }

    private fun createStartup(requestWrite: (Intent) -> Unit = { writeSettings += it }): CarPlayStartup =
        CarPlayStartup(activity, requestWrite, { overlays += it }) { readyCount++ }.also { startup = it }

    private fun completeHotspot() {
        Hotspot.release.countDown()
        awaitWorker()
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun awaitWorker() {
        val worker = CarPlayStartup::class.java.getDeclaredField("worker").apply { isAccessible = true }
            .get(startup) as ExecutorService
        worker.submit {}.get(3, TimeUnit.SECONDS)
    }

    private fun installGaode() {
        val name = ComponentName("com.autonavi.amapauto", "MainActivity")
        shadowOf(activity.packageManager).addActivityIfNotPresent(name)
        val info = activity.packageManager.getActivityInfo(name, 0)
        shadowOf(activity.packageManager).addResolveInfoForIntent(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_INFO).setPackage(name.packageName),
            android.content.pm.ResolveInfo().apply { activityInfo = info })
    }

    @Implements(CarHotspotTethering::class, isInAndroidSdk = false)
    class Hotspot {
        @Implementation fun permitted(context: Context): Boolean = allowed

        @Implementation fun enable(context: Context, isCancelled: () -> Boolean, timeoutMillis: Long,
            log: (String) -> Unit): CarHotspotTethering.Result {
            calls++
            timeout = timeoutMillis
            entered.countDown()
            try {
                release.await(3, TimeUnit.SECONDS)
                return result
            } catch (_: InterruptedException) {
                cancelled = isCancelled()
                Thread.currentThread().interrupt()
                return CarHotspotTethering.Result.CANCELLED
            } finally {
                exited.countDown()
            }
        }

        companion object {
            var allowed = true
            var result = CarHotspotTethering.Result.READY
            @Volatile var calls = 0
            @Volatile var timeout = 0L
            @Volatile var cancelled = false
            var entered = CountDownLatch(1)
            var release = CountDownLatch(1)
            var exited = CountDownLatch(1)
        }
    }
}
