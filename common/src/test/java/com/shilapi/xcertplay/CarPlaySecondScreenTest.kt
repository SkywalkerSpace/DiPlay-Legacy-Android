package com.shilapi.xcertplay

import android.app.Activity
import android.app.Presentation
import android.content.Context
import android.hardware.display.DisplayManager
import com.shilapi.xcertplay.media.AndroidMediaSink
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDisplayManager

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class CarPlaySecondScreenTest {
    private class Fixture : AutoCloseable {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()
        val id = ShadowDisplayManager.addDisplay("w1920dp-h720dp-mdpi", 5)
        val display = (activity.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager).getDisplay(id)
        val sink = AndroidMediaSink()
        var starts = 0
        val mirror = CarPlaySecondScreen(activity) { starts++ }
        val preferences = activity.getSharedPreferences("carplay_second_screen", Context.MODE_PRIVATE)

        override fun close() {
            mirror.stop()
            sink.close()
            ShadowDisplayManager.removeDisplay(id)
            controller.pause().stop().destroy()
        }
    }

    @Test fun singleDisplayWaitsForMainPictureThenSavesTargetAndStops() {
        Fixture().use { f ->
            f.mirror.bind(f.sink, 1280, 720)
            assertFalse(f.mirror.active)
            assertEquals(0, f.starts)
            f.mirror.onVideoReady()
            assertTrue(f.mirror.active)
            assertEquals(1, f.starts)
            assertEquals(f.id, f.preferences.getInt("displayId", -1))
            assertEquals(f.display.name, f.preferences.getString("displayName", null))
            f.mirror.stop()
            assertFalse(f.mirror.active)
        }
    }

    @Test fun missingSavedTargetDoesNotSwitchToAnotherDisplay() {
        Fixture().use { f ->
            f.preferences.edit().putInt("displayId", 999).putString("displayName", "Missing cluster").apply()
            f.mirror.bind(f.sink, 1280, 720)
            f.mirror.onVideoReady()
            assertFalse(f.mirror.active)
            assertEquals(0, f.starts)
            assertEquals(999, f.preferences.getInt("displayId", -1))
        }
    }

    @Test fun changedIdRestoresOnlyMatchingSavedDisplayName() {
        Fixture().use { f ->
            f.preferences.edit().putInt("displayId", 999).putString("displayName", f.display.name).apply()
            f.mirror.bind(f.sink, 1280, 720)
            f.mirror.onVideoReady()
            assertTrue(f.mirror.active)
            assertEquals(f.id, f.preferences.getInt("displayId", -1))
        }
    }

    @Test fun mirrorUsesOnlyDefaultPresentationWindowTypeWithoutOverlayPermission() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()
        val id = ShadowDisplayManager.addDisplay("w1920dp-h720dp-mdpi", 5)
        val display = (activity.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager).getDisplay(id)
        org.robolectric.shadows.ShadowSettings.setCanDrawOverlays(false)
        val defaultType = Presentation(activity, display).window!!.attributes.type
        val presentation = CarPlayMirrorPresentation(activity, display, 1280, 720) {}
        try {
            presentation.show()
            assertTrue(presentation.isShowing)
            assertEquals(defaultType, presentation.window!!.attributes.type)
            assertNotEquals(2002, presentation.window!!.attributes.type)
            assertNotEquals(2003, presentation.window!!.attributes.type)
        } finally {
            presentation.dismiss()
            ShadowDisplayManager.removeDisplay(id)
            controller.pause().stop().destroy()
        }
    }
}
