package com.pixeltrigger.app

import android.app.Notification
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
import android.os.SystemClock
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.pixeltrigger.app.engine.DetectionEngine
import com.pixeltrigger.app.engine.PixelSampler
import com.pixeltrigger.app.input.InputCapability
import com.pixeltrigger.app.input.ShizukuTapEngine
import com.pixeltrigger.app.ui.SensorOverlayView
import com.pixeltrigger.app.ui.SensorStatus
import com.pixeltrigger.app.ui.TargetOverlayView
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * PixelTrigger V5 single capture host.
 *
 * The V4 PixelProbe detector/action path is retained. The only architectural
 * difference is that each captured frame is also handed to ShoulderCaptureService
 * before V4 does any early-return work. That gives both engines exactly the same
 * source frame while keeping independent detectors and independent FIRE actions.
 *
 * This service also owns the ONLY floating control center in V5.
 */
class ScreenCaptureService : Service() {
    private lateinit var windowManager: WindowManager
    private lateinit var preferences: SharedPreferences
    private val mainHandler = Handler(android.os.Looper.getMainLooper())

    private var captureThread: HandlerThread? = null
    private var captureHandler: Handler? = null
    private var imageReader: ImageReader? = null
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var wakeLock: PowerManager.WakeLock? = null

    private var screenWidth = 0
    private var screenHeight = 0
    private var densityDpi = 0
    private var captureWidth = 0
    private var captureHeight = 0
    private var captureDensityDpi = 0

    private val sensorViews = arrayOfNulls<SensorOverlayView>(TOTAL_MONITOR_COUNT)
    private val sensorParams = arrayOfNulls<WindowManager.LayoutParams>(TOTAL_MONITOR_COUNT)
    private val detectionEngines = Array(TOTAL_MONITOR_COUNT) { DetectionEngine() }
    private var sensorVisibleDiameter = 1
    private var sensorTouchSize = 1
    @Volatile private var activeGroup = 0

    private var targetView: TargetOverlayView? = null
    private var targetParams: WindowManager.LayoutParams? = null
    private var targetTouchSize = 1

    private var menuButton: TextView? = null
    private var menuButtonParams: WindowManager.LayoutParams? = null
    private var menuPanel: View? = null
    private var menuPanelParams: WindowManager.LayoutParams? = null
    private var menuStatusText: TextView? = null

    private var circlesVisible = true
    @Volatile private var engineEnabled = true
    @Volatile private var circleEditMode = false
    private var lastInputReady = false

    private lateinit var tapEngine: ShizukuTapEngine

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayRemoved(displayId: Int) = Unit
        override fun onDisplayChanged(displayId: Int) {
            mainHandler.removeCallbacks(refreshDisplayRunnable)
            mainHandler.postDelayed(refreshDisplayRunnable, DISPLAY_REFRESH_DEBOUNCE_MS)
        }
    }
    private val refreshDisplayRunnable = Runnable { refreshDisplayGeometry() }

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        preferences = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        circlesVisible = preferences.getBoolean(KEY_CIRCLES_VISIBLE, true)
        activeGroup = preferences.getInt(KEY_ACTIVE_GROUP, 0).coerceIn(0, GROUP_COUNT - 1)

        val whiteRearm = preferences.getBoolean(KEY_WHITE_REARM, true)
        val delayEnabled = preferences.getBoolean(KEY_REARM_DELAY_ENABLED, false)
        val rearmSeconds = preferences.getInt(KEY_REARM_SECONDS, 10).coerceIn(5, 60)
        detectionEngines.forEach { engine ->
            engine.whiteRearmEnabled = whiteRearm
            engine.rearmDelayEnabled = delayEnabled
            engine.rearmSeconds = rearmSeconds
        }

        tapEngine = ShizukuTapEngine(this)
        tapEngine.connect()

        (getSystemService(DISPLAY_SERVICE) as DisplayManager).registerDisplayListener(displayListener, mainHandler)
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
        wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:PixelMonitor")
            .apply { acquire() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> if (mediaProjection == null) {
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
                val data = projectionIntent(intent)
                if (resultCode == 0 || data == null) stopSelf() else setupProjection(resultCode, data)
            }
            ACTION_STOP -> shutdownCompletely()
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun projectionIntent(intent: Intent): Intent? =
        if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        else @Suppress("DEPRECATION") intent.getParcelableExtra(EXTRA_RESULT_DATA)

    private fun setupProjection(resultCode: Int, resultData: Intent) {
        val bounds = currentScreenBounds()
        screenWidth = bounds.width()
        screenHeight = bounds.height()
        densityDpi = resources.displayMetrics.densityDpi
        updateCaptureGeometry()

        captureThread = HandlerThread("PixelTriggerCapture", Process.THREAD_PRIORITY_URGENT_DISPLAY).also { it.start() }
        captureHandler = Handler(captureThread!!.looper)

        mediaProjection = (getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager)
            .getMediaProjection(resultCode, resultData)
            .also { projection ->
                projection.registerCallback(object : MediaProjection.Callback() {
                    override fun onStop() = stopSelf()
                }, mainHandler)
            }

        imageReader = createImageReader(captureWidth, captureHeight)
        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "PixelTriggerDisplay",
            captureWidth,
            captureHeight,
            captureDensityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface,
            null,
            captureHandler,
        )

        mainHandler.post {
            createOverlays()
            lastInputReady = tapEngine.isReady()
            refreshSensorStatuses(lastInputReady)
        }
    }

    private fun updateCaptureGeometry() {
        captureWidth = max((screenWidth * CAPTURE_SCALE).roundToInt(), 1)
        captureHeight = max((screenHeight * CAPTURE_SCALE).roundToInt(), 1)
        captureDensityDpi = max((densityDpi * CAPTURE_SCALE).roundToInt(), 1)
    }

    private fun createImageReader(width: Int, height: Int): ImageReader =
        ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2).also { reader ->
            reader.setOnImageAvailableListener({ source ->
                source.acquireLatestImage()?.use(::processImage)
            }, captureHandler)
        }

    private fun processImage(image: Image) {
        // Shared at the absolute start of the frame path. The Shoulder engine
        // therefore keeps running even if V4 is OFF, editing, or returns after FIRE.
        ShoulderCaptureService.dispatchSharedFrame(image, screenWidth, screenHeight)

        if (!engineEnabled || circleEditMode) return

        val inputReady = tapEngine.isReady()
        if (inputReady != lastInputReady) {
            lastInputReady = inputReady
            refreshSensorStatuses(inputReady)
        }

        if (screenWidth <= 0 || screenHeight <= 0) return
        val crop = image.cropRect
        if (crop.width() <= 0 || crop.height() <= 0) return

        val group = activeGroup
        val base = groupBaseIndex(group)
        val nowMs = SystemClock.elapsedRealtime()
        var local = 0
        var statusChanged = false
        var manualTimeout = false

        while (local < MONITORS_PER_GROUP) {
            val index = base + local
            val params = sensorParams[index]
            if (params != null) {
                val sample = sampleSensor(image, crop, params)
                if (sample != null) {
                    when (detectionEngines[index].processSample(sample, nowMs)) {
                        is DetectionEngine.Event.Armed,
                        is DetectionEngine.Event.Rearmed,
                        is DetectionEngine.Event.ManualRearmed -> statusChanged = true

                        is DetectionEngine.Event.Fired -> {
                            var siblingLocal = 0
                            while (siblingLocal < MONITORS_PER_GROUP) {
                                val siblingIndex = base + siblingLocal
                                if (siblingIndex != index) detectionEngines[siblingIndex].synchronizeAfterExternalFire(nowMs)
                                siblingLocal++
                            }
                            executeTapImmediately()
                            refreshSensorStatuses(inputReady, forced = SensorStatus.FIRED)
                            return
                        }

                        is DetectionEngine.Event.ManualRearmTimedOut -> manualTimeout = true
                        else -> Unit
                    }
                }
            }
            local++
        }

        if (statusChanged) refreshSensorStatuses(inputReady)
        if (manualTimeout) showMessage("لم يتم التسليح: اللون الأبيض غير موجود")
    }

    private fun sampleSensor(
        image: Image,
        crop: Rect,
        params: WindowManager.LayoutParams,
    ): DetectionEngine.ColorSample? {
        val screenCenterX = params.x + sensorTouchSize / 2
        val screenCenterY = params.y + sensorTouchSize / 2
        val centerX = (crop.left + (screenCenterX * crop.width().toFloat() / screenWidth)).roundToInt()
            .coerceIn(crop.left, crop.right - 1)
        val centerY = (crop.top + (screenCenterY * crop.height().toFloat() / screenHeight)).roundToInt()
            .coerceIn(crop.top, crop.bottom - 1)
        val screenRadius = sensorVisibleDiameter / 2f
        val radiusX = max(0.5f, crop.width() * screenRadius / screenWidth)
        val radiusY = max(0.5f, crop.height() * screenRadius / screenHeight)
        return PixelSampler.sampleCircularRegion(image, centerX, centerY, radiusX, radiusY)
    }

    private fun executeTapImmediately() {
        if (!engineEnabled) return
        val target = targetParams ?: return
        val tapX = target.x + targetTouchSize / 2f
        val tapY = target.y + targetTouchSize / 2f
        tapEngine.fireFast(tapX, tapY, displayId = 0)
    }

    private fun createOverlays() {
        if (sensorViews[0] != null) return
        sensorVisibleDiameter = max(mmToPx(MONITOR_DIAMETER_MM), 1)
        val targetVisibleDiameter = max(mmToPx(5f), dp(12))

        var group = 0
        while (group < GROUP_COUNT) {
            var local = 0
            while (local < MONITORS_PER_GROUP) {
                val index = flatSensorIndex(group, local)
                val sensor = SensorOverlayView(this, sensorVisibleDiameter)
                if (index == 0) sensorTouchSize = max(dp(48), sensor.outerDiameterPx + dp(30))
                sensorViews[index] = sensor

                val defaultX = defaultSensorX(local)
                val defaultY = screenHeight / 2 - sensorTouchSize / 2
                val sensorLp = overlayParams(sensorTouchSize, sensorTouchSize).apply {
                    x = preferences.getInt(sensorKeyX(group, local), defaultX)
                    y = preferences.getInt(sensorKeyY(group, local), defaultY)
                }
                sensorParams[index] = sensorLp
                clampCirclePosition(sensorLp, sensorVisibleDiameter)
                windowManager.addView(sensor, sensorLp)

                val sensorGroup = group
                val sensorLocal = local
                attachDrag(sensor, sensorLp, sensor.outerDiameterPx) { x, y ->
                    val movedIndex = flatSensorIndex(sensorGroup, sensorLocal)
                    detectionEngines[movedIndex].resetForSensorMove()
                    preferences.edit()
                        .putInt(sensorKeyX(sensorGroup, sensorLocal), x)
                        .putInt(sensorKeyY(sensorGroup, sensorLocal), y)
                        .apply()
                }
                local++
            }
            group++
        }

        targetTouchSize = max(dp(52), dp(24) + targetVisibleDiameter)
        val target = TargetOverlayView(this, targetVisibleDiameter)
        targetView = target
        val targetLp = overlayParams(targetTouchSize, targetTouchSize).apply {
            x = preferences.getInt(KEY_TARGET_X, screenWidth / 2 + dp(70))
            y = preferences.getInt(KEY_TARGET_Y, screenHeight / 2 - targetTouchSize / 2)
        }
        targetParams = targetLp
        clampCirclePosition(targetLp, targetVisibleDiameter)
        windowManager.addView(target, targetLp)
        attachDrag(target, targetLp, targetVisibleDiameter) { x, y ->
            preferences.edit().putInt(KEY_TARGET_X, x).putInt(KEY_TARGET_Y, y).apply()
        }

        // Single floating control for the whole V5 system.
        val buttonSize = dp(54)
        val button = TextView(this).apply {
            text = "P5\n${activeGroup + 1}"
            textSize = 12f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = roundedBackground(Color.rgb(79, 52, 185), Color.rgb(244, 114, 143), 20f)
        }
        menuButton = button
        val buttonLp = overlayParams(buttonSize, buttonSize).apply {
            x = preferences.getInt(KEY_BUTTON_X, max(screenWidth - buttonSize - dp(12), 0))
            y = preferences.getInt(KEY_BUTTON_Y, dp(60))
            flags = baseOverlayFlags()
        }
        menuButtonParams = buttonLp
        clampPosition(buttonLp, buttonSize)
        windowManager.addView(button, buttonLp)
        attachFloatingButtonGesture(button, buttonLp)

        setConfigurationTouchability(false)
        applyGroupVisibility()
        updateButtonVisual()
    }

    private fun defaultSensorX(local: Int): Int = when (local) {
        0 -> screenWidth / 2 - sensorTouchSize / 2
        1 -> screenWidth / 2 - sensorTouchSize / 2 - dp(56)
        else -> screenWidth / 2 - sensorTouchSize / 2 + dp(56)
    }

    private fun setConfigurationTouchability(enabled: Boolean) {
        val activeBase = groupBaseIndex(activeGroup)
        var i = 0
        while (i < TOTAL_MONITOR_COUNT) {
            val view = sensorViews[i]
            val lp = sensorParams[i]
            if (view != null && lp != null) {
                val belongsToActiveGroup = i >= activeBase && i < activeBase + MONITORS_PER_GROUP
                lp.flags = if (enabled && belongsToActiveGroup) baseOverlayFlags()
                else baseOverlayFlags() or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                runCatching { windowManager.updateViewLayout(view, lp) }
            }
            i++
        }
        targetView?.let { view ->
            targetParams?.let { lp ->
                lp.flags = if (enabled) baseOverlayFlags()
                else baseOverlayFlags() or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                runCatching { windowManager.updateViewLayout(view, lp) }
            }
        }
    }

    private fun attachFloatingButtonGesture(view: View, params: WindowManager.LayoutParams) {
        var longPressTriggered = false
        val twoSecondHold = Runnable {
            if (!circleEditMode) {
                longPressTriggered = true
                toggleEngine()
            }
        }

        val detector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean {
                longPressTriggered = false
                mainHandler.removeCallbacks(twoSecondHold)
                mainHandler.postDelayed(twoSecondHold, ENGINE_HOLD_MS)
                return true
            }

            override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
                mainHandler.removeCallbacks(twoSecondHold)
                params.x -= distanceX.roundToInt()
                params.y -= distanceY.roundToInt()
                clampPosition(params, view.width.takeIf { it > 0 } ?: params.width)
                windowManager.updateViewLayout(view, params)
                preferences.edit().putInt(KEY_BUTTON_X, params.x).putInt(KEY_BUTTON_Y, params.y).apply()
                return true
            }

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                mainHandler.removeCallbacks(twoSecondHold)
                if (longPressTriggered) return true
                if (circleEditMode) finishCirclePositionEditing() else toggleMenu()
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                mainHandler.removeCallbacks(twoSecondHold)
                if (longPressTriggered) return true
                if (circleEditMode) finishCirclePositionEditing() else switchToNextGroup()
                return true
            }

            override fun onLongPress(e: MotionEvent) = Unit
        })

        view.setOnTouchListener { _, event ->
            val handled = detector.onTouchEvent(event)
            if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
                mainHandler.removeCallbacks(twoSecondHold)
            }
            handled
        }
    }

    private fun switchToNextGroup() {
        if (circleEditMode) return
        closeMenu()
        val next = (activeGroup + 1) % GROUP_COUNT
        val switchTask = Runnable {
            resetGroupDetectorsNow(activeGroup)
            resetGroupDetectorsNow(next)
            activeGroup = next
            preferences.edit().putInt(KEY_ACTIVE_GROUP, next).apply()
            lastInputReady = tapEngine.isReady()
            mainHandler.post {
                applyGroupVisibility()
                setConfigurationTouchability(false)
                updateButtonVisual()
                refreshSensorStatuses(lastInputReady)
                showMessage("مجموعة PixelProbe ${next + 1}")
            }
        }
        captureHandler?.post(switchTask) ?: switchTask.run()
    }

    private fun attachDrag(
        view: View,
        params: WindowManager.LayoutParams,
        visibleDiameter: Int,
        onMoved: (Int, Int) -> Unit,
    ) {
        var grabOffsetX = 0f
        var grabOffsetY = 0f
        var framePending = false

        fun updateNextFrame() {
            if (framePending) return
            framePending = true
            view.postOnAnimation {
                framePending = false
                runCatching { windowManager.updateViewLayout(view, params) }
            }
        }

        view.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    grabOffsetX = event.rawX - params.x
                    grabOffsetY = event.rawY - params.y
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = (event.rawX - grabOffsetX).roundToInt()
                    params.y = (event.rawY - grabOffsetY).roundToInt()
                    clampCirclePosition(params, visibleDiameter)
                    updateNextFrame()
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    clampCirclePosition(params, visibleDiameter)
                    runCatching { windowManager.updateViewLayout(view, params) }
                    onMoved(params.x, params.y)
                    true
                }
                else -> true
            }
        }
    }

    private fun beginCirclePositionEditing() {
        closeMenu()
        circleEditMode = true
        setCirclesVisible(true)
        setConfigurationTouchability(true)
        updateButtonVisual()
        showMessage("اسحب دوائر PixelProbe للمجموعة ${activeGroup + 1} ودائرة الضغط، ثم اضغط P5 للحفظ")
    }

    private fun finishCirclePositionEditing() {
        if (!circleEditMode) return
        circleEditMode = false
        setConfigurationTouchability(false)
        resetGroupDetectors(activeGroup)
        updateButtonVisual()
        showMessage("تم حفظ مواضع PixelProbe للمجموعة ${activeGroup + 1}")
    }

    private fun resetMonitorCirclesToCenter() {
        closeMenu()
        setCirclesVisible(true)
        val group = activeGroup
        val x = screenWidth / 2 - sensorTouchSize / 2
        val y = screenHeight / 2 - sensorTouchSize / 2
        val editor = preferences.edit()
        var local = 0
        while (local < MONITORS_PER_GROUP) {
            val index = flatSensorIndex(group, local)
            val lp = sensorParams[index]
            val view = sensorViews[index]
            if (lp != null && view != null) {
                lp.x = x
                lp.y = y
                clampCirclePosition(lp, sensorVisibleDiameter)
                runCatching { windowManager.updateViewLayout(view, lp) }
                editor.putInt(sensorKeyX(group, local), lp.x).putInt(sensorKeyY(group, local), lp.y)
            }
            local++
        }
        editor.apply()
        resetGroupDetectors(group)
        refreshSensorStatuses(tapEngine.isReady())
        showMessage("أعيدت دوائر PixelProbe للمجموعة ${group + 1} إلى المنتصف")
    }

    private fun resetGroupDetectors(group: Int) {
        val task = Runnable { resetGroupDetectorsNow(group) }
        captureHandler?.post(task) ?: task.run()
    }

    private fun resetGroupDetectorsNow(group: Int) {
        val base = groupBaseIndex(group)
        var local = 0
        while (local < MONITORS_PER_GROUP) {
            detectionEngines[base + local].resetForSensorMove()
            local++
        }
    }

    private fun resetAllDetectorsNow() {
        var i = 0
        while (i < TOTAL_MONITOR_COUNT) {
            detectionEngines[i].resetForSensorMove()
            i++
        }
    }

    private fun toggleEngine() {
        val enableRequested = !engineEnabled
        engineEnabled = false
        val task = Runnable {
            resetAllDetectorsNow()
            engineEnabled = enableRequested
            lastInputReady = tapEngine.isReady()
            refreshSensorStatuses(lastInputReady, forced = if (enableRequested) null else SensorStatus.OFF)
        }
        captureHandler?.post(task) ?: task.run()
    }

    private fun aggregateState(): DetectionEngine.State {
        val base = groupBaseIndex(activeGroup)
        var anyRearm = false
        var local = 0
        while (local < MONITORS_PER_GROUP) {
            when (detectionEngines[base + local].state) {
                DetectionEngine.State.ARMED -> return DetectionEngine.State.ARMED
                DetectionEngine.State.WAITING_REARM -> anyRearm = true
                else -> Unit
            }
            local++
        }
        return if (anyRearm) DetectionEngine.State.WAITING_REARM else DetectionEngine.State.WAITING_FOR_WHITE
    }

    private fun updateButtonVisual() {
        val button = menuButton ?: return
        val fill = when {
            circleEditMode -> Color.rgb(30, 165, 92)
            !engineEnabled -> Color.rgb(95, 95, 104)
            tapEngine.capability != InputCapability.CONCURRENT_TOUCH_SAFE -> Color.rgb(165, 70, 190)
            aggregateState() == DetectionEngine.State.ARMED -> Color.rgb(32, 170, 88)
            else -> Color.rgb(79, 52, 185)
        }
        button.text = when {
            circleEditMode -> "✓\nP5"
            !engineEnabled -> "P5\nOFF"
            else -> "P5\n${activeGroup + 1}"
        }
        button.textSize = 11f
        button.background = roundedBackground(fill, Color.rgb(244, 114, 143), 20f)
    }

    private fun toggleMenu() {
        if (menuPanel != null) closeMenu() else showMenu()
    }

    private fun showMenu() {
        if (menuPanel != null) return
        tapEngine.refreshCapability()
        setConfigurationTouchability(false)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(8), dp(10), dp(10))
            background = roundedBackground(Color.rgb(247, 247, 251), Color.rgb(146, 142, 167), 18f)
        }

        val header = TextView(this).apply {
            text = "PixelTrigger V5  •  P${activeGroup + 1}  •  R/L"
            textSize = 16f
            setTextColor(Color.rgb(24, 23, 32))
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(9), dp(8), dp(9))
            background = roundedBackground(Color.rgb(228, 224, 247), Color.rgb(170, 159, 224), 12f)
        }
        root.addView(header, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(46)))

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(6), 0, 0)
        }

        menuStatusText = TextView(this).apply {
            text = combinedStatusText()
            textSize = 12f
            setTextColor(Color.rgb(42, 42, 52))
            gravity = Gravity.CENTER
            setPadding(dp(6), dp(4), dp(6), dp(6))
        }
        content.addView(menuStatusText, matchWrap())

        content.addView(sectionLabel("SHOULDER  •  R / L", Color.rgb(150, 49, 76)), matchWrap())
        content.addView(shoulderControlCard(), matchWrap())

        content.addView(sectionLabel("PIXELPROBE  •  TAP", Color.rgb(83, 58, 170)), matchWrap())
        content.addView(
            actionCard(
                "تعديل مواضع PixelProbe ${activeGroup + 1}",
                "اسحب الدوائر الثلاث ودائرة الضغط. الحفظ تلقائي عند الانتهاء.",
            ) { beginCirclePositionEditing() },
            matchWrap(dp(86)),
        )
        content.addView(
            actionCard(
                "إعادة PixelProbe ${activeGroup + 1} إلى المنتصف",
                "يعيد دوائر المجموعة الحالية فقط ولا يغير R/L أو دائرة الضغط.",
            ) { resetMonitorCirclesToCenter() },
            matchWrap(dp(86)),
        )
        content.addView(menuButton("إظهار / إخفاء دوائر PixelProbe") { setCirclesVisible(!circlesVisible) }, matchWrap(dp(48)))

        content.addView(sectionLabel("DETECTOR  •  مشترك", Color.rgb(42, 119, 79)), matchWrap())
        content.addView(detectorSettingsCard(), matchWrap())

        content.addView(sectionLabel("SYSTEM", Color.rgb(70, 70, 82)), matchWrap())
        content.addView(menuButton("إغلاق مركز التحكم") { closeMenu() }, matchWrap(dp(48)))
        content.addView(menuButton("إيقاف PixelTrigger V5 بالكامل") { shutdownCompletely() }, matchWrap(dp(48), danger = true))

        val scroll = ScrollView(this).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = true
            addView(content, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))
        }
        root.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        val margin = dp(10)
        val availableWidth = max(screenWidth - margin * 2, 1)
        val availableHeight = max(screenHeight - margin * 2, 1)
        val width = min(dp(430), availableWidth).coerceAtLeast(min(dp(230), availableWidth))
        val height = min(dp(650), availableHeight).coerceAtLeast(min(dp(220), availableHeight))
        val lp = WindowManager.LayoutParams(
            width,
            height,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = preferences.getInt(KEY_MENU_X, ((screenWidth - width) / 2).coerceAtLeast(margin))
            y = preferences.getInt(KEY_MENU_Y, ((screenHeight - height) / 2).coerceAtLeast(margin))
        }
        menuPanel = root
        menuPanelParams = lp
        clampMenuPosition(lp)
        windowManager.addView(root, lp)
        attachMenuDrag(header, root, lp)
    }

    private fun sectionLabel(textValue: String, color: Int) = TextView(this).apply {
        text = textValue
        textSize = 12f
        setTextColor(color)
        gravity = Gravity.CENTER
        setPadding(dp(4), dp(10), dp(4), dp(5))
    }

    private fun shoulderControlCard(): View {
        val shoulderPrefs = getSharedPreferences(ShoulderCaptureService.PREFS_NAME, MODE_PRIVATE)
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(8), dp(10), dp(8))
            background = roundedBackground(Color.rgb(255, 238, 242), Color.rgb(226, 105, 133), 14f)
        }

        card.addView(TextView(this).apply {
            text = ShoulderCaptureService.statusSummary()
            textSize = 12f
            setTextColor(Color.rgb(91, 46, 60))
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(4))
        }, matchWrap())

        fun sideRow(label: String, prefix: String): View {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val hold = Switch(this).apply {
                text = "$label HOLD"
                isChecked = shoulderPrefs.getBoolean("shoulder_${prefix}_hold", false)
                setTextColor(Color.rgb(68, 35, 45))
            }
            val value = TextView(this).apply {
                gravity = Gravity.CENTER
                textSize = 13f
                setTextColor(Color.rgb(68, 35, 45))
            }
            fun refresh() {
                value.text = if (hold.isChecked) "${shoulderPrefs.getInt("shoulder_${prefix}_seconds", 1).coerceIn(1, 5)}s" else "Flash"
            }
            hold.setOnCheckedChangeListener { _, checked ->
                shoulderPrefs.edit().putBoolean("shoulder_${prefix}_hold", checked).apply()
                refresh()
            }
            val minus = menuButton("−") {
                val n = (shoulderPrefs.getInt("shoulder_${prefix}_seconds", 1) - 1).coerceIn(1, 5)
                shoulderPrefs.edit().putInt("shoulder_${prefix}_seconds", n).apply()
                refresh()
            }
            val plus = menuButton("+") {
                val n = (shoulderPrefs.getInt("shoulder_${prefix}_seconds", 1) + 1).coerceIn(1, 5)
                shoulderPrefs.edit().putInt("shoulder_${prefix}_seconds", n).apply()
                refresh()
            }
            row.addView(hold, LinearLayout.LayoutParams(0, dp(46), 1f))
            row.addView(minus, LinearLayout.LayoutParams(dp(48), dp(42)))
            row.addView(value, LinearLayout.LayoutParams(dp(54), dp(42)))
            row.addView(plus, LinearLayout.LayoutParams(dp(48), dp(42)))
            refresh()
            return row
        }

        card.addView(sideRow("R", "r"), matchWrap(dp(48)))
        card.addView(sideRow("L", "l"), matchWrap(dp(48)))

        val editRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        editRow.addView(menuButton("دوائر R") { shoulderAction(ShoulderCaptureService.ACTION_EDIT_R) }, LinearLayout.LayoutParams(0, dp(46), 1f))
        editRow.addView(menuButton("دوائر L") { shoulderAction(ShoulderCaptureService.ACTION_EDIT_L) }, LinearLayout.LayoutParams(0, dp(46), 1f))
        editRow.addView(menuButton("✓ حفظ") { shoulderAction(ShoulderCaptureService.ACTION_DONE_EDIT) }, LinearLayout.LayoutParams(0, dp(46), 1f))
        card.addView(editRow, matchWrap(dp(48)))
        return card
    }

    private fun detectorSettingsCard(): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(8), dp(10), dp(8))
            background = roundedBackground(Color.rgb(235, 248, 240), Color.rgb(89, 171, 122), 14f)
        }
        val primary = detectionEngines[groupBaseIndex(activeGroup)]

        val whiteSwitch = Switch(this).apply {
            text = "إعادة التسليح عند ظهور الأبيض"
            isChecked = primary.whiteRearmEnabled
            setTextColor(Color.rgb(31, 55, 40))
            setOnCheckedChangeListener { _, checked ->
                detectionEngines.forEach { it.whiteRearmEnabled = checked }
                preferences.edit().putBoolean(KEY_WHITE_REARM, checked).apply()
                syncShoulderDetectorConfig()
            }
        }
        card.addView(whiteSwitch, matchWrap(dp(50)))

        val delaySwitch = Switch(this).apply {
            text = "تأخير إعادة التسليح"
            isChecked = primary.rearmDelayEnabled
            setTextColor(Color.rgb(31, 55, 40))
            setOnCheckedChangeListener { _, checked ->
                detectionEngines.forEach { it.rearmDelayEnabled = checked }
                preferences.edit().putBoolean(KEY_REARM_DELAY_ENABLED, checked).apply()
                syncShoulderDetectorConfig()
            }
        }
        card.addView(delaySwitch, matchWrap(dp(50)))

        val secondsText = TextView(this).apply {
            text = "${primary.rearmSeconds} ثانية"
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(31, 55, 40))
            textSize = 14f
        }
        val durationRow = LinearLayout(this).apply { gravity = Gravity.CENTER }
        durationRow.addView(menuButton("−") {
            val seconds = (detectionEngines[0].rearmSeconds - 1).coerceIn(5, 60)
            detectionEngines.forEach { it.rearmSeconds = seconds }
            preferences.edit().putInt(KEY_REARM_SECONDS, seconds).apply()
            secondsText.text = "$seconds ثانية"
            syncShoulderDetectorConfig()
        }, LinearLayout.LayoutParams(dp(58), dp(44)))
        durationRow.addView(secondsText, LinearLayout.LayoutParams(0, dp(44), 1f))
        durationRow.addView(menuButton("+") {
            val seconds = (detectionEngines[0].rearmSeconds + 1).coerceIn(5, 60)
            detectionEngines.forEach { it.rearmSeconds = seconds }
            preferences.edit().putInt(KEY_REARM_SECONDS, seconds).apply()
            secondsText.text = "$seconds ثانية"
            syncShoulderDetectorConfig()
        }, LinearLayout.LayoutParams(dp(58), dp(44)))
        card.addView(durationRow, matchWrap(dp(48)))

        card.addView(menuButton("تفعيل التسليح الآن لمرة واحدة — PixelProbe") {
            captureHandler?.post {
                val now = SystemClock.elapsedRealtime()
                val base = groupBaseIndex(activeGroup)
                var requested = false
                var local = 0
                while (local < MONITORS_PER_GROUP) {
                    requested = detectionEngines[base + local].requestOneTimeRearmOverride(now) || requested
                    local++
                }
                if (!requested) showMessage("لا يوجد تأخير جارٍ يمكن تجاوزه")
            }
            closeMenu()
        }, matchWrap(dp(46)))
        return card
    }

    private fun shoulderAction(action: String) {
        runCatching {
            startService(Intent(this, ShoulderCaptureService::class.java).apply { this.action = action })
        }
        closeMenu()
    }

    private fun syncShoulderDetectorConfig() {
        runCatching {
            startService(Intent(this, ShoulderCaptureService::class.java).apply {
                action = ShoulderCaptureService.ACTION_SYNC_CONFIG
            })
        }
    }

    private fun combinedStatusText(): String =
        "PixelProbe: ${engineStatusText()}\nShoulder: ${ShoulderCaptureService.statusSummary()}\nInput: ${tapEngine.capability}"

    private fun captureStatsText(): String =
        "capture=${captureWidth}x${captureHeight} (${(CAPTURE_SCALE * 100).roundToInt()}%); group=${activeGroup + 1}; V4 active=3/9; Shoulder active=3R+3L"

    private fun attachMenuDrag(handle: View, panel: View, params: WindowManager.LayoutParams) {
        var grabOffsetX = 0f
        var grabOffsetY = 0f
        var framePending = false

        fun updateNextFrame() {
            if (framePending) return
            framePending = true
            panel.postOnAnimation {
                framePending = false
                if (menuPanel === panel) runCatching { windowManager.updateViewLayout(panel, params) }
            }
        }

        handle.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    grabOffsetX = event.rawX - params.x
                    grabOffsetY = event.rawY - params.y
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = (event.rawX - grabOffsetX).roundToInt()
                    params.y = (event.rawY - grabOffsetY).roundToInt()
                    clampMenuPosition(params)
                    updateNextFrame()
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    clampMenuPosition(params)
                    runCatching { windowManager.updateViewLayout(panel, params) }
                    preferences.edit().putInt(KEY_MENU_X, params.x).putInt(KEY_MENU_Y, params.y).apply()
                    true
                }
                else -> true
            }
        }
    }

    private fun closeMenu() {
        menuPanel?.let { runCatching { windowManager.removeView(it) } }
        menuPanel = null
        menuPanelParams = null
        menuStatusText = null
        setConfigurationTouchability(false)
    }

    private fun setCirclesVisible(visible: Boolean) {
        circlesVisible = visible
        preferences.edit().putBoolean(KEY_CIRCLES_VISIBLE, visible).apply()
        applyGroupVisibility()
    }

    private fun applyGroupVisibility() {
        val activeBase = groupBaseIndex(activeGroup)
        var i = 0
        while (i < TOTAL_MONITOR_COUNT) {
            val active = i >= activeBase && i < activeBase + MONITORS_PER_GROUP
            sensorViews[i]?.visibility = if (circlesVisible && active) View.VISIBLE else View.INVISIBLE
            i++
        }
        targetView?.visibility = if (circlesVisible) View.VISIBLE else View.INVISIBLE
    }

    private fun engineStatusText(): String = when {
        !engineEnabled -> "OFF"
        tapEngine.capability != InputCapability.CONCURRENT_TOUCH_SAFE -> "WAITING_SHIZUKU"
        aggregateState() == DetectionEngine.State.ARMED -> "ARMED"
        aggregateState() == DetectionEngine.State.WAITING_REARM -> "WAITING_REARM"
        else -> "WAITING_FOR_WHITE"
    }

    private fun sensorStatusFor(engine: DetectionEngine, inputReady: Boolean): SensorStatus = when {
        !engineEnabled -> SensorStatus.OFF
        !inputReady -> SensorStatus.INPUT_NOT_READY
        engine.state == DetectionEngine.State.ARMED -> SensorStatus.ARMED
        engine.state == DetectionEngine.State.WAITING_REARM -> SensorStatus.FIRED
        else -> SensorStatus.WAITING
    }

    private fun refreshSensorStatuses(inputReady: Boolean, forced: SensorStatus? = null) {
        mainHandler.post {
            val base = groupBaseIndex(activeGroup)
            var local = 0
            while (local < MONITORS_PER_GROUP) {
                val index = base + local
                sensorViews[index]?.setStatus(forced ?: sensorStatusFor(detectionEngines[index], inputReady))
                local++
            }
            updateButtonVisual()
            menuStatusText?.text = combinedStatusText()
        }
    }

    private fun refreshDisplayGeometry() {
        val bounds = currentScreenBounds()
        val newWidth = bounds.width()
        val newHeight = bounds.height()
        if (newWidth <= 0 || newHeight <= 0 || (newWidth == screenWidth && newHeight == screenHeight)) return
        screenWidth = newWidth
        screenHeight = newHeight
        densityDpi = resources.displayMetrics.densityDpi
        updateCaptureGeometry()

        captureHandler?.post {
            val replacement = createImageReader(captureWidth, captureHeight)
            val old = imageReader
            imageReader = replacement
            virtualDisplay?.resize(captureWidth, captureHeight, captureDensityDpi)
            virtualDisplay?.surface = replacement.surface
            old?.close()
            resetAllDetectorsNow()
        }

        var i = 0
        while (i < TOTAL_MONITOR_COUNT) {
            val lp = sensorParams[i]
            val view = sensorViews[i]
            if (lp != null && view != null) {
                clampCirclePosition(lp, sensorVisibleDiameter)
                runCatching { windowManager.updateViewLayout(view, lp) }
            }
            i++
        }
        targetParams?.let { lp ->
            val visibleDiameter = max(mmToPx(5f), dp(12))
            clampCirclePosition(lp, visibleDiameter)
            targetView?.let { runCatching { windowManager.updateViewLayout(it, lp) } }
        }
        menuButtonParams?.let { lp ->
            clampPosition(lp, min(lp.width, lp.height))
            menuButton?.let { runCatching { windowManager.updateViewLayout(it, lp) } }
        }
        menuPanelParams?.let { lp ->
            val panel = menuPanel ?: return@let
            val availableWidth = max(screenWidth - dp(20), 1)
            val availableHeight = max(screenHeight - dp(20), 1)
            lp.width = min(dp(430), availableWidth).coerceAtLeast(min(dp(230), availableWidth))
            lp.height = min(dp(650), availableHeight).coerceAtLeast(min(dp(220), availableHeight))
            clampMenuPosition(lp)
            runCatching { windowManager.updateViewLayout(panel, lp) }
        }
        applyGroupVisibility()
    }

    private fun currentScreenBounds(): Rect = if (Build.VERSION.SDK_INT >= 30) {
        windowManager.currentWindowMetrics.bounds
    } else {
        @Suppress("DEPRECATION")
        val point = android.graphics.Point().also { windowManager.defaultDisplay.getRealSize(it) }
        Rect(0, 0, point.x, point.y)
    }

    private fun clampPosition(params: WindowManager.LayoutParams, @Suppress("UNUSED_PARAMETER") visibleDiameter: Int) {
        val w = max(params.width, 1)
        val h = max(params.height, 1)
        params.x = params.x.coerceIn(0, max(screenWidth - w, 0))
        params.y = params.y.coerceIn(0, max(screenHeight - h, 0))
    }

    private fun clampCirclePosition(params: WindowManager.LayoutParams, visibleDiameter: Int) {
        val halfWindowW = max(params.width, 1) / 2f
        val halfWindowH = max(params.height, 1) / 2f
        val radius = max(visibleDiameter, 1) / 2f
        val centerX = (params.x + halfWindowW).coerceIn(radius, max(screenWidth - radius, radius))
        val centerY = (params.y + halfWindowH).coerceIn(radius, max(screenHeight - radius, radius))
        params.x = (centerX - halfWindowW).roundToInt()
        params.y = (centerY - halfWindowH).roundToInt()
    }

    private fun clampMenuPosition(params: WindowManager.LayoutParams) {
        params.x = params.x.coerceIn(0, max(screenWidth - params.width, 0))
        params.y = params.y.coerceIn(0, max(screenHeight - params.height, 0))
    }

    private fun overlayParams(width: Int, height: Int) = WindowManager.LayoutParams(
        width,
        height,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        baseOverlayFlags(),
        PixelFormat.TRANSLUCENT,
    ).apply { gravity = Gravity.TOP or Gravity.START }

    private fun baseOverlayFlags(): Int =
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN

    private fun actionCard(title: String, subtitle: String, action: () -> Unit): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(8), dp(14), dp(8))
            background = roundedBackground(Color.rgb(239, 236, 252), Color.rgb(129, 110, 202), 14f)
            isClickable = true
            isFocusable = true
            addView(TextView(this@ScreenCaptureService).apply {
                text = title
                textSize = 15f
                setTextColor(Color.rgb(56, 43, 120))
            }, matchWrap())
            addView(TextView(this@ScreenCaptureService).apply {
                text = subtitle
                textSize = 11f
                setTextColor(Color.rgb(76, 70, 96))
            }, matchWrap())
            setOnClickListener { action() }
        }

    private fun menuButton(textValue: String, action: () -> Unit): Button = Button(this).apply {
        text = textValue
        textSize = 13f
        isAllCaps = false
        setOnClickListener { action() }
    }

    private fun matchWrap(
        height: Int = LinearLayout.LayoutParams.WRAP_CONTENT,
        danger: Boolean = false,
    ): LinearLayout.LayoutParams {
        @Suppress("UNUSED_VARIABLE") val ignored = danger
        return LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, height).apply { bottomMargin = dp(5) }
    }

    private fun roundedBackground(fill: Int, stroke: Int, radiusDp: Float): GradientDrawable = GradientDrawable().apply {
        setColor(fill)
        cornerRadius = dp(radiusDp).toFloat()
        setStroke(dp(1), stroke)
    }

    private fun mmToPx(mm: Float): Int {
        val metrics = resources.displayMetrics
        val x = metrics.xdpi.takeIf { it.isFinite() && it in 100f..1000f } ?: metrics.densityDpi.toFloat()
        val y = metrics.ydpi.takeIf { it.isFinite() && it in 100f..1000f } ?: metrics.densityDpi.toFloat()
        val dpi = (x + y) / 2f
        return (mm * dpi / 25.4f).roundToInt()
    }

    private fun flatSensorIndex(group: Int, local: Int): Int = group * MONITORS_PER_GROUP + local
    private fun groupBaseIndex(group: Int): Int = group * MONITORS_PER_GROUP

    private fun sensorKeyX(group: Int, local: Int): String = if (group == 0) {
        when (local) {
            0 -> KEY_SENSOR_X
            1 -> KEY_SENSOR_2_X
            else -> KEY_SENSOR_3_X
        }
    } else {
        "sensor_g${group + 1}_${local + 1}_x"
    }

    private fun sensorKeyY(group: Int, local: Int): String = if (group == 0) {
        when (local) {
            0 -> KEY_SENSOR_Y
            1 -> KEY_SENSOR_2_Y
            else -> KEY_SENSOR_3_Y
        }
    } else {
        "sensor_g${group + 1}_${local + 1}_y"
    }

    private fun showMessage(message: String) {
        mainHandler.post { Toast.makeText(this, message, Toast.LENGTH_SHORT).show() }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "PixelTrigger V5", NotificationManager.IMPORTANCE_LOW),
            )
        }
    }

    private fun buildNotification(): Notification = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(android.R.drawable.ic_menu_view)
        .setContentTitle("PixelTrigger V5")
        .setContentText("PixelProbe + Shoulder R/L • shared capture")
        .setOngoing(true)
        .build()

    private fun shutdownCompletely() {
        closeMenu()
        runCatching {
            startService(Intent(this, ShoulderCaptureService::class.java).apply { action = ShoulderCaptureService.ACTION_STOP })
        }
        stopSelf()
    }

    override fun onDestroy() {
        mainHandler.removeCallbacksAndMessages(null)
        (getSystemService(DISPLAY_SERVICE) as DisplayManager).unregisterDisplayListener(displayListener)
        closeMenu()
        var i = 0
        while (i < TOTAL_MONITOR_COUNT) {
            sensorViews[i]?.let { runCatching { windowManager.removeView(it) } }
            i++
        }
        listOf(targetView, menuButton).forEach { view ->
            if (view != null) runCatching { windowManager.removeView(view) }
        }
        virtualDisplay?.release()
        imageReader?.close()
        mediaProjection?.stop()
        captureThread?.quitSafely()
        tapEngine.disconnect()
        wakeLock?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()
    private fun dp(value: Float): Int = (value * resources.displayMetrics.density).roundToInt()

    companion object {
        const val ACTION_START = "com.pixeltrigger.app.action.START"
        const val ACTION_STOP = "com.pixeltrigger.app.action.STOP"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"

        private const val CHANNEL_ID = "pixeltrigger_monitor"
        private const val NOTIFICATION_ID = 41
        private const val PREFS_NAME = "pixeltrigger_prefs"

        private const val GROUP_COUNT = 3
        private const val MONITORS_PER_GROUP = 3
        private const val TOTAL_MONITOR_COUNT = GROUP_COUNT * MONITORS_PER_GROUP
        private const val MONITOR_DIAMETER_MM = 0.3f
        private const val CAPTURE_SCALE = 0.5f
        private const val DISPLAY_REFRESH_DEBOUNCE_MS = 16L
        private const val ENGINE_HOLD_MS = 2_000L

        private const val KEY_ACTIVE_GROUP = "active_monitor_group"
        private const val KEY_SENSOR_X = "sensor_x"
        private const val KEY_SENSOR_Y = "sensor_y"
        private const val KEY_SENSOR_2_X = "sensor_2_x"
        private const val KEY_SENSOR_2_Y = "sensor_2_y"
        private const val KEY_SENSOR_3_X = "sensor_3_x"
        private const val KEY_SENSOR_3_Y = "sensor_3_y"
        private const val KEY_TARGET_X = "target_x"
        private const val KEY_TARGET_Y = "target_y"
        private const val KEY_BUTTON_X = "button_x"
        private const val KEY_BUTTON_Y = "button_y"
        private const val KEY_MENU_X = "menu_x"
        private const val KEY_MENU_Y = "menu_y"
        private const val KEY_CIRCLES_VISIBLE = "circles_visible"
        private const val KEY_WHITE_REARM = "white_rearm_enabled"
        private const val KEY_REARM_DELAY_ENABLED = "rearm_delay_enabled"
        private const val KEY_REARM_SECONDS = "rearm_seconds"
    }
}
