package com.shilapi.xcertplay

import android.app.Activity
import android.app.Presentation
import android.content.Context
import android.hardware.display.DisplayManager
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
