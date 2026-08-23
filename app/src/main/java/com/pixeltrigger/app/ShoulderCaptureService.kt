package com.pixeltrigger.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.PixelFormat
import android.graphics.Rect
import android.media.Image
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import com.pixeltrigger.app.engine.DetectionEngine
import com.pixeltrigger.app.engine.PixelSampler
import com.pixeltrigger.app.input.ShoulderShizukuEngine
import com.pixeltrigger.app.ui.ShoulderSensorOverlayView
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * R/L controller for PixelTrigger V5.
 * It owns no MediaProjection and no floating menu. ScreenCaptureService is the
 * single capture/UI host and sends each frame here before its own V4 processing.
 * Monitoring is the exact V4 DetectionEngine + PixelSampler path; only FIRE differs.
 */
class ShoulderCaptureService : Service() {
    private enum class Side { R, L }

    private lateinit var windowManager: WindowManager
    private lateinit var prefs: SharedPreferences
    private lateinit var shoulderInput: ShoulderShizukuEngine
    private val mainHandler = Handler(android.os.Looper.getMainLooper())

    private var screenWidth = 0
    private var screenHeight = 0
    private var densityDpi = 0
    private var visibleDiameter = 1
    private var touchSize = 1

    private val rViews = arrayOfNulls<ShoulderSensorOverlayView>(MONITORS_PER_SIDE)
    private val lViews = arrayOfNulls<ShoulderSensorOverlayView>(MONITORS_PER_SIDE)
    private val rParams = arrayOfNulls<WindowManager.LayoutParams>(MONITORS_PER_SIDE)
    private val lParams = arrayOfNulls<WindowManager.LayoutParams>(MONITORS_PER_SIDE)
    private val rDetectors = Array(MONITORS_PER_SIDE) { DetectionEngine() }
    private val lDetectors = Array(MONITORS_PER_SIDE) { DetectionEngine() }

    @Volatile private var editSide: Side? = null
    private var lastInputReady = false
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        syncFromV4Prefs()
        shoulderInput = ShoulderShizukuEngine(this)
        shoulderInput.connect()
        activeInstance = this
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
        wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:ShoulderController")
            .apply { acquire() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startSharedMode()
            ACTION_EDIT_R -> enterEdit(Side.R)
            ACTION_EDIT_L -> enterEdit(Side.L)
            ACTION_DONE_EDIT -> leaveEdit()
            ACTION_SYNC_CONFIG -> syncFromV4Prefs()
            ACTION_STOP -> stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startSharedMode() {
        if (rViews[0] != null) return
        val bounds = if (Build.VERSION.SDK_INT >= 30) windowManager.currentWindowMetrics.bounds
        else @Suppress("DEPRECATION") Rect(0, 0, resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels)
        screenWidth = bounds.width().coerceAtLeast(1)
        screenHeight = bounds.height().coerceAtLeast(1)
        densityDpi = resources.displayMetrics.densityDpi
        createOverlays()
        lastInputReady = shoulderInput.isReady()
        refreshStatusViews()
    }

    private fun syncFromV4Prefs() {
        val v4 = getSharedPreferences("pixeltrigger_prefs", MODE_PRIVATE)
        val white = v4.getBoolean("white_rearm_enabled", true)
        val delay = v4.getBoolean("rearm_delay_enabled", false)
        val seconds = v4.getInt("rearm_seconds", 10).coerceIn(5, 60)
        (rDetectors.asList() + lDetectors.asList()).forEach {
            it.whiteRearmEnabled = white
            it.rearmDelayEnabled = delay
            it.rearmSeconds = seconds
        }
    }

    private fun consumeSharedFrame(image: Image, sourceScreenWidth: Int, sourceScreenHeight: Int) {
        if (rViews[0] == null) return
        if (sourceScreenWidth > 0) screenWidth = sourceScreenWidth
        if (sourceScreenHeight > 0) screenHeight = sourceScreenHeight
        processImage(image)
    }

    private fun processImage(image: Image) {
        if (editSide != null || screenWidth <= 0 || screenHeight <= 0) return
        val crop = image.cropRect
        if (crop.width() <= 0 || crop.height() <= 0) return

        val ready = shoulderInput.isReady()
        if (ready != lastInputReady) {
            lastInputReady = ready
            mainHandler.post { refreshStatusViews() }
        }

        val now = SystemClock.elapsedRealtime()
        processSide(Side.R, image, crop, rParams, rDetectors, ready, now)
        processSide(Side.L, image, crop, lParams, lDetectors, ready, now)
    }

    private fun processSide(
        side: Side,
        image: Image,
        crop: Rect,
        params: Array<WindowManager.LayoutParams?>,
        detectors: Array<DetectionEngine>,
        inputReady: Boolean,
        nowMs: Long,
    ) {
        var local = 0
        var statusChanged = false
        while (local < MONITORS_PER_SIDE) {
            val lp = params[local]
            if (lp != null) {
                val sample = sample(image, crop, lp)
                if (sample != null) {
                    when (detectors[local].processSample(sample, nowMs)) {
                        is DetectionEngine.Event.Armed,
                        is DetectionEngine.Event.Rearmed,
                        is DetectionEngine.Event.ManualRearmed -> statusChanged = true

                        is DetectionEngine.Event.Fired -> {
                            var sibling = 0
                            while (sibling < MONITORS_PER_SIDE) {
                                if (sibling != local) detectors[sibling].synchronizeAfterExternalFire(nowMs)
                                sibling++
                            }
                            if (inputReady) {
                                if (side == Side.R) shoulderInput.fireR(pressDurationMs(Side.R))
                                else shoulderInput.fireL(pressDurationMs(Side.L))
                            }
                            mainHandler.post {
                                setSideStatus(
                                    side,
                                    if (inputReady) ShoulderSensorOverlayView.Status.FIRED
                                    else ShoulderSensorOverlayView.Status.INPUT_NOT_READY,
                                )
                            }
                            return
                        }

                        else -> Unit
                    }
                }
            }
            local++
        }
        if (statusChanged) mainHandler.post { refreshSideStatus(side, inputReady) }
    }

    private fun sample(image: Image, crop: Rect, lp: WindowManager.LayoutParams) = run {
        val screenCenterX = lp.x + touchSize / 2
        val screenCenterY = lp.y + touchSize / 2
        val centerX = (crop.left + screenCenterX * crop.width().toFloat() / screenWidth).roundToInt()
            .coerceIn(crop.left, crop.right - 1)
        val centerY = (crop.top + screenCenterY * crop.height().toFloat() / screenHeight).roundToInt()
            .coerceIn(crop.top, crop.bottom - 1)
        val screenRadius = visibleDiameter / 2f
        PixelSampler.sampleCircularRegion(
            image,
            centerX,
            centerY,
            max(0.5f, crop.width() * screenRadius / screenWidth),
            max(0.5f, crop.height() * screenRadius / screenHeight),
        )
    }

    private fun pressDurationMs(side: Side): Int {
        val prefix = if (side == Side.R) "r" else "l"
        if (!prefs.getBoolean("shoulder_${prefix}_hold", false)) return 0
        return prefs.getInt("shoulder_${prefix}_seconds", 1).coerceIn(1, 5) * 1000
    }

    private fun createOverlays() {
        visibleDiameter = max(mmToPx(MONITOR_DIAMETER_MM), 1)
        touchSize = max(dp(48), visibleDiameter + dp(30))
        createSideCircles(Side.R, rViews, rParams)
        createSideCircles(Side.L, lViews, lParams)
        updateCircleTouchability()
        refreshStatusViews()
    }

    private fun createSideCircles(
        side: Side,
        views: Array<ShoulderSensorOverlayView?>,
        params: Array<WindowManager.LayoutParams?>,
    ) {
        var i = 0
        while (i < MONITORS_PER_SIDE) {
            val view = ShoulderSensorOverlayView(this, visibleDiameter, side.name + (i + 1))
            val lp = overlayParams(touchSize, touchSize).apply {
                x = normalizedPosition(side, i, "x", defaultX(side, i), screenWidth)
                y = normalizedPosition(side, i, "y", screenHeight / 2 - touchSize / 2 + (i - 1) * dp(44), screenHeight)
                flags = baseFlags() or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            }
            clamp(lp, touchSize, touchSize)
            views[i] = view
            params[i] = lp
            windowManager.addView(view, lp)
            attachDrag(view, lp, side, i)
            i++
        }
    }

    private fun defaultX(side: Side, index: Int): Int {
        val center = screenWidth / 2 - touchSize / 2
        val sideOffset = if (side == Side.R) -dp(115) else dp(115)
        return center + sideOffset + (index - 1) * dp(34)
    }

    private fun normalizedPosition(side: Side, index: Int, axis: String, fallback: Int, dimension: Int): Int {
        val key = positionKey(side, index, axis)
        if (!prefs.contains(key)) return fallback
        return (prefs.getFloat(key, 0f).coerceIn(0f, 1f) * dimension).roundToInt()
    }

    private fun savePosition(side: Side, index: Int, lp: WindowManager.LayoutParams) {
        prefs.edit()
            .putFloat(positionKey(side, index, "x"), lp.x.toFloat() / screenWidth.toFloat())
            .putFloat(positionKey(side, index, "y"), lp.y.toFloat() / screenHeight.toFloat())
            .apply()
    }

    private fun positionKey(side: Side, index: Int, axis: String): String =
        "shoulder_${side.name.lowercase()}_${index}_${axis}n"

    private fun attachDrag(view: View, lp: WindowManager.LayoutParams, side: Side, index: Int) {
        var downRawX = 0f
        var downRawY = 0f
        var startX = 0
        var startY = 0
        var framePending = false

        fun updateNextFrame() {
            if (framePending) return
            framePending = true
            view.postOnAnimation {
                framePending = false
                runCatching { windowManager.updateViewLayout(view, lp) }
            }
        }

        view.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX
                    downRawY = event.rawY
                    startX = lp.x
                    startY = lp.y
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    lp.x = startX + (event.rawX - downRawX).roundToInt()
                    lp.y = startY + (event.rawY - downRawY).roundToInt()
                    clamp(lp, touchSize, touchSize)
                    updateNextFrame()
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    clamp(lp, touchSize, touchSize)
                    runCatching { windowManager.updateViewLayout(view, lp) }
                    savePosition(side, index, lp)
                    true
                }
                else -> false
            }
        }
    }

    private fun enterEdit(side: Side) {
        if (rViews[0] == null) return
        editSide = side
        resetDetectors()
        updateCircleTouchability()
        refreshStatusViews()
    }

    private fun leaveEdit() {
        editSide = null
        resetDetectors()
        updateCircleTouchability()
        refreshStatusViews()
    }

    private fun resetDetectors() {
        rDetectors.forEach { it.resetForSensorMove() }
        lDetectors.forEach { it.resetForSensorMove() }
    }

    private fun updateCircleTouchability() {
        updateSideTouchability(Side.R, rViews, rParams)
        updateSideTouchability(Side.L, lViews, lParams)
    }

    private fun updateSideTouchability(
        side: Side,
        views: Array<ShoulderSensorOverlayView?>,
        params: Array<WindowManager.LayoutParams?>,
    ) {
        var i = 0
        while (i < MONITORS_PER_SIDE) {
            val view = views[i]
            val lp = params[i]
            if (view != null && lp != null) {
                lp.flags = if (editSide == side) baseFlags()
                else baseFlags() or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                runCatching { windowManager.updateViewLayout(view, lp) }
            }
            i++
        }
    }

    private fun refreshStatusViews() {
        val ready = shoulderInput.isReady()
        refreshSideStatus(Side.R, ready)
        refreshSideStatus(Side.L, ready)
    }

    private fun refreshSideStatus(side: Side, inputReady: Boolean) {
        if (!inputReady) {
            setSideStatus(side, ShoulderSensorOverlayView.Status.INPUT_NOT_READY)
            return
        }
        val detectors = if (side == Side.R) rDetectors else lDetectors
        val status = when {
            detectors.any { it.state == DetectionEngine.State.ARMED } -> ShoulderSensorOverlayView.Status.ARMED
            detectors.any { it.state == DetectionEngine.State.WAITING_REARM } -> ShoulderSensorOverlayView.Status.FIRED
            else -> ShoulderSensorOverlayView.Status.WAITING
        }
        setSideStatus(side, status)
    }

    private fun setSideStatus(side: Side, status: ShoulderSensorOverlayView.Status) {
        val views = if (side == Side.R) rViews else lViews
        views.forEach { it?.setStatus(status) }
    }

    private fun summary(): String {
        val r = if (!prefs.getBoolean("shoulder_r_hold", false)) "Flash"
        else "${prefs.getInt("shoulder_r_seconds", 1).coerceIn(1, 5)}s"
        val l = if (!prefs.getBoolean("shoulder_l_hold", false)) "Flash"
        else "${prefs.getInt("shoulder_l_seconds", 1).coerceIn(1, 5)}s"
        val ready = if (shoulderInput.isReady()) "READY" else "WAIT"
        val edit = editSide?.name ?: "OFF"
        return "R: $r   L: $l   •   $ready   •   Edit: $edit"
    }

    private fun overlayParams(width: Int, height: Int) = WindowManager.LayoutParams(
        width,
        height,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        baseFlags(),
        PixelFormat.TRANSLUCENT,
    ).apply { gravity = Gravity.TOP or Gravity.START }

    private fun baseFlags(): Int =
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN

    private fun clamp(lp: WindowManager.LayoutParams, width: Int, height: Int) {
        lp.x = lp.x.coerceIn(0, max(screenWidth - width, 0))
        lp.y = lp.y.coerceIn(0, max(screenHeight - height, 0))
    }

    private fun mmToPx(mm: Float): Int = (mm * densityDpi / 25.4f).roundToInt().coerceAtLeast(1)
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "PixelTrigger Shoulder", NotificationManager.IMPORTANCE_LOW),
            )
        }
    }

    private fun buildNotification() = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(android.R.drawable.ic_media_play)
        .setContentTitle("PixelTrigger V5 — Shoulder")
        .setContentText("Shared PixelProbe detector stream • R/L active")
        .setOngoing(true)
        .build()

    override fun onDestroy() {
        if (activeInstance === this) activeInstance = null
        rViews.forEach { it?.let { view -> runCatching { windowManager.removeView(view) } } }
        lViews.forEach { it?.let { view -> runCatching { windowManager.removeView(view) } } }
        if (::shoulderInput.isInitialized) shoulderInput.disconnect()
        wakeLock?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }

    companion object {
        @Volatile private var activeInstance: ShoulderCaptureService? = null

        fun dispatchSharedFrame(image: Image, screenWidth: Int, screenHeight: Int) {
            activeInstance?.consumeSharedFrame(image, screenWidth, screenHeight)
        }

        fun statusSummary(): String = activeInstance?.summary() ?: "Shoulder starting…"

        const val ACTION_START = "com.pixeltrigger.app.action.START_SHOULDER"
        const val ACTION_STOP = "com.pixeltrigger.app.action.STOP_SHOULDER"
        const val ACTION_EDIT_R = "com.pixeltrigger.app.action.EDIT_SHOULDER_R"
        const val ACTION_EDIT_L = "com.pixeltrigger.app.action.EDIT_SHOULDER_L"
        const val ACTION_DONE_EDIT = "com.pixeltrigger.app.action.DONE_SHOULDER_EDIT"
        const val ACTION_SYNC_CONFIG = "com.pixeltrigger.app.action.SYNC_SHOULDER_CONFIG"
        const val PREFS_NAME = "pixeltrigger_shoulder_v5"
        const val MONITORS_PER_SIDE = 3
        const val MONITOR_DIAMETER_MM = 0.3f
        private const val CHANNEL_ID = "pixeltrigger_shoulder"
        private const val NOTIFICATION_ID = 5205
    }
}
