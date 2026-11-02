package com.domanovsky.roadlimit.location

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.graphics.drawable.IconCompat
import com.domanovsky.roadlimit.MainActivity
import com.domanovsky.roadlimit.data.OverpassClient
import com.domanovsky.roadlimit.domain.LimitResolution
import com.domanovsky.roadlimit.domain.LocationFix
import com.domanovsky.roadlimit.domain.MatchResult
import com.domanovsky.roadlimit.domain.RoadLimitResolver
import com.domanovsky.roadlimit.domain.RoadMatcher
import com.domanovsky.roadlimit.domain.RoadSegment
import com.domanovsky.roadlimit.domain.SpeedLimit
import com.domanovsky.roadlimit.domain.SpeedUnit
import java.util.concurrent.Executors

class SpeedLimitService : Service(), LocationListener {
    private lateinit var locationManager: LocationManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private val networkExecutor = Executors.newSingleThreadExecutor()
    private val overpassClient = OverpassClient()
    private var roads: List<RoadSegment> = emptyList()
    private var countryCode: String? = null
    private var insideSettlement: Boolean? = null
    private var lastFix: Location? = null
    private var lastFetchLocation: Location? = null
    private var lastFetchAt = 0L
    private var fetching = false
    private var previousWayId: Long? = null
    private var cachedFallbackActive = false

    private val staleFixChecker = object : Runnable {
        override fun run() {
            val fix = lastFix
            if (fix != null && !isFresh(fix)) {
                publish("Сигнал GPS потерян — лимит скрыт", null, null, null)
            }
            mainHandler.postDelayed(this, 5_000L)
        }
    }

    override fun onCreate() {
        super.onCreate()
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopTracking()
            stopSelf()
            return START_NOT_STICKY
        }
        startAsForeground()
        startTracking()
        return START_NOT_STICKY
    }

    private fun startAsForeground() {
        val notification = buildNotification("Road Limit Reminder", "Ожидание GPS", null)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun startTracking() {
        val fineGranted = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!fineGranted) {
            publish("Требуется точная геолокация", null, null, null)
            stopSelf()
            return
        }
        try {
            locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1_000L, 0f, this, Looper.getMainLooper())
            getSharedPreferences(PREFERENCES, MODE_PRIVATE).edit().putBoolean(KEY_RUNNING, true).apply()
            mainHandler.removeCallbacks(staleFixChecker)
            mainHandler.postDelayed(staleFixChecker, 5_000L)
            publish("Ожидание сигнала GPS", null, null, null)
        } catch (error: SecurityException) {
            publish("Нет разрешения на геолокацию", null, null, null)
            stopSelf()
        } catch (error: IllegalArgumentException) {
            publish("GPS недоступен на устройстве", null, null, null)
            stopSelf()
        }
    }

    override fun onLocationChanged(location: Location) {
        lastFix = location
        if (roads.isEmpty()) {
            publish("Получаем дорожные данные OSM…", null, location.speed, "GPS ±${location.accuracy.toInt()} м")
        } else {
            publishMatch(location)
        }
        val moved = lastFetchLocation?.distanceTo(location)?.let { it >= 300f } ?: true
        val expired = System.currentTimeMillis() - lastFetchAt >= 120_000L
        val neverFetched = lastFetchAt == 0L
        if (!fetching && (neverFetched || moved || expired)) fetchRoads(location)
    }

    private fun publishMatch(location: Location) {
        if (!isFresh(location)) {
            publish("Сигнал GPS устарел — лимит скрыт", null, null, null)
            return
        }
        when (val match = RoadMatcher.match(location.toFix(), roads, previousWayId)) {
            is MatchResult.Matched -> {
                previousWayId = match.road.id
                when (val resolution = RoadLimitResolver.resolve(match.road.tags, countryCode, insideSettlement)) {
                    is LimitResolution.Known -> {
                        val fromBelarusRules = resolution.limit.rawTag.startsWith("BY:") ||
                            resolution.limit.rawTag.contains("ПДД РБ")
                        publish(
                            when {
                                cachedFallbackActive -> "Сохранённые данные OSM — сеть недоступна"
                                fromBelarusRules -> "Базовое ограничение по ПДД РБ"
                                else -> "Ограничение по данным OpenStreetMap"
                            },
                            resolution.limit,
                            location.speed,
                            match.road.name ?: match.road.highway
                        )
                    }
                    is LimitResolution.Unavailable -> publish(
                        resolution.reason, null, location.speed, match.road.name ?: match.road.highway
                    )
                }
            }
            MatchResult.Ambiguous -> publish("Рядом несколько возможных дорог — уточняем положение", null, location.speed, "GPS ±${location.accuracy.toInt()} м")
            MatchResult.NoMatch -> publish("Не удалось уверенно определить дорогу", null, location.speed, "GPS ±${location.accuracy.toInt()} м")
        }
    }

    private fun fetchRoads(location: Location) {
        fetching = true
        val latitude = location.latitude
        val longitude = location.longitude
        val requestedLocation = Location(location)
        publish("Обновляем дорожные данные OSM…", null, location.speed, "GPS ±${location.accuracy.toInt()} м")
        networkExecutor.execute {
            try {
                val loaded = overpassClient.loadNearby(latitude, longitude)
                mainHandler.post {
                    roads = loaded.roads
                    countryCode = loaded.countryCode
                    insideSettlement = loaded.insideSettlement
                    cachedFallbackActive = false
                    lastFetchLocation = requestedLocation
                    lastFetchAt = System.currentTimeMillis()
                    fetching = false
                    val fix = lastFix
                    if (fix != null && isFresh(fix)) publishMatch(fix)
                    else publish("Данные OSM загружены. Ожидание актуального GPS…", null, null, null)
                }
            } catch (error: Exception) {
                mainHandler.post {
                    fetching = false
                    lastFetchAt = System.currentTimeMillis()
                    lastFetchLocation = lastFix
                    publish("Не удалось загрузить OSM. Проверьте интернет", null, null, null)
                }
            }
        }
    }

    private fun isFresh(location: Location): Boolean =
        SystemClock.elapsedRealtimeNanos() - location.elapsedRealtimeNanos <= 15_000_000_000L

    private fun Location.toFix() = LocationFix(
        latitude, longitude, accuracy, bearing, speed
    )

    private fun publish(status: String, limit: SpeedLimit.Known?, speed: Float?, detail: String?) {
        val speedText = speed?.takeIf { it.isFinite() }?.let {
            if (limit?.unit == SpeedUnit.MILES_PER_HOUR) {
                "${(it * 2.23694f).toInt()} mph"
            } else {
                "${(it * 3.6f).toInt()} км/ч"
            }
        } ?: "—"
        val stateIntent = Intent(ACTION_STATE).setPackage(packageName).apply {
            putExtra(EXTRA_LIMIT, limit?.value?.toString() ?: "—")
            putExtra(EXTRA_UNIT, limit?.unit?.label.orEmpty())
            putExtra(EXTRA_STATUS, status)
            putExtra(EXTRA_SPEED, speedText)
            putExtra(EXTRA_DETAIL, detail.orEmpty())
            putExtra(EXTRA_KNOWN, limit != null)
        }
        sendBroadcast(stateIntent)

        val title = limit?.let { "${it.value} ${it.unit.label}" } ?: "Road Limit Reminder"
        val body = if (limit != null) listOf(status, detail.orEmpty()).filter { it.isNotBlank() }.joinToString(" • ") else status
        getSystemService(NotificationManager::class.java).notify(
            NOTIFICATION_ID,
            buildNotification(title, body, limit)
        )
    }

    private fun buildNotification(title: String, text: String, limit: SpeedLimit.Known?): Notification {
        val openIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this, 1, Intent(this, SpeedLimitService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(notificationIcon(limit))
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(android.R.drawable.ic_media_pause, "Остановить", stopIntent)
            .build()
    }

    /**
     * The status-bar icon is an alpha-only speed sign. Android applies its own
     * notification tint, so the circle and digits remain legible in the tray.
     */
    private fun notificationIcon(limit: SpeedLimit.Known?): IconCompat {
        if (limit == null) {
            return IconCompat.createWithResource(this, android.R.drawable.ic_menu_mylocation)
        }
        val size = 96
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = 7f
        }
        canvas.drawCircle(48f, 48f, 40f, paint)
        paint.style = Paint.Style.FILL
        paint.textAlign = Paint.Align.CENTER
        paint.typeface = Typeface.create("sans-serif", Typeface.BOLD)
        paint.textSize = if (limit.value >= 100) 27f else 34f
        canvas.drawText(limit.value.toString(), 48f, 60f, paint)
        return IconCompat.createWithBitmap(bitmap)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Отслеживание ограничения скорости", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun stopTracking() {
        mainHandler.removeCallbacks(staleFixChecker)
        try {
            locationManager.removeUpdates(this)
        } catch (_: SecurityException) {
        }
        getSharedPreferences(PREFERENCES, MODE_PRIVATE).edit().putBoolean(KEY_RUNNING, false).apply()
        networkExecutor.shutdownNow()
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    override fun onProviderDisabled(provider: String) {
        publish("GPS отключён", null, null, null)
    }

    override fun onProviderEnabled(provider: String) {
        publish("GPS включён. Ожидание координат…", null, null, null)
    }

    @Deprecated("Deprecated by Android")
    override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) = Unit

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        mainHandler.removeCallbacks(staleFixChecker)
        try {
            locationManager.removeUpdates(this)
        } catch (_: Exception) {
        }
        getSharedPreferences(PREFERENCES, MODE_PRIVATE).edit().putBoolean(KEY_RUNNING, false).apply()
        networkExecutor.shutdownNow()
        super.onDestroy()
    }

    companion object {
        const val ACTION_STOP = "com.domanovsky.roadlimit.STOP"
        const val ACTION_STATE = "com.domanovsky.roadlimit.STATE"
        const val EXTRA_LIMIT = "limit"
        const val EXTRA_UNIT = "unit"
        const val EXTRA_STATUS = "status"
        const val EXTRA_SPEED = "speed"
        const val EXTRA_DETAIL = "detail"
        const val EXTRA_KNOWN = "known"
        const val PREFERENCES = "tracking_state"
        const val KEY_RUNNING = "running"
        private const val CHANNEL_ID = "speed_limit_tracking"
        private const val NOTIFICATION_ID = 71
    }
}
