package com.domanovsky.roadlimit

import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.net.Uri
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.domanovsky.roadlimit.location.SpeedLimitService

class MainActivity : Activity() {
    private lateinit var limitValue: TextView
    private lateinit var limitUnit: TextView
    private lateinit var status: TextView
    private lateinit var currentSpeed: TextView
    private lateinit var detail: TextView
    private lateinit var actionButton: Button
    private var serviceStarted = false

    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != SpeedLimitService.ACTION_STATE) return
            limitValue.text = intent.getStringExtra(SpeedLimitService.EXTRA_LIMIT) ?: "—"
            limitUnit.text = when (intent.getStringExtra(SpeedLimitService.EXTRA_UNIT)) {
                "км/ч" -> "км\n―\nч"
                "mph" -> "mi\n―\nh"
                else -> ""
            }
            status.text = intent.getStringExtra(SpeedLimitService.EXTRA_STATUS) ?: "Ожидание"
            currentSpeed.text = "Текущая скорость: ${intent.getStringExtra(SpeedLimitService.EXTRA_SPEED) ?: "—"}"
            detail.text = intent.getStringExtra(SpeedLimitService.EXTRA_DETAIL).orEmpty()
            val known = intent.getBooleanExtra(SpeedLimitService.EXTRA_KNOWN, false)
            limitValue.setTextColor(if (known) 0xFFFFFFFF.toInt() else 0xFFB0BEC5.toInt())
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildScreen()
        serviceStarted = getSharedPreferences(SpeedLimitService.PREFERENCES, MODE_PRIVATE)
            .getBoolean(SpeedLimitService.KEY_RUNNING, false)
        updateButton()
    }

    private fun buildScreen() {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(28, 36, 28, 28)
            setBackgroundColor(0xFF101820.toInt())
        }
        val title = TextView(this).apply {
            text = "ОГРАНИЧЕНИЕ СКОРОСТИ"
            textSize = 17f
            setTextColor(0xFFFFFFFF.toInt())
            gravity = Gravity.CENTER
        }
        limitValue = TextView(this).apply {
            text = "—"
            textSize = 104f
            setTextColor(0xFFB0BEC5.toInt())
            gravity = Gravity.CENTER
        }
        limitUnit = TextView(this).apply {
            textSize = 16f
            setTextColor(0xFFFFFFFF.toInt())
            gravity = Gravity.CENTER
            setLineSpacing(0f, 0.75f)
        }
        status = TextView(this).apply {
            text = "Запустите отслеживание"
            textSize = 16f
            setTextColor(0xFFCFD8DC.toInt())
            gravity = Gravity.CENTER
            setPadding(0, 24, 0, 12)
        }
        currentSpeed = TextView(this).apply {
            text = "Текущая скорость: —"
            textSize = 16f
            setTextColor(0xFF90A4AE.toInt())
            gravity = Gravity.CENTER
        }
        detail = TextView(this).apply {
            textSize = 14f
            setTextColor(0xFF90A4AE.toInt())
            gravity = Gravity.CENTER
            setPadding(0, 8, 0, 16)
        }
        actionButton = Button(this).apply {
            setOnClickListener { onTrackingButtonClicked() }
        }
        val attribution = TextView(this).apply {
            text = "© 2ГИС • dev.2gis.ru\nGPS-координаты передаются в 2ГИС для определения дороги и ограничения скорости."
            textSize = 11f
            setTextColor(0xFF78909C.toInt())
            gravity = Gravity.CENTER
            setPadding(0, 24, 0, 0)
            setOnClickListener {
                runCatching {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/hvdwolf/SpeedAlert_Databases")))
                }
            }
        }
        content.addView(title)
        content.addView(limitValue, LinearLayout.LayoutParams(-1, -2).apply { topMargin = 24 })
        content.addView(limitUnit)
        content.addView(status)
        content.addView(currentSpeed)
        content.addView(detail)
        content.addView(actionButton, LinearLayout.LayoutParams(-1, -2).apply { topMargin = 18 })
        content.addView(attribution)
        setContentView(content)
    }

    private fun onTrackingButtonClicked() {
        if (serviceStarted) {
            stopService(Intent(this, SpeedLimitService::class.java))
            serviceStarted = false
            updateButton()
            limitValue.text = "—"
            limitUnit.text = ""
            status.text = "Отслеживание остановлено"
            currentSpeed.text = "Текущая скорость: —"
            detail.text = ""
            return
        }
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            val permissions = mutableListOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            )
            if (Build.VERSION.SDK_INT >= 33) permissions += Manifest.permission.POST_NOTIFICATIONS
            requestPermissions(permissions.toTypedArray(), REQUEST_PERMISSIONS)
            return
        }
        startTrackingService()
    }

    private fun startTrackingService() {
        val intent = Intent(this, SpeedLimitService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent) else startService(intent)
        serviceStarted = true
        updateButton()
        status.text = "Запускаем GPS…"
    }

    private fun updateButton() {
        actionButton.text = if (serviceStarted) "Остановить отслеживание" else "Начать отслеживание"
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_PERMISSIONS) return
        val fineGranted = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (fineGranted) startTrackingService()
        else status.text = "Для определения дороги разрешите точную геолокацию"
    }

    @Suppress("DEPRECATION")
    override fun onStart() {
        super.onStart()
        val filter = IntentFilter(SpeedLimitService.ACTION_STATE)
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(stateReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        else registerReceiver(stateReceiver, filter)
    }

    override fun onStop() {
        try {
            unregisterReceiver(stateReceiver)
        } catch (_: IllegalArgumentException) {
        }
        super.onStop()
    }

    companion object {
        private const val REQUEST_PERMISSIONS = 100
    }
}
