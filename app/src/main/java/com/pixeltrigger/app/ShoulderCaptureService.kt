package com.pixeltrigger.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.PowerManager
import android.os.Process
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import com.pixeltrigger.app.engine.PixelSampler
import com.pixeltrigger.app.engine.ShoulderDetectionEngine
import com.pixeltrigger.app.input.ShoulderShizukuEngine
import com.pixeltrigger.app.ui.ShoulderSensorOverlayView
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Completely independent left half of PixelTrigger V5.
 *
 * It owns its own MediaProjection, monitor circles, detector state, floating menu,
 * persistent positions, and R/L trigger path. It does not call or modify the V4
 * tap/detection engine. The only shared prerequisites are overlay permission and
 * the same Shizuku shell permission.
 */
class ShoulderCaptureService : Service() {
    private enum class Side { R, L }

    private lateinit var windowManager: WindowManager
    private lateinit var prefs: SharedPreferences
    private lateinit var shoulderInput: ShoulderShizukuEngine

    private val mainHandler = Handler(android.os.Looper.getMainLooper())
    private var captureThread: HandlerThread? = null
    private var captureHandler: Handler? = null
    private var imageReader: ImageReader? = null
    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var wakeLock: PowerManager.WakeLock? = null

    private var screenWidth = 0
    private var screenHeight = 0
    private var densityDpi = 0
    private var captureWidth = 0
    private var captureHeight = 0

    private var visibleDiameter = 1
    private var touchSize = 1

    private val rViews = arrayOfNulls<ShoulderSensorOverlayView>(MONITORS_PER_SIDE)
    private val lViews = arrayOfNulls<ShoulderSensorOverlayView>(MONITORS_PER_SIDE)
    private val rParams = arrayOfNulls<WindowManager.LayoutParams>(MONITORS_PER_SIDE)
    private val lParams = arrayOfNulls<WindowManager.LayoutParams>(MONITORS_PER_SIDE)

    private val rDetector = ShoulderDetectionEngine()
    private val lDetector = ShoulderDetectionEngine()

    @Volatile private var editSide: Side? = null
    private var menuButton: TextView? = null
    private var menuButtonParams: WindowManager.LayoutParams? = null
    private var menuPanel: View? = null
    private var menuPanelParams: WindowManager.LayoutParams? = null
    private var statusText: TextView? = null
    private var lastInputReady = false

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        shoulderInput = ShoulderShizukuEngine(this)
        shoulderInput.connect()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
        wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:ShoulderMonitor")
            .apply { acquire() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> if (projection == null) {
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
                val data = projectionIntent(intent)
                if (resultCode == 0 || data == null) stopSelf() else setupProjection(resultCode, data)
            }
            ACTION_EDIT_R -> enterEdit(Side.R)
            ACTION_EDIT_L -> enterEdit(Side.L)
            ACTION_DONE_EDIT -> leaveEdit()
            ACTION_STOP -> shutdown()
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun projectionIntent(intent: Intent): Intent? =
        if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        else @Suppress("DEPRECATION") intent.getParcelableExtra(EXTRA_RESULT_DATA)

    private fun setupProjection(resultCode: Int, resultData: Intent) {
        val bounds = if (Build.VERSION.SDK_INT >= 30) windowManager.currentWindowMetrics.bounds
        else @Suppress("DEPRECATION") android.graphics.Rect(0, 0, resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels)
        screenWidth = bounds.width().coerceAtLeast(1)
        screenHeight = bounds.height().coerceAtLeast(1)
        densityDpi = resources.displayMetrics.densityDpi
        captureWidth = max((screenWidth * CAPTURE_SCALE).roundToInt(), 1)
        captureHeight = max((screenHeight * CAPTURE_SCALE).roundToInt(), 1)

        captureThread = HandlerThread("PixelTriggerShoulderCapture", Process.THREAD_PRIORITY_URGENT_DISPLAY).also { it.start() }
        captureHandler = Handler(captureThread!!.looper)

        projection = (getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager)
            .getMediaProjection(resultCode, resultData)
            .also { mp ->
                mp.registerCallback(object : MediaProjection.Callback() {
                    override fun onStop() = stopSelf()
                }, mainHandler)
            }

        imageReader = ImageReader.newInstance(captureWidth, captureHeight, PixelFormat.RGBA_8888, 2).also { reader ->
            reader.setOnImageAvailableListener({ source ->
                source.acquireLatestImage()?.use(::processImage)
            }, captureHandler)
        }

        virtualDisplay = projection?.createVirtualDisplay(
            "PixelTriggerShoulderDisplay",
            captureWidth,
            captureHeight,
            max((densityDpi * CAPTURE_SCALE).roundToInt(), 1),
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface,
            null,
            captureHandler,
        )

        mainHandler.post {
            createOverlays()
            lastInputReady = shoulderInput.isReady()
            refreshStatusViews()
        }
    }

    private fun processImage(image: Image) {
        if (editSide != null || screenWidth <= 0 || screenHeight <= 0) return
        val crop = image.cropRect
        if (crop.width() <= 0 || crop.height() <= 0) return

        val inputReady = shoulderInput.isReady()
        if (inputReady != lastInputReady) {
            lastInputReady = inputReady
            mainHandler.post { refreshStatusViews() }
        }

        val rRed = sideHasRed(image, crop, rParams)
        val lRed = sideHasRed(image, crop, lParams)

        when (rDetector.process(rRed)) {
            ShoulderDetectionEngine.Event.Armed -> mainHandler.post { setSideStatus(Side.R, ShoulderSensorOverlayView.Status.ARMED) }
            ShoulderDetectionEngine.Event.Fired -> {
                if (inputReady) shoulderInput.fireR(pressDurationMs(Side.R))
                mainHandler.post { setSideStatus(Side.R, if (inputReady) ShoulderSensorOverlayView.Status.FIRED else ShoulderSensorOverlayView.Status.INPUT_NOT_READY) }
            }
            ShoulderDetectionEngine.Event.None -> Unit
        }

        when (lDetector.process(lRed)) {
            ShoulderDetectionEngine.Event.Armed -> mainHandler.post { setSideStatus(Side.L, ShoulderSensorOverlayView.Status.ARMED) }
            ShoulderDetectionEngine.Event.Fired -> {
                if (inputReady) shoulderInput.fireL(pressDurationMs(Side.L))
                mainHandler.post { setSideStatus(Side.L, if (inputReady) ShoulderSensorOverlayView.Status.FIRED else ShoulderSensorOverlayView.Status.INPUT_NOT_READY) }
            }
            ShoulderDetectionEngine.Event.None -> Unit
        }
    }

    private fun sideHasRed(
        image: Image,
        crop: Rect,
        params: Array<WindowManager.LayoutParams?>,
    ): Boolean {
        var i = 0
        while (i < MONITORS_PER_SIDE) {
            val lp = params[i]
            if (lp != null) {
                val sample = sample(image, crop, lp)
                if (sample != null && ShoulderDetectionEngine.isRed(sample)) return true
            }
            i++
        }
        return false
    }

    private fun sample(image: Image, crop: Rect, lp: WindowManager.LayoutParams) = run {
        val screenCenterX = lp.x + touchSize / 2
        val screenCenterY = lp.y + touchSize / 2
        val centerX = (crop.left + screenCenterX * crop.width().toFloat() / screenWidth).roundToInt()
            .coerceIn(crop.left, crop.right - 1)
        val centerY = (crop.top + screenCenterY * crop.height().toFloat() / screenHeight).roundToInt()
            .coerceIn(crop.top, crop.bottom - 1)
        val screenRadius = visibleDiameter / 2f
        val radiusX = max(0.5f, crop.width() * screenRadius / screenWidth)
        val radiusY = max(0.5f, crop.height() * screenRadius / screenHeight)
        PixelSampler.sampleCircularRegion(image, centerX, centerY, radiusX, radiusY)
    }

    private fun pressDurationMs(side: Side): Int {
        val prefix = if (side == Side.R) "r" else "l"
        val hold = prefs.getBoolean("shoulder_${prefix}_hold", false)
        if (!hold) return 0
        val seconds = prefs.getInt("shoulder_${prefix}_seconds", 1).coerceIn(1, 5)
        return seconds * 1000
    }

    private fun createOverlays() {
        if (rViews[0] != null) return
        visibleDiameter = max(mmToPx(MONITOR_DIAMETER_MM), 1)
        touchSize = max(dp(48), visibleDiameter + dp(30))

        createSideCircles(Side.R, rViews, rParams)
        createSideCircles(Side.L, lViews, lParams)
        createFloatingMenuButton()
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
            val defaultX = defaultX(side, i)
            val defaultY = screenHeight / 2 - touchSize / 2 + (i - 1) * dp(44)
            val lp = overlayParams(touchSize, touchSize).apply {
                x = normalizedPosition(side, i, "x", defaultX, screenWidth)
                y = normalizedPosition(side, i, "y", defaultY, screenHeight)
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
        var startRawX = 0f
        var startRawY = 0f
        var startX = 0
        var startY = 0
        view.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startRawX = event.rawX
                    startRawY = event.rawY
                    startX = lp.x
                    startY = lp.y
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    lp.x = startX + (event.rawX - startRawX).roundToInt()
                    lp.y = startY + (event.rawY - startRawY).roundToInt()
                    clamp(lp, touchSize, touchSize)
                    runCatching { windowManager.updateViewLayout(view, lp) }
                    savePosition(side, index, lp)
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    savePosition(side, index, lp)
                    true
                }
                else -> false
            }
        }
    }

    private fun createFloatingMenuButton() {
        val size = dp(46)
        val view = TextView(this).apply {
            text = "RL"
            textSize = 14f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = rounded(Color.rgb(196, 38, 63), Color.rgb(255, 120, 135), 18f)
        }
        val lp = overlayParams(size, size).apply {
            // Default immediately to the left of the V4 floating button.
            x = prefs.getInt(KEY_BUTTON_X, max(screenWidth - size * 2 - dp(24), 0))
            y = prefs.getInt(KEY_BUTTON_Y, dp(60))
            flags = baseFlags()
        }
        clamp(lp, size, size)
        menuButton = view
        menuButtonParams = lp
        windowManager.addView(view, lp)
        attachMenuButtonGesture(view, lp)
    }

    private fun attachMenuButtonGesture(view: View, lp: WindowManager.LayoutParams) {
        var downRawX = 0f
        var downRawY = 0f
        var startX = 0
        var startY = 0
        var moved = false
        view.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX
                    downRawY = event.rawY
                    startX = lp.x
                    startY = lp.y
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downRawX
                    val dy = event.rawY - downRawY
                    if (kotlin.math.abs(dx) > dp(5) || kotlin.math.abs(dy) > dp(5)) moved = true
                    if (moved) {
                        lp.x = startX + dx.roundToInt()
                        lp.y = startY + dy.roundToInt()
                        clamp(lp, view.width.coerceAtLeast(dp(46)), view.height.coerceAtLeast(dp(46)))
                        runCatching { windowManager.updateViewLayout(view, lp) }
                        prefs.edit().putInt(KEY_BUTTON_X, lp.x).putInt(KEY_BUTTON_Y, lp.y).apply()
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) toggleMenu()
                    true
                }
                else -> false
            }
        }
    }

    private fun toggleMenu() {
        if (menuPanel != null) closeMenu() else openMenu()
    }

    private fun openMenu() {
        val buttonLp = menuButtonParams ?: return
        val width = dp(280)
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background = rounded(Color.rgb(26, 26, 35), Color.rgb(100, 100, 120), 16f)
        }
        panel.addView(TextView(this).apply {
            text = "Shoulder R / L"
            textSize = 18f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        })
        statusText = TextView(this).apply {
            textSize = 12f
            setTextColor(Color.rgb(210, 210, 220))
            setPadding(0, dp(8), 0, dp(8))
        }
        panel.addView(statusText)
        panel.addView(menuButton("تعديل دوائر R") { enterEdit(Side.R) })
        panel.addView(menuButton("تعديل دوائر L") { enterEdit(Side.L) })
        panel.addView(menuButton("إنهاء التعديل وحفظ") { leaveEdit() })
        panel.addView(menuButton("إيقاف النصف الأيسر") { shutdown() })

        val lp = overlayParams(width, WindowManager.LayoutParams.WRAP_CONTENT).apply {
            x = (buttonLp.x - width - dp(8)).coerceAtLeast(0)
            y = buttonLp.y
            flags = baseFlags()
        }
        menuPanel = panel
        menuPanelParams = lp
        windowManager.addView(panel, lp)
        refreshMenuStatus()
    }

    private fun menuButton(label: String, action: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        setOnClickListener { action() }
    }

    private fun closeMenu() {
        menuPanel?.let { runCatching { windowManager.removeView(it) } }
        menuPanel = null
        menuPanelParams = null
        statusText = null
    }

    private fun enterEdit(side: Side) {
        if (rViews[0] == null) return
        editSide = side
        rDetector.reset()
        lDetector.reset()
        updateCircleTouchability()
        refreshStatusViews()
        refreshMenuStatus()
    }

    private fun leaveEdit() {
        editSide = null
        rDetector.reset()
        lDetector.reset()
        updateCircleTouchability()
        refreshStatusViews()
        refreshMenuStatus()
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
        if (!shoulderInput.isReady()) {
            setSideStatus(Side.R, ShoulderSensorOverlayView.Status.INPUT_NOT_READY)
            setSideStatus(Side.L, ShoulderSensorOverlayView.Status.INPUT_NOT_READY)
        } else {
            setSideStatus(Side.R, if (rDetector.state == ShoulderDetectionEngine.State.ARMED) ShoulderSensorOverlayView.Status.ARMED else ShoulderSensorOverlayView.Status.WAITING)
            setSideStatus(Side.L, if (lDetector.state == ShoulderDetectionEngine.State.ARMED) ShoulderSensorOverlayView.Status.ARMED else ShoulderSensorOverlayView.Status.WAITING)
        }
        refreshMenuStatus()
    }

    private fun setSideStatus(side: Side, status: ShoulderSensorOverlayView.Status) {
        val views = if (side == Side.R) rViews else lViews
        views.forEach { it?.setStatus(status) }
    }

    private fun refreshMenuStatus() {
        val r = pressDescription(Side.R)
        val l = pressDescription(Side.L)
        val edit = editSide?.name ?: "OFF"
        statusText?.text = "R: $r\nL: $l\nEdit: $edit\n${shoulderInput.status}"
    }

    private fun pressDescription(side: Side): String {
        val prefix = if (side == Side.R) "r" else "l"
        return if (!prefs.getBoolean("shoulder_${prefix}_hold", false)) "Flash"
        else "${prefs.getInt("shoulder_${prefix}_seconds", 1).coerceIn(1, 5)}s"
    }

    private fun overlayParams(width: Int, height: Int) = WindowManager.LayoutParams(
        width,
        height,
        if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
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

    private fun rounded(fill: Int, stroke: Int, radiusDp: Float) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setColor(fill)
        setStroke(dp(1), stroke)
        cornerRadius = dp(radiusDp.toInt()).toFloat()
    }

    private fun mmToPx(mm: Float): Int = (mm * densityDpi / 25.4f).roundToInt().coerceAtLeast(1)
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "PixelTrigger Shoulder", NotificationManager.IMPORTANCE_LOW),
            )
        }
    }

    private fun buildNotification() = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(android.R.drawable.ic_media_play)
        .setContentTitle("PixelTrigger V5 — Shoulder")
        .setContentText("R/L red trigger monitor is active")
        .setOngoing(true)
        .build()

    private fun shutdown() {
        closeMenu()
        rViews.forEach { it?.let { view -> runCatching { windowManager.removeView(view) } } }
        lViews.forEach { it?.let { view -> runCatching { windowManager.removeView(view) } } }
        menuButton?.let { runCatching { windowManager.removeView(it) } }
        imageReader?.setOnImageAvailableListener(null, null)
        virtualDisplay?.release()
        virtualDisplay = null
        imageReader?.close()
        imageReader = null
        projection?.stop()
        projection = null
        captureThread?.quitSafely()
        captureThread = null
        captureHandler = null
        shoulderInput.disconnect()
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        if (projection != null || menuButton != null) shutdown()
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "com.pixeltrigger.app.action.START_SHOULDER"
        const val ACTION_STOP = "com.pixeltrigger.app.action.STOP_SHOULDER"
        const val ACTION_EDIT_R = "com.pixeltrigger.app.action.EDIT_SHOULDER_R"
        const val ACTION_EDIT_L = "com.pixeltrigger.app.action.EDIT_SHOULDER_L"
        const val ACTION_DONE_EDIT = "com.pixeltrigger.app.action.DONE_SHOULDER_EDIT"
        const val EXTRA_RESULT_CODE = "shoulder_result_code"
        const val EXTRA_RESULT_DATA = "shoulder_result_data"

        const val PREFS_NAME = "pixeltrigger_shoulder_v5"
        const val MONITORS_PER_SIDE = 3
        const val MONITOR_DIAMETER_MM = 0.3f
        const val CAPTURE_SCALE = 0.5f

        private const val CHANNEL_ID = "pixeltrigger_shoulder"
        private const val NOTIFICATION_ID = 5205
        private const val KEY_BUTTON_X = "shoulder_menu_x"
        private const val KEY_BUTTON_Y = "shoulder_menu_y"
    }
}
