package com.shilapi.xcertplay.orchestration

import android.os.Looper
import com.shilapi.xcertplay.airplay.*
import com.shilapi.xcertplay.transport.Iap2IdentificationConfig
import java.net.Socket
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
class CarPlayControllerMainPictureTest {
    private class Fixture : AutoCloseable {
        init {
            // This fixture has no vendor service; Robolectric otherwise invents a null binding.
            shadowOf(RuntimeEnvironment.getApplication())
                .declareActionUnbindable("com.ts.car.someip.SomeIpServerService")
        }
        val config = AirPlayConfig("test", "test", "", "1", AirPlayDisplayConfig(800, 480))
        val events = mutableListOf<String>()
        fun listener(name: String) = object : AirPlaySessionListener {
            override fun onSessionActive(session: AirPlaySession) { events += "$name:active" }
            override fun onVideoFrameRendered(session: AirPlaySession) { events += "$name:picture" }
        }
        val controller = CarPlayController(RuntimeEnvironment.getApplication(),
            CarPlayRuntimeConfig(mfiTarget = MfiTarget.LOCAL, identification = Iap2IdentificationConfig(
                name = "test", modelIdentifier = "test", manufacturer = "test", serialNumber = "test",
                firmwareVersion = "1", hardwareVersion = "1", carPlayUsbInterfaceNumber = 3)),
            config, AirPlayIdentity(ByteArray(32), ByteArray(32), "test"), PairingStore(),
            listener("initial"), object : AirPlayMediaHandler {}, {})
        val input = controller.javaClass.getDeclaredField("sessionListener").apply { isAccessible = true }
            .get(controller) as AirPlaySessionListener
        private val phones = mutableListOf<AirPlaySession>()
        fun phone() = AirPlaySession(Socket(), config,
            AirPlayIdentity(ByteArray(32), ByteArray(32), "test"), PairingStore(), null,
            object : AirPlaySessionListener {}, object : AirPlayMediaHandler {}).also(phones::add)
        override fun close() {
            controller.close()
            controller.awaitClosed(2_000)
            phones.forEach { it.close() }
        }
    }

    @Test fun firstMainPictureIsRetainedOncePerSessionAndReplayedAfterUiReattach() {
        Fixture().use { f ->
            val phone = f.phone()
            assertFalse(f.controller.hasRenderedMainPicture())
            f.input.onSessionActive(phone)
            assertFalse(f.controller.hasRenderedMainPicture())
            f.input.onVideoFrameRendered(phone)
            f.input.onVideoFrameRendered(phone)
            assertTrue(f.controller.hasRenderedMainPicture())
            assertEquals(listOf("initial:active", "initial:picture"), f.events)
            f.controller.attachUi(f.listener("next")) {}
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(listOf("initial:active", "initial:picture", "next:active", "next:picture"), f.events)
            // Repeated activation of the same session does not lose historical picture evidence.
            f.input.onSessionActive(phone)
            assertTrue(f.controller.hasRenderedMainPicture())
            f.controller.close()
            assertFalse(f.controller.hasRenderedMainPicture())
        }
    }

    @Test fun replacedSessionAndQueuedOldUiCannotRestoreStalePictureEvidence() {
        Fixture().use { f ->
            val old = f.phone()
            val next = f.phone()
            f.input.onSessionActive(old)
            f.input.onVideoFrameRendered(old)
            f.controller.attachUi(f.listener("discarded")) {}
            f.input.onSessionActive(next)
            f.input.onSessionEnded(old)
            f.input.onVideoFrameRendered(old)
            assertFalse(f.controller.hasRenderedMainPicture())
            f.controller.attachUi(f.listener("current")) {}
            f.events.clear()
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(listOf("current:active"), f.events)
            f.input.onVideoFrameRendered(next)
            assertTrue(f.controller.hasRenderedMainPicture())
            f.input.onSessionEnded(next)
            assertFalse(f.controller.hasRenderedMainPicture())
        }
    }
}
