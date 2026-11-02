package com.shilapi.xcertplay

import android.app.Activity
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.network.CarHotspotTethering
import com.shilapi.xcertplay.orchestration.WirelessHotspotMode
import java.util.concurrent.Executors

/** Prepares the car's display route before CarPlay takes the foreground. No capture permission is needed. */
internal class CarPlayStartup(
    private val activity: Activity,
    private val requestWriteSettings: (Intent) -> Unit,
    private val requestOverlayPermission: (Intent) -> Unit,
    private val onReady: () -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "diplay-car-startup").apply { isDaemon = true } }
    @Volatile private var closed = false
    private var started = false
    private var permissionPending = false
    private var overlayPermissionPending = false
    private var resumed = false
    private var returningFromGaode = false
    private var gaodeReturnAttempts = 0
    var ready = false
        private set

    fun start() {
        if (closed || started) return
        started = true
        if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(activity) && !overlayPermissionAsked) {
            overlayPermissionAsked = true
            overlayPermissionPending = true
            val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${activity.packageName}"))
            if (runCatching { requestOverlayPermission(intent) }
                    .onFailure { Log.w(TAG, "Cannot open overlay permission page", it) }.isSuccess) return
            overlayPermissionPending = false
        }
        prepareHotspot()
    }

    fun onOverlayPermissionResult() {
        if (closed || !overlayPermissionPending) return
        overlayPermissionPending = false
        Log.i(TAG, "Overlay permission granted=${Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(activity)}")
        prepareHotspot()
    }

    fun onResume() {
        resumed = true
        if (returningFromGaode) {
            returningFromGaode = false
            Log.i(TAG, "CarPlay task resumed after Gaode warmup")
        }
    }

    fun onPause() {
        resumed = false
    }

    private fun prepareHotspot() {
        // Existing-Wi-Fi is a client connection: starting an AP here could drop the iPhone's link.
        val mode = AirPlayPersistence.loadWirelessHotspotMode(activity)
        if (AirPlayPersistence.loadWirelessEnabled(activity) && mode != WirelessHotspotMode.MANUAL) {
            Log.i(TAG, "Leaving hotspot management to the selected wireless mode: $mode")
            warmGaode()
            return
        }
        if (!CarHotspotTethering.permitted(activity) && !permissionAsked && Build.VERSION.SDK_INT >= 23) {
            permissionAsked = true
            permissionPending = true
            val intent = Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:${activity.packageName}"))
            if (runCatching { requestWriteSettings(intent) }.isSuccess) return
            permissionPending = false
            Log.w(TAG, "No system page for hotspot control permission")
        }
        enableHotspot()
    }

    fun onPermissionResult() {
        if (closed || !permissionPending) return
        permissionPending = false
        enableHotspot()
    }

    private fun enableHotspot() {
        val app = activity.applicationContext
        worker.execute {
            val result = runCatching {
                CarHotspotTethering.enable(app, isCancelled = { closed }, timeoutMillis = 12_000L,
                    log = { Log.i(TAG, it) })
            }.onFailure { Log.w(TAG, "Hotspot startup failed", it) }.getOrDefault(CarHotspotTethering.Result.FAILED)
            main.post {
                if (closed) return@post
                val message = when (result) {
                    CarHotspotTethering.Result.READY, CarHotspotTethering.Result.CANCELLED -> null
                    CarHotspotTethering.Result.PERMISSION_REQUIRED -> R.string.hotspot_control_missing
                    CarHotspotTethering.Result.UNSUPPORTED -> R.string.hotspot_control_unsupported
                    CarHotspotTethering.Result.TIMED_OUT -> R.string.hotspot_control_timeout
                    CarHotspotTethering.Result.FAILED -> R.string.hotspot_control_failed
                }
                message?.let { Toast.makeText(app, it, Toast.LENGTH_LONG).show() }
                // A firmware limitation must not prevent wired CarPlay from working.
                warmGaode()
            }
        }
    }

    private fun warmGaode() {
        if (closed || ready) return
        if (!gaodeAttempted) {
            gaodeAttempted = true
            val runningPackage = runningGaodePackage()
            if (runningPackage != null) {
                Log.i(TAG, "Gaode already running: $runningPackage; skipping display-route warmup")
                ready = true
                onReady()
                return
            }
            // Without this exemption Android 10+ may let Gaode open but silently block our return.
            val canReturnFromBackground = Build.VERSION.SDK_INT < 29 || Settings.canDrawOverlays(activity)
            if (!canReturnFromBackground) {
                Log.w(TAG, "Skipping Gaode warmup: overlay permission is required for automatic background return")
            }
            for (name in if (canReturnFromBackground) GAODE_PACKAGES else emptyList()) {
                val intent = activity.packageManager.getLaunchIntentForPackage(name) ?: continue
                if (runCatching { activity.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                        .onFailure { Log.w(TAG, "Cannot launch $name", it) }.isSuccess) {
                    gaodeReadyAt = SystemClock.elapsedRealtime() + 2_000L
                    Log.i(TAG, "Gaode started: $name; waiting 2 seconds for the car display route")
                    break
                }
            }
            if (gaodeReadyAt == 0L) Log.w(TAG, "Gaode is not available; continuing without display-route warmup")
        }
        val remaining = (gaodeReadyAt - SystemClock.elapsedRealtime()).coerceAtLeast(0L)
        main.postDelayed({
            if (closed || ready || activity.isFinishing || activity.isDestroyed) return@postDelayed
            ready = true
            // Consume this launch's return before foregrounding: later hosts must not replay it.
            val needsReturn = gaodeReadyAt != 0L
            gaodeReadyAt = 0L
            if (needsReturn && !resumed) returnToCarPlay()
            onReady()
        }, remaining)
    }

    private fun runningGaodePackage(): String? = runCatching {
        val manager = activity.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        // Some firmware hides other apps' processes. An empty list is not proof that Gaode stopped.
        val processes = manager.runningAppProcesses.orEmpty()
        GAODE_PACKAGES.firstOrNull { name ->
            processes.any { process ->
                process.importance != ActivityManager.RunningAppProcessInfo.IMPORTANCE_GONE &&
                    (process.pkgList?.contains(name) == true || process.processName == name ||
                        process.processName?.startsWith("$name:") == true)
            }
        }
    }.onFailure { Log.w(TAG, "Cannot inspect Gaode processes; using one-time launch fallback", it) }.getOrNull()

    private fun returnToCarPlay() {
        if (closed || resumed || activity.isFinishing || activity.isDestroyed) return
        returningFromGaode = true
        gaodeReturnAttempts++
        Log.i(TAG, "Returning to CarPlay after Gaode warmup; attempt=$gaodeReturnAttempts/${MAX_GAODE_RETURN_RETRIES + 1}")
        if (Build.VERSION.SDK_INT >= 21) {
            runCatching {
                val manager = activity.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
                @Suppress("DEPRECATION")
                val task = manager.appTasks.firstOrNull { it.taskInfo?.id == activity.taskId }
                task?.moveToFront()
            }.onFailure { Log.w(TAG, "Cannot move the existing CarPlay task to front", it) }
        }
        runCatching {
            activity.startActivity(Intent(activity, CarPlayHostActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        }.onFailure { Log.w(TAG, "Cannot return to CarPlay automatically; open DiPlayDuo to continue", it) }
        // Background-start denial often does not throw; only onResume confirms that we returned.
        main.postDelayed({
            if (closed || !returningFromGaode || resumed || activity.isFinishing || activity.isDestroyed) return@postDelayed
            if (gaodeReturnAttempts <= MAX_GAODE_RETURN_RETRIES) {
                Log.w(TAG, "CarPlay task did not resume; retrying in 2 seconds after attempt=$gaodeReturnAttempts")
                main.postDelayed({
                    if (returningFromGaode) returnToCarPlay()
                }, 2_000L)
            } else {
                returningFromGaode = false
                Log.w(TAG, "CarPlay task did not resume after $gaodeReturnAttempts attempts; check overlay/background launch permissions and open DiPlayDuo manually")
            }
        }, 1_000L)
    }

    fun close() {
        closed = true
        main.removeCallbacksAndMessages(null)
        worker.shutdownNow()
    }

    private companion object {
        const val TAG = "DiPlay-Startup"
        const val MAX_GAODE_RETURN_RETRIES = 3
        val GAODE_PACKAGES = listOf("com.autonavi.amapauto", "com.autonavi.minimap")
        var permissionAsked = false
        var overlayPermissionAsked = false
        var gaodeAttempted = false
        var gaodeReadyAt = 0L
    }
}
