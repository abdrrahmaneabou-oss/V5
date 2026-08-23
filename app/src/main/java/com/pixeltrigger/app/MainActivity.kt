package com.pixeltrigger.app

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import rikka.shizuku.Shizuku

class MainActivity : AppCompatActivity() {
    private lateinit var overlayStatus: TextView
    private lateinit var shizukuStatus: TextView
    private lateinit var startButton: Button

    private val projectionManager by lazy {
        getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
    }

    private val projectionLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val data = result.data
        if (result.resultCode == Activity.RESULT_OK && data != null) {
            val serviceIntent = Intent(this, ScreenCaptureService::class.java).apply {
                action = ScreenCaptureService.ACTION_START
                putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, result.resultCode)
                putExtra(ScreenCaptureService.EXTRA_RESULT_DATA, data)
            }
            ContextCompat.startForegroundService(this, serviceIntent)
        }
    }

    private val permissionListener = Shizuku.OnRequestPermissionResultListener { _, _ -> updateReadiness() }
    private val binderListener = Shizuku.OnBinderReceivedListener { updateReadiness() }
    private val binderDeadListener = Shizuku.OnBinderDeadListener { updateReadiness() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Shizuku.addRequestPermissionResultListener(permissionListener)
        Shizuku.addBinderReceivedListener(binderListener)
        Shizuku.addBinderDeadListener(binderDeadListener)
        setContentView(buildContent())
        requestNotificationPermissionIfNeeded()
    }

    override fun onResume() {
        super.onResume()
        updateReadiness()
    }

    override fun onDestroy() {
        Shizuku.removeRequestPermissionResultListener(permissionListener)
        Shizuku.removeBinderReceivedListener(binderListener)
        Shizuku.removeBinderDeadListener(binderDeadListener)
        super.onDestroy()
    }

    private fun buildContent(): LinearLayout {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(24), dp(30), dp(24), dp(24))
            setBackgroundColor(Color.rgb(18, 18, 24))
        }
        root.addView(TextView(this).apply {
            text = "PixelTrigger v3 — Shizuku / No Root"
            textSize = 25f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        }, matchWrap(dp(62)))
        root.addView(TextView(this).apply {
            text = "محرك الأبيض والتسليح = Baseline v2.12.\nمحرك الضغط الجديد يرفض Root وAccessibility كمسار أساسي."
            textSize = 15f
            setTextColor(Color.rgb(195, 195, 210))
            gravity = Gravity.CENTER
        }, matchWrap(dp(92)))

        overlayStatus = requirement(root, "الظهور فوق التطبيقات") { requestOverlayPermission() }
        shizukuStatus = requirement(root, "Shizuku (ADB / UID 2000 فقط)") { requestShizukuPermission() }

        startButton = Button(this).apply {
            text = "بدء المراقبة"
            textSize = 18f
            setOnClickListener { beginMonitoring() }
        }
        root.addView(startButton, matchWrap(dp(58)))
        root.addView(TextView(this).apply {
            text = "مهم: إذا لم نستطع إثبات دعم اللمس المتزامن على الجهاز، سيمنع PixelTrigger الضغطة بدل المخاطرة بإلغاء لمس اللاعب."
            textSize = 13f
            setTextColor(Color.rgb(180, 180, 195))
            gravity = Gravity.CENTER
            setPadding(dp(4), dp(18), dp(4), 0)
        }, matchWrap(LinearLayout.LayoutParams.WRAP_CONTENT))
        return root
    }

    private fun requirement(root: LinearLayout, title: String, action: () -> Unit): TextView {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(6), 0, dp(6))
        }
        val button = Button(this).apply {
            text = title
            setOnClickListener { action() }
        }
        val status = TextView(this).apply {
            text = "مطلوب"
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(255, 150, 150))
        }
        row.addView(button, LinearLayout.LayoutParams(0, dp(52), 1f))
        row.addView(status, LinearLayout.LayoutParams(dp(110), dp(52)))
        root.addView(row, matchWrap(dp(64)))
        return status
    }

    private fun updateReadiness() {
        val overlayReady = Settings.canDrawOverlays(this)
        overlayStatus.text = if (overlayReady) "جاهز" else "مطلوب"
        overlayStatus.setTextColor(if (overlayReady) Color.rgb(90, 230, 145) else Color.rgb(255, 140, 140))

        val shizukuText = when {
            !Shizuku.pingBinder() -> "شغّل Shizuku"
            runCatching { Shizuku.getUid() }.getOrDefault(-1) != 2000 -> "Root مرفوض"
            Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED -> "امنح الإذن"
            else -> "جاهز"
        }
        shizukuStatus.text = shizukuText
        shizukuStatus.setTextColor(if (shizukuText == "جاهز") Color.rgb(90, 230, 145) else Color.rgb(255, 170, 95))
        startButton.isEnabled = overlayReady && shizukuText == "جاهز"
    }

    private fun beginMonitoring() {
        if (!Settings.canDrawOverlays(this)) return requestOverlayPermission()
        if (!Shizuku.pingBinder()) {
            Toast.makeText(this, "شغّل Shizuku أولًا", Toast.LENGTH_SHORT).show()
            return
        }
        val uid = runCatching { Shizuku.getUid() }.getOrDefault(-1)
        if (uid != 2000) {
            Toast.makeText(this, "PixelTrigger v3 لا يستخدم Root. شغّل Shizuku عبر ADB/Wireless debugging.", Toast.LENGTH_LONG).show()
            return
        }
        if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
            requestShizukuPermission()
            return
        }
        projectionLauncher.launch(projectionManager.createScreenCaptureIntent())
    }

    private fun requestShizukuPermission() {
        if (!Shizuku.pingBinder()) {
            Toast.makeText(this, "افتح Shizuku وشغّله عبر Wireless debugging/ADB", Toast.LENGTH_LONG).show()
            return
        }
        if (runCatching { Shizuku.getUid() }.getOrDefault(-1) != 2000) {
            Toast.makeText(this, "Root غير مسموح في هذا الإصدار", Toast.LENGTH_LONG).show()
            return
        }
        if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) Shizuku.requestPermission(4101)
    }

    private fun requestOverlayPermission() {
        startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33 && ActivityCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 201)
        }
    }

    private fun matchWrap(height: Int) = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, height)
    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
