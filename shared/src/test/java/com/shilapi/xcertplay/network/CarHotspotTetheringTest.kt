package com.shilapi.xcertplay.network

import android.content.Context
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.os.ResultReceiver
import java.lang.reflect.InvocationTargetException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadow.api.Shadow
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import com.shilapi.xcertplay.network.CarHotspotTethering.Result

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class CarHotspotTetheringTest {
    @Test fun existingHotspotNeedsNoGrantOrStartCall() {
        assertEquals(Result.READY, enable(canWrite = false, state = { true }))
    }

    @Test fun missingPermissionDoesNotTouchTheHotspot() {
        assertEquals(Result.PERMISSION_REQUIRED, enable(canWrite = false))
    }

    @Test fun unreadableStateIsUnsupportedWithoutStartingBlindly() {
        assertEquals(Result.UNSUPPORTED, enable(state = { null }))
    }

    @Test fun successfulRequestStillWaitsForActualHotspotState() {
        assertEquals(Result.TIMED_OUT, enable(start = { it.send(0, null) }))
    }

    @Test fun actualEnabledStateCompletesStartup() {
        var on = false
        assertEquals(Result.READY, enable(state = { on }, start = { on = true }))
    }

    @Test fun asynchronousEnabledStateIsObserved() {
        val on = AtomicBoolean(false)
        var completion: Thread? = null
        val result = CarHotspotTethering.enable(1_000, { false }, { true }, { on.get() }) {
            completion = thread { on.set(true) }
        }
        completion!!.join(1_000)
        assertEquals(Result.READY, result)
    }

    @Test fun platformRejectionIsReported() {
        assertEquals(Result.FAILED, enable(start = { it.send(1, null) }))
    }

    @Test fun missingBinderMethodIsUnsupported() {
        assertEquals(Result.UNSUPPORTED, enable(start = { throw NoSuchMethodException() }))
    }

    @Test fun binderPermissionFailureIsReportedSeparately() {
        assertEquals(Result.PERMISSION_REQUIRED,
            enable(start = { throw InvocationTargetException(SecurityException()) }))
    }

    @Test fun otherBinderFailureIsNotReportedAsPermissionReady() {
        assertEquals(Result.FAILED,
            enable(start = { throw InvocationTargetException(IllegalStateException()) }))
    }

    @Test fun missingBinderMethodFallsBackToAdb() {
        var on = false
        val result = CarHotspotTethering.enable(
            1_000, { false }, { true }, { on },
            startFallback = { on = true; true },
            start = { throw NoSuchMethodException() },
        )
        assertEquals(Result.READY, result)
    }

    @Test fun securityExceptionFallsBackToAdb() {
        var on = false
        val result = CarHotspotTethering.enable(
            1_000, { false }, { true }, { on },
            startFallback = { on = true; true },
            start = { throw InvocationTargetException(SecurityException()) },
        )
        assertEquals(Result.READY, result)
    }

    @Test fun failedAdbFallbackReportsOriginalDiagnostic() {
        val result = CarHotspotTethering.enable(
            20, { false }, { true }, { false },
            startFallback = { false },
            start = { throw NoSuchMethodException() },
        )
        assertEquals(Result.UNSUPPORTED, result)
    }

    @Test fun cancellationDuringReflectionFailureDoesNotStartTheFallback() {
        var cancelled = false
        var fallbackCalls = 0
        val result = CarHotspotTethering.enable(1_000, { cancelled }, { true }, { false },
            startFallback = { fallbackCalls++; true },
            start = { cancelled = true; throw SecurityException() })
        assertEquals(Result.CANCELLED, result)
        assertEquals(0, fallbackCalls)
    }

    @Test fun anExpiredReflectionAttemptDoesNotGetAnotherAdbBudget() {
        var fallbackCalls = 0
        val result = CarHotspotTethering.enable(20, { false }, { true }, { false },
            startFallback = { fallbackCalls++; true },
            start = { Thread.sleep(30); throw NoSuchMethodException() })
        assertEquals(Result.TIMED_OUT, result)
        assertEquals(0, fallbackCalls)
    }

    @Test fun unknownAppStateCanUseAFallbackWhichIndependentlyObservesTheAp() {
        var observed: Boolean? = null
        val result = CarHotspotTethering.enable(1_000, { false }, { true }, { observed },
            startFallback = { observed = true; true }, start = { fail("Do not invoke reflection with unknown state") })
        assertEquals(Result.READY, result)
    }

    @Test fun acceptingAFallbackRequestDoesNotInventObservableReadiness() {
        val result = CarHotspotTethering.enable(20, { false }, { true }, { null },
            startFallback = { true }, start = { fail("Do not start blindly") })
        assertEquals(Result.TIMED_OUT, result)
    }

    @Test fun anAdbEnabledObservationCannotMaskALaterPlatformOffState() {
        val observed = AtomicReference<Boolean?>()
        val state = CarHotspotTethering.stateWithAdbObservation({ false }, observed)
        val result = CarHotspotTethering.enable(20, { false }, { true }, state,
            startFallback = { observed.set(true); true }, start = { throw NoSuchMethodException() })
        assertEquals(Result.TIMED_OUT, result)
    }

    @Test fun anAdbEnabledObservationAllowsHiddenPlatformStatusToCompleteStartup() {
        val observed = AtomicReference<Boolean?>()
        val state = CarHotspotTethering.stateWithAdbObservation({ null }, observed)
        val result = CarHotspotTethering.enable(1_000, { false }, { true }, state,
            startFallback = { observed.set(true); true }, start = { fail("Do not start blindly") })
        assertEquals(Result.READY, result)
    }

    @Test fun aFallbackExceptionPreservesTheOriginalPermissionDiagnostic() {
        val result = CarHotspotTethering.enable(1_000, { false }, { true }, { false },
            startFallback = { throw IllegalStateException("ADB unavailable") }, start = { throw SecurityException() })
        assertEquals(Result.PERMISSION_REQUIRED, result)
    }

    @Test fun cancellationWhileReadingEnabledStateCannotReportReady() {
        var cancelled = false
        assertEquals(Result.CANCELLED, enable(cancelled = { cancelled }, state = {
            cancelled = true
            true
        }))
    }

    @Test fun anExpiredStatusReadCannotReportReadyOrStartAnything() {
        val result = CarHotspotTethering.enable(20, { false }, { true }, {
            Thread.sleep(30)
            true
        }, startFallback = { fail("No fallback after expiry"); false },
            start = { fail("No reflection after expiry") })
        assertEquals(Result.TIMED_OUT, result)
    }

    @Test fun anInterruptedFallbackPreservesCancellation() {
        try {
            val result = CarHotspotTethering.enable(1_000, { false }, { true }, { null },
                startFallback = { throw InterruptedException() }, start = { fail("Do not start blindly") })
            assertEquals(Result.CANCELLED, result)
            assertTrue(Thread.currentThread().isInterrupted)
        } finally {
            Thread.interrupted()
        }
    }

    @Test fun cancelledRequestDoesNotStartTheHotspot() {
        assertEquals(Result.CANCELLED, enable(cancelled = { true }))
    }

    @Test fun cancellationDuringStartupStopsWaitingWithoutAnotherRequest() {
        var cancelled = false
        var calls = 0
        assertEquals(Result.CANCELLED, enable(cancelled = { cancelled }, start = {
            calls++
            cancelled = true
        }))
        assertEquals(1, calls)
    }

    @Test fun concurrentRequestsShareOneStartAndRecheckStateAfterWaiting() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val on = AtomicBoolean(false)
        val calls = AtomicInteger(0)
        var first: Result? = null
        var second: Result? = null
        val one = thread {
            first = CarHotspotTethering.enable(1_000, { false }, { true }, { on.get() }) {
                calls.incrementAndGet()
                entered.countDown()
                check(release.await(2, TimeUnit.SECONDS))
                on.set(true)
            }
        }
        assertTrue(entered.await(2, TimeUnit.SECONDS))
        val two = thread {
            second = CarHotspotTethering.enable(1_000, { false }, { true }, { on.get() }) {
                calls.incrementAndGet()
            }
        }
        release.countDown()
        one.join(2_000); two.join(2_000)
        assertFalse(one.isAlive); assertFalse(two.isAlive)
        assertEquals(Result.READY, first); assertEquals(Result.READY, second)
        assertEquals(1, calls.get())
    }

    private fun enable(
        canWrite: Boolean = true,
        state: () -> Boolean? = { false },
        cancelled: () -> Boolean = { false },
        start: (ResultReceiver) -> Unit = { fail("Unexpected hotspot mutation") },
    ): Result = CarHotspotTethering.enable(20, cancelled, { canWrite }, state, start)
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE,
    shadows = [CarHotspotTetheringPublicTest.WifiShadow::class, CarHotspotTetheringPublicTest.SettingsShadow::class])
class CarHotspotTetheringPublicTest {
    private lateinit var context: Context
    private lateinit var wifi: WifiShadow

    @Before fun setUp() {
        context = RuntimeEnvironment.getApplication()
        wifi = Shadow.extract(context.getSystemService(Context.WIFI_SERVICE) as WifiManager)
        SettingsShadow.allowed = true
    }

    @Test fun missingPermissionDoesNotDisableWifi() {
        SettingsShadow.allowed = false
        assertEquals(Result.PERMISSION_REQUIRED, enable())
        assertUntouched()
    }

    @Test fun existingHotspotDoesNotDisableWifiOrRequirePermission() {
        wifi.apEnabled = true
        SettingsShadow.allowed = false
        assertEquals(Result.READY, enable())
        assertUntouched()
    }

    @Test fun cancelledRequestDoesNotDisableWifi() {
        assertEquals(Result.CANCELLED, enable(cancelled = { true }))
        assertUntouched()
    }

    @Test fun expiredRequestDoesNotDisableWifi() {
        assertEquals(Result.TIMED_OUT, enable(timeoutMillis = 0))
        assertUntouched()
    }

    @Test fun interruptedRequestDoesNotDisableWifi() {
        try {
            Thread.currentThread().interrupt()
            assertEquals(Result.CANCELLED, enable())
            assertUntouched()
            assertTrue(Thread.currentThread().isInterrupted)
        } finally {
            Thread.interrupted()
        }
    }

    @Test fun cancellationDuringInitialStatusReadDoesNotDisableWifi() {
        var cancelled = false
        wifi.onStateRead = { cancelled = true }
        assertEquals(Result.CANCELLED, enable(cancelled = { cancelled }))
        assertUntouched()
    }

    @Test fun cancellationAfterFirstWifiDisableSkipsRetryAndApStart() {
        var cancelled = false
        wifi.onDisable = { cancelled = true }
        assertEquals(Result.CANCELLED, enable(cancelled = { cancelled }))
        assertEquals(1, wifi.disableCalls)
        assertEquals(0, wifi.apStartCalls)
    }

    @Test fun cancellationWhileWaitingForWifiShutdownSkipsRetryAndApStart() {
        wifi.keepWifiOn = true
        val cancelled = AtomicBoolean(false)
        val disabled = CountDownLatch(1)
        wifi.onDisable = { disabled.countDown() }
        var result: Result? = null
        val worker = thread { result = enable(cancelled = { cancelled.get() }) }
        try {
            assertTrue(disabled.await(2, TimeUnit.SECONDS))
            cancelled.set(true)
            worker.join(2_000)
            assertFalse(worker.isAlive)
            assertEquals(Result.CANCELLED, result)
            assertEquals(1, wifi.disableCalls)
            assertEquals(0, wifi.apStartCalls)
        } finally {
            cancelled.set(true)
            worker.join(2_000)
        }
    }

    @Test fun wifiShutdownUsesTheOriginalDeadlineRatherThanItsOwnTwoSecondBudget() {
        wifi.keepWifiOn = true
        val started = System.nanoTime()
        assertEquals(Result.TIMED_OUT, enable(timeoutMillis = 150))
        val elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
        assertTrue("Wi-Fi shutdown exceeded the shared deadline: $elapsedMillis ms", elapsedMillis < 1_000)
        assertEquals(1, wifi.disableCalls)
        assertEquals(0, wifi.apStartCalls)
    }

    @Test fun cancelledLockWaitDoesNotDisableWifi() = withStartupLockHeld {
        val cancelled = AtomicBoolean(false)
        val checked = CountDownLatch(1)
        var result: Result? = null
        val waiter = thread {
            result = enable(cancelled = { checked.countDown(); cancelled.get() })
        }
        try {
            assertTrue(checked.await(2, TimeUnit.SECONDS))
            cancelled.set(true)
            waiter.join(2_000)
            assertFalse(waiter.isAlive)
            assertEquals(Result.CANCELLED, result)
            assertUntouched()
        } finally {
            cancelled.set(true)
            waiter.join(2_000)
        }
    }

    @Test fun expiredLockWaitDoesNotDisableWifi() = withStartupLockHeld {
        assertEquals(Result.TIMED_OUT, enable(timeoutMillis = 50))
        assertUntouched()
    }

    @Test @Config(sdk = [23, 25])
    fun preOStartsSavedApDirectlyAfterDisablingWifi() {
        assertEquals(Result.READY, enable())
        assertEquals(1, wifi.disableCalls)
        assertEquals(1, wifi.apStartCalls)
        assertNull(wifi.apConfig)
    }

    @Test @Config(sdk = [23])
    fun spoofedModernSdkOnAndroid42UsesTheLegacyApPathWithoutPermission() {
        org.robolectric.shadows.ShadowBuild.setVersionRelease("4.2.2")
        SettingsShadow.allowed = false
        assertEquals(Result.READY, enable())
        assertEquals(1, wifi.disableCalls)
        assertEquals(1, wifi.apStartCalls)
    }

    @Test @Config(sdk = [23])
    fun issue172RetriesWifiShutdownBeforeStartingSavedAp() {
        wifi.keepWifiOn = true
        assertEquals(Result.READY, enable(timeoutMillis = 4_000))
        assertEquals(2, wifi.disableCalls)
        assertEquals(1, wifi.apStartCalls)
    }

    private fun enable(timeoutMillis: Long = 1_000, cancelled: () -> Boolean = { false }): Result =
        CarHotspotTethering.enable(context, cancelled, timeoutMillis) { }

    private fun assertUntouched() {
        assertEquals(0, wifi.disableCalls)
        assertEquals(0, wifi.apStartCalls)
        assertTrue(wifi.stationEnabled)
    }

    private fun withStartupLockHeld(test: () -> Unit) {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        var result: Result? = null
        val holder = thread {
            var on = false
            result = CarHotspotTethering.enable(5_000, { false }, { true }, { on }) {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                on = true
            }
        }
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            test()
        } finally {
            release.countDown()
            holder.join(2_000)
        }
        assertFalse(holder.isAlive)
        assertEquals(Result.READY, result)
    }

    @Implements(WifiManager::class)
    class WifiShadow {
        var stationEnabled = true
        var apEnabled = false
        var keepWifiOn = false
        var disableCalls = 0
        var apStartCalls = 0
        var apConfig: WifiConfiguration? = null
        var onDisable: () -> Unit = { }
        var onStateRead: () -> Unit = { }

        @Implementation fun isWifiEnabled(): Boolean = stationEnabled
        @Implementation fun getWifiApState(): Int {
            onStateRead()
            return if (apEnabled) 13 else 11
        }
        @Implementation fun setWifiEnabled(enabled: Boolean): Boolean {
            check(!enabled)
            disableCalls++
            if (!keepWifiOn) stationEnabled = false
            onDisable()
            return true
        }
        @Implementation fun setWifiApEnabled(config: WifiConfiguration?, enabled: Boolean): Boolean {
            check(disableCalls > 0)
            apStartCalls++
            apConfig = config
            apEnabled = enabled
            return true
        }
    }

    @Implements(android.provider.Settings.System::class)
    class SettingsShadow {
        companion object {
            var allowed = true
            @JvmStatic @Implementation fun canWrite(context: Context): Boolean = allowed
        }
    }
}
