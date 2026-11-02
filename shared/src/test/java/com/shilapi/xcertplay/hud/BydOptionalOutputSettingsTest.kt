package com.shilapi.xcertplay.hud

import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowBuild

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class BydOptionalOutputSettingsTest {
    @Test fun optionalOutputsDefaultOffAndKeepExplicitPreviousSelections() {
        val app = RuntimeEnvironment.getApplication()
        val prefs = app.getSharedPreferences("diplay_byd_outputs", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        assertFalse(BydOutputSettings.hudSong(app))
        assertEquals(BydOemClusterHold.OFF, BydOutputSettings.oemClusterHold(app))
        prefs.edit().putBoolean("oem_cluster_freeze", true).commit()
        assertEquals(BydOemClusterHold.PACKAGE, BydOutputSettings.oemClusterHold(app))
        BydOutputSettings.setOemClusterHold(app, BydOemClusterHold.COMPONENT)
        assertEquals(BydOemClusterHold.COMPONENT, BydOutputSettings.oemClusterHold(app))
    }

    @Test fun installedStockReceiverDoesNotEnableUnverifiedDilink4Hud() {
        val app = RuntimeEnvironment.getApplication()
        ShadowBuild.setFingerprint("BYD/DiLink4:10/unverified")
        val info = PackageInfo().apply {
            packageName = "com.byd.clusterdebug"
            applicationInfo = ApplicationInfo().apply {
                packageName = "com.byd.clusterdebug"
                flags = ApplicationInfo.FLAG_SYSTEM
            }
        }
        shadowOf(app.packageManager).installPackage(info)
        for (pkg in listOf("com.shihab.diplay", "com.shihab.diplay.duo")) {
            val knownApp = object : ContextWrapper(app) {
                override fun getPackageName(): String = pkg
            }
            assertFalse(BydStandaloneHudOutput.available(knownApp))
            assertTrue(BydStandaloneHudOutput.diagnostics(knownApp).contains("standaloneHudAvailable=false"))
        }
    }

    @Test fun starterBridgeAllowsDuoAndExistingTestPackagesButStillValidatesTokens() {
        val app = RuntimeEnvironment.getApplication()
        for (pkg in listOf(
            "com.andrerinas.headunitrevived.bydhudtest",
            "com.shihab.diplay.hudtest",
            "com.shihab.diplay.duo",
        )) {
            val context = object : ContextWrapper(app) {
                override fun getPackageName(): String = pkg
            }
            // Invalid credentials fail before initialization, so no bridge worker is started.
            val error = runCatching { BydStarterBridge.configure(context, "invalid") }.exceptionOrNull()
            assertTrue("$pkg must pass the package guard", error is IllegalArgumentException)
        }
        val unrelatedApp = object : ContextWrapper(app) {
            override fun getPackageName(): String = "com.example.unrelated"
        }
        val error = runCatching { BydStarterBridge.configure(unrelatedApp, "invalid") }.exceptionOrNull()
        assertTrue("Unlisted packages must remain rejected", error is IllegalStateException)
    }

}
