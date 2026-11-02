package com.shilapi.xcertplay

import android.app.Activity
import android.app.ActivityManager
import android.content.Context
import android.content.ComponentName
import android.content.Intent
import android.net.wifi.WifiManager
import com.shilapi.xcertplay.orchestration.WirelessHotspotMode
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
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
import org.robolectric.shadows.ShadowSettings

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class CarPlayStartupTest {
    private val controller = Robolectric.buildActivity(Activity::class.java).setup()
    private val activity get() = controller.get()
    private val overlayRequests = mutableListOf<Intent>()
    private var readyCount = 0
    private var startup: CarPlayStartup? = null

    @Before fun reset() {
        AirPlayPersistence.saveWirelessEnabled(activity, true)
        AirPlayPersistence.saveWirelessHotspotMode(activity, WirelessHotspotMode.EXISTING_WIFI)
        setStartupField("overlayPermissionAsked", false)
        setStartupField("writeSettingsAsked", false)
        setStartupField("gaodeAttempted", false)
        setStartupField("gaodeReadyAt", 0L)
        ShadowSettings.setCanDrawOverlays(false)
        setRunningProcesses()
    }

    @After fun close() {
        startup?.close()
        if (!activity.isDestroyed) controller.pause().stop().destroy()
    }

    @Test fun requestsOverlayBeforeLaunchingGaodeAndContinuesAfterDenial() {
        installGaode()
        val startup = createStartup()
        startup.start()
        assertEquals(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, overlayRequests.single().action)
        assertEquals("package:${activity.packageName}", overlayRequests.single().dataString)
        assertNull(shadowOf(activity).nextStartedActivity)
        assertFalse(startup.ready)

        startup.onOverlayPermissionResult()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(startup.ready)
        assertEquals(1, readyCount)
        assertNull(shadowOf(activity).nextStartedActivity)
        startup.onOverlayPermissionResult()
        assertEquals(1, readyCount)
    }

    @Test fun grantLaunchesGaodeThenReturnsToCarPlayTaskAfterTwoSeconds() {
        installGaode()
        val startup = createStartup()
        startup.start()
        ShadowSettings.setCanDrawOverlays(true)
        startup.onOverlayPermissionResult()
        assertEquals("com.autonavi.amapauto", shadowOf(activity).nextStartedActivity.component?.packageName)
        startup.onResume()
        startup.onPause()
        shadowOf(Looper.getMainLooper()).idleFor(1, TimeUnit.SECONDS)
        assertFalse(startup.ready)
        assertNull(shadowOf(activity).nextStartedActivity)
        shadowOf(Looper.getMainLooper()).idleFor(1, TimeUnit.SECONDS)
        val returned = shadowOf(activity).nextStartedActivity
        assertEquals(ComponentName(activity, CarPlayHostActivity::class.java), returned.component)
        for (flag in listOf(Intent.FLAG_ACTIVITY_NEW_TASK, Intent.FLAG_ACTIVITY_CLEAR_TOP, Intent.FLAG_ACTIVITY_SINGLE_TOP)) {
            assertEquals(flag, returned.flags and flag)
        }
        assertTrue(startup.ready)
        assertEquals(1, readyCount)
    }

    @Test fun alreadyGrantedPermissionDoesNotOpenSettingsAndMissingGaodeDoesNotDelayStartup() {
        ShadowSettings.setCanDrawOverlays(true)
        val startup = createStartup()
        startup.start()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(overlayRequests.isEmpty())
        assertTrue(startup.ready)
        assertEquals(1, readyCount)
        assertNull(shadowOf(activity).nextStartedActivity)
    }

    @Test fun nonManualWirelessModesDoNotTouchWifiOrRequestHotspotPermission() {
        ShadowSettings.setCanDrawOverlays(true)
        @Suppress("DEPRECATION")
        val wifi = activity.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        @Suppress("DEPRECATION")
        wifi.setWifiEnabled(true)
        AirPlayPersistence.saveRootHotspotEnabled(activity, true)
        val modes = listOf(
            true to WirelessHotspotMode.EXISTING_WIFI,
            true to WirelessHotspotMode.WIFI_P2P,
            true to WirelessHotspotMode.HOSTAPD,
        )
        modes.forEachIndexed { index, (wireless, mode) ->
            AirPlayPersistence.saveWirelessEnabled(activity, wireless)
            AirPlayPersistence.saveWirelessHotspotMode(activity, mode)
            val next = createStartup()
            next.start()
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(next.ready)
            assertEquals(index + 1, readyCount)
            assertTrue(wifi.isWifiEnabled)
            assertTrue(overlayRequests.isEmpty())
            assertNull(shadowOf(activity).nextStartedActivity)
            next.close()
        }
    }

    @Test fun unavailablePermissionPageDoesNotBlockCarPlayOrLaunchGaodeWithoutPermission() {
        installGaode()
        val startup = createStartup { throw IllegalStateException("No settings activity") }
        startup.start()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(startup.ready)
        assertNull(shadowOf(activity).nextStartedActivity)
    }

    @Test fun deniedPermissionIsNotRequestedInALoopWithinTheSameProcess() {
        createStartup().apply {
            start()
            onOverlayPermissionResult()
            close()
        }
        val next = createStartup()
        next.start()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, overlayRequests.size)
        assertTrue(next.ready)
    }

    @Test fun recreatedHostReturnsEvenWhenWarmupDeadlineHasAlreadyPassed() {
        ShadowSettings.setCanDrawOverlays(true)
        setStartupField("gaodeAttempted", true)
        setStartupField("gaodeReadyAt", SystemClock.elapsedRealtime().coerceAtLeast(1L))
        shadowOf(Looper.getMainLooper()).idleFor(3, TimeUnit.SECONDS)
        val startup = createStartup()
        startup.start()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(ComponentName(activity, CarPlayHostActivity::class.java), shadowOf(activity).nextStartedActivity.component)
        assertTrue(startup.ready)
    }

    @Test fun resumedHostDoesNotRelaunchItselfAfterWarmup() {
        ShadowSettings.setCanDrawOverlays(true)
        installGaode()
        val startup = createStartup()
        startup.start()
        assertNotNull(shadowOf(activity).nextStartedActivity)
        startup.onResume()
        shadowOf(Looper.getMainLooper()).idleFor(2, TimeUnit.SECONDS)
        assertTrue(startup.ready)
        assertNull(shadowOf(activity).nextStartedActivity)
    }

    @Test fun closeCancelsPendingWarmupReturn() {
        ShadowSettings.setCanDrawOverlays(true)
        installGaode()
        val startup = createStartup()
        startup.start()
        assertNotNull(shadowOf(activity).nextStartedActivity)
        startup.close()
        shadowOf(Looper.getMainLooper()).idleFor(3, TimeUnit.SECONDS)
        assertFalse(startup.ready)
        assertEquals(0, readyCount)
        assertNull(shadowOf(activity).nextStartedActivity)
    }

    @Test fun runningCarGaodeSkipsLaunchAndDelay() {
        installGaode()
        setRunningProcesses(ActivityManager.RunningAppProcessInfo("shared.navigation", 123, arrayOf("com.autonavi.amapauto")))
        assertRunningGaodeSkipped()
    }

    @Test fun runningPhoneGaodeSkipsEvenWhenCarVersionIsInstalled() {
        installGaode()
        setRunningProcesses(ActivityManager.RunningAppProcessInfo("com.autonavi.minimap", 123, null))
        assertRunningGaodeSkipped()
    }

    @Test fun runningGaodeSubprocessSkipsLaunchWithoutPackageList() {
        installGaode()
        setRunningProcesses(ActivityManager.RunningAppProcessInfo("com.autonavi.amapauto:service", 123, null))
        assertRunningGaodeSkipped()
    }

    @Test fun unrelatedProcessWithSimilarNameDoesNotSkipWarmup() {
        ShadowSettings.setCanDrawOverlays(true)
        installGaode()
        setRunningProcesses(ActivityManager.RunningAppProcessInfo("com.autonavi.amapauto.other", 123, null))
        createStartup().start()
        assertEquals("com.autonavi.amapauto", shadowOf(activity).nextStartedActivity.component?.packageName)
    }

    @Test fun exitedGaodeProcessDoesNotSkipWarmup() {
        ShadowSettings.setCanDrawOverlays(true)
        installGaode()
        setRunningProcesses(ActivityManager.RunningAppProcessInfo("com.autonavi.amapauto", 123, null).apply {
            importance = ActivityManager.RunningAppProcessInfo.IMPORTANCE_GONE
        })
        createStartup().start()
        assertEquals("com.autonavi.amapauto", shadowOf(activity).nextStartedActivity.component?.packageName)
    }

    @Test @Config(sdk = [23]) fun preAndroid10HeadUnitSkipsAlreadyRunningGaode() {
        ShadowSettings.setCanDrawOverlays(true)
        installGaode()
        setRunningProcesses(ActivityManager.RunningAppProcessInfo("com.autonavi.amapauto", 123, null))
        val startup = createStartup()
        startup.start()
        shadowOf(Looper.getMainLooper()).idleFor(3, TimeUnit.SECONDS)
        assertTrue(startup.ready)
        assertEquals(1, readyCount)
        assertTrue(overlayRequests.isEmpty())
        assertNull(shadowOf(activity).nextStartedActivity)
    }

    @Test fun repeatedStartDoesNotFinishEarlyOrRepeatReadyCallback() {
        ShadowSettings.setCanDrawOverlays(true)
        installGaode()
        val startup = createStartup()
        startup.start()
        assertNotNull(shadowOf(activity).nextStartedActivity)
        startup.onResume()
        shadowOf(Looper.getMainLooper()).idleFor(1, TimeUnit.SECONDS)
        startup.start()
        assertFalse(startup.ready)
        shadowOf(Looper.getMainLooper()).idleFor(1, TimeUnit.SECONDS)
        startup.start()
        shadowOf(Looper.getMainLooper()).idleFor(3, TimeUnit.SECONDS)
        assertEquals(1, readyCount)
        assertNull(shadowOf(activity).nextStartedActivity)
    }

    @Test fun completedWarmupDoesNotReplayReturnInLaterHost() {
        ShadowSettings.setCanDrawOverlays(true)
        installGaode()
        val first = createStartup()
        first.start()
        assertNotNull(shadowOf(activity).nextStartedActivity)
        shadowOf(Looper.getMainLooper()).idleFor(2, TimeUnit.SECONDS)
        assertEquals(ComponentName(activity, CarPlayHostActivity::class.java), shadowOf(activity).nextStartedActivity.component)
        first.onResume()
        first.close()

        val next = createStartup()
        next.start()
        shadowOf(Looper.getMainLooper()).idleFor(3, TimeUnit.SECONDS)
        assertTrue(next.ready)
        assertEquals(2, readyCount)
        assertNull(shadowOf(activity).nextStartedActivity)
    }

    @Test fun closedHostTransfersUnfinishedWarmupWithoutLaunchingGaodeAgain() {
        ShadowSettings.setCanDrawOverlays(true)
        installGaode()
        val first = createStartup()
        first.start()
        assertNotNull(shadowOf(activity).nextStartedActivity)
        shadowOf(Looper.getMainLooper()).idleFor(1, TimeUnit.SECONDS)
        first.close()

        val next = createStartup()
        next.start()
        shadowOf(Looper.getMainLooper()).idleFor(1, TimeUnit.SECONDS)
        assertTrue(next.ready)
        assertEquals(1, readyCount)
        assertEquals(ComponentName(activity, CarPlayHostActivity::class.java), shadowOf(activity).nextStartedActivity.component)
        assertNull(shadowOf(activity).nextStartedActivity)
    }

    @Test fun failedReturnChecksAfterOneSecondAndRetriesAfterTwoMoreSecondsAtMostThreeTimes() {
        val startup = beginGaodeReturn()
        repeat(3) {
            shadowOf(Looper.getMainLooper()).idleFor(1, TimeUnit.SECONDS)
            assertNull(shadowOf(activity).nextStartedActivity)
            shadowOf(Looper.getMainLooper()).idleFor(1_999, TimeUnit.MILLISECONDS)
            assertNull(shadowOf(activity).nextStartedActivity)
            shadowOf(Looper.getMainLooper()).idleFor(1, TimeUnit.MILLISECONDS)
            assertEquals(ComponentName(activity, CarPlayHostActivity::class.java), shadowOf(activity).nextStartedActivity.component)
            assertNull(shadowOf(activity).nextStartedActivity)
        }
        shadowOf(Looper.getMainLooper()).idleFor(30, TimeUnit.SECONDS)
        assertNull(shadowOf(activity).nextStartedActivity)
        assertTrue(startup.ready)
        assertEquals(1, readyCount)
    }

    @Test fun resumeBeforeVerificationStopsRetriesEvenAfterAnotherPause() {
        assertResumeStopsRetries(500)
    }

    @Test fun resumeDuringRetryDelayStopsRetriesEvenAfterAnotherPause() {
        assertResumeStopsRetries(1_500)
    }

    @Test fun resumeAfterFirstRetryStopsRemainingRetries() {
        assertResumeStopsRetries(3_000, expectRetry = true)
    }

    @Test fun closeDuringRetryDelayCancelsReturn() {
        val startup = beginGaodeReturn()
        shadowOf(Looper.getMainLooper()).idleFor(1, TimeUnit.SECONDS)
        startup.close()
        shadowOf(Looper.getMainLooper()).idleFor(30, TimeUnit.SECONDS)
        assertNull(shadowOf(activity).nextStartedActivity)
        assertEquals(1, readyCount)
    }

    @Test fun finishingActivityDuringRetryDelayDoesNotReturn() {
        beginGaodeReturn()
        shadowOf(Looper.getMainLooper()).idleFor(1, TimeUnit.SECONDS)
        activity.finish()
        shadowOf(Looper.getMainLooper()).idleFor(30, TimeUnit.SECONDS)
        assertNull(shadowOf(activity).nextStartedActivity)
    }

    @Test fun destroyedActivityDuringRetryDelayDoesNotReturn() {
        beginGaodeReturn()
        shadowOf(Looper.getMainLooper()).idleFor(1, TimeUnit.SECONDS)
        controller.pause().stop().destroy()
        shadowOf(Looper.getMainLooper()).idleFor(30, TimeUnit.SECONDS)
        assertNull(shadowOf(activity).nextStartedActivity)
    }

    private fun beginGaodeReturn(): CarPlayStartup {
        ShadowSettings.setCanDrawOverlays(true)
        installGaode()
        val startup = createStartup()
        startup.start()
        assertEquals("com.autonavi.amapauto", shadowOf(activity).nextStartedActivity.component?.packageName)
        startup.onResume()
        startup.onPause()
        shadowOf(Looper.getMainLooper()).idleFor(2, TimeUnit.SECONDS)
        assertEquals(ComponentName(activity, CarPlayHostActivity::class.java), shadowOf(activity).nextStartedActivity.component)
        return startup
    }

    private fun assertResumeStopsRetries(delayMillis: Long, expectRetry: Boolean = false) {
        val startup = beginGaodeReturn()
        shadowOf(Looper.getMainLooper()).idleFor(delayMillis, TimeUnit.MILLISECONDS)
        if (expectRetry) {
            assertEquals(ComponentName(activity, CarPlayHostActivity::class.java), shadowOf(activity).nextStartedActivity.component)
        }
        startup.onResume()
        startup.onPause()
        shadowOf(Looper.getMainLooper()).idleFor(30, TimeUnit.SECONDS)
        assertNull(shadowOf(activity).nextStartedActivity)
        assertEquals(1, readyCount)
    }

    private fun assertRunningGaodeSkipped() {
        ShadowSettings.setCanDrawOverlays(true)
        val startup = createStartup()
        startup.start()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(startup.ready)
        assertEquals(1, readyCount)
        assertNull(shadowOf(activity).nextStartedActivity)
        startup.onPause()
        startup.close()
        createStartup().start()
        shadowOf(Looper.getMainLooper()).idleFor(3, TimeUnit.SECONDS)
        assertEquals(2, readyCount)
        assertNull(shadowOf(activity).nextStartedActivity)
    }

    private fun setRunningProcesses(vararg processes: ActivityManager.RunningAppProcessInfo) {
        val manager = activity.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        shadowOf(manager).setProcesses(processes.toList())
    }

    private fun createStartup(requestOverlay: (Intent) -> Unit = { overlayRequests += it }): CarPlayStartup =
        CarPlayStartup(activity, { fail("Unexpected hotspot permission request") }, requestOverlay) { readyCount++ }
            .also { startup = it }

    private fun installGaode() {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_INFO).setPackage("com.autonavi.amapauto")
        shadowOf(activity.packageManager).addActivityIfNotPresent(ComponentName("com.autonavi.amapauto", "MainActivity"))
        val info = activity.packageManager.getActivityInfo(ComponentName("com.autonavi.amapauto", "MainActivity"), 0)
        shadowOf(activity.packageManager).addResolveInfoForIntent(intent, android.content.pm.ResolveInfo().apply { activityInfo = info })
    }

    private fun setStartupField(name: String, value: Any) {
        CarPlayStartup::class.java.getDeclaredField(name).apply { isAccessible = true }.set(null, value)
    }
}
