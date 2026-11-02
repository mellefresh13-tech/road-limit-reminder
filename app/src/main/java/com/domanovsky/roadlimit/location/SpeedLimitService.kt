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
import com.domanovsky.roadlimit.BuildConfig
import com.domanovsky.roadlimit.MainActivity
import com.domanovsky.roadlimit.data.TwoGisEdgeMatcher
import com.domanovsky.roadlimit.data.TwoGisGpsPoint
import com.domanovsky.roadlimit.data.TwoGisMapMatchingClient
import com.domanovsky.roadlimit.data.TwoGisMatchResult
import com.domanovsky.roadlimit.domain.LocationFix
import com.domanovsky.roadlimit.domain.SpeedLimit
import com.domanovsky.roadlimit.domain.SpeedUnit
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.ArrayDeque
import java.util.concurrent.Executors
import kotlin.math.min

class SpeedLimitService : Service(), LocationListener {
    private lateinit var locationManager: LocationManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private val networkExecutor = Executors.newSingleThreadExecutor()
    private val gpsSamples = ArrayDeque<Location>()

    private var lastFix: Location? = null
    private var latestLimit: SpeedLimit? = null
    private var latestLimitStatus: String? = null
    private var latestLimitDetail: String? = null
    private var latestMatchedLocation: Location? = null
    private var latestMatchAtMillis = 0L
    private var requestInFlight = false
    private var hasSuccessfulMatch = false
    private var nextRetryAt = 0L
    private var consecutiveFailures = 0

    private val staleFixChecker = object : Runnable {
        override fun run() {
            val fix = lastFix
            if (fix != null && !isFresh(fix)) {
                clearDisplayedLimit()
                publish("Сигнал GPS потерян — ограничение скрыто", null, null, null)
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
            locationManager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                GPS_INTERVAL_MILLIS,
                0f,
                this,
                Looper.getMainLooper()
            )
            getSharedPreferences(PREFERENCES, MODE_PRIVATE).edit().putBoolean(KEY_RUNNING, true).apply()
            mainHandler.removeCallbacks(staleFixChecker)
            mainHandler.postDelayed(staleFixChecker, 5_000L)
            publish("Ожидание GPS. Подключение к 2ГИС…", null, null, null)
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
        if (!isFresh(location)) {
            clearDisplayedLimit()
            publish("Сигнал GPS устарел — ограничение скрыто", null, location.speed, null)
            return
        }

        expireDisplayedLimit(location)
        appendGpsSample(location)

        if (BuildConfig.DGIS_API_KEY.isBlank()) {
            if (latestLimit == null) {
                publish(
                    "Не настроен ключ API 2ГИС. Добавьте секрет DGIS_API_KEY в GitHub Actions.",
                    null,
                    location.speed,
                    "Для получения ограничений нужен активный ключ Map Matching API"
                )
            } else {
                publish(latestLimitStatus ?: "Данные 2ГИС", latestLimit, location.speed, latestLimitDetail)
            }
            return
        }

        requestMapMatching()
        if (!requestInFlight && latestLimit == null) {
            publish(
                if (nextRetryAt > System.currentTimeMillis()) "Ожидаем повторного запроса 2ГИС…" else "Определяем дорожный участок через 2ГИС…",
                null,
                location.speed,
                "GPS ±${location.accuracy.toInt()} м"
            )
        } else if (latestLimit != null && latestMatchedLocation != null) {
            publish(latestLimitStatus ?: "Данные 2ГИС", latestLimit, location.speed, latestLimitDetail)
        }
    }

    private fun appendGpsSample(location: Location) {
        if (!location.accuracy.isFinite() || location.accuracy > MAX_API_ACCURACY_METRES) return
        val previous = gpsSamples.peekLast()
        if (previous != null) {
            val gap = location.time - previous.time
            if (gap < MIN_SAMPLE_GAP_MILLIS) return
            if (gap > MAX_SAMPLE_GAP_MILLIS) {
                gpsSamples.clear()
                hasSuccessfulMatch = false
            }
        }
        gpsSamples.addLast(Location(location))
        while (gpsSamples.size > MAX_QUERY_POINTS) gpsSamples.removeFirst()
    }

    private fun requestMapMatching() {
        if (requestInFlight || System.currentTimeMillis() < nextRetryAt) return
        val requiredPoints = if (hasSuccessfulMatch) REFRESH_QUERY_POINTS else INITIAL_QUERY_POINTS
        if (gpsSamples.size < requiredPoints) return

        val samples = gpsSamples.toList().takeLast(MAX_QUERY_POINTS)
        if (samples.last().time - samples.first().time > MAX_QUERY_SPAN_MILLIS) {
            while (gpsSamples.size > 1) gpsSamples.removeFirst()
            return
        }
        val queryPoints = samples.map { it.toTwoGisPoint() }
        requestInFlight = true

        networkExecutor.execute {
            try {
                val result = TwoGisMapMatchingClient(BuildConfig.DGIS_API_KEY).match(queryPoints)
                mainHandler.post {
                    requestInFlight = false
                    consecutiveFailures = 0
                    nextRetryAt = System.currentTimeMillis() + SUCCESSFUL_REQUEST_COOLDOWN_MILLIS
                    hasSuccessfulMatch = true

                    val latestSubmitted = samples.last()
                    val newerSamples = gpsSamples.filter { it.time > latestSubmitted.time }
                    gpsSamples.clear()
                    gpsSamples.addLast(latestSubmitted)
                    newerSamples.takeLast(MAX_QUERY_POINTS - 1).forEach(gpsSamples::addLast)

                    val matchedIndex = result.matchedPointIndex
                    val matchedSample = matchedIndex
                        ?.takeIf { it in samples.indices }
                        ?.let(samples::get)
                        ?: latestSubmitted
                    applyLiveResult(result, matchedSample)
                    if (gpsSamples.size >= REFRESH_QUERY_POINTS) requestMapMatching()
                }
            } catch (error: Exception) {
                mainHandler.post {
                    requestInFlight = false
                    consecutiveFailures++
                    val backoff = min(
                        MAX_RETRY_DELAY_MILLIS,
                        INITIAL_RETRY_DELAY_MILLIS * (1L shl min(consecutiveFailures - 1, 4))
                    )
                    nextRetryAt = System.currentTimeMillis() + backoff
                    val fix = lastFix
                    expireDisplayedLimit(fix)
                    if (latestLimit == null) {
                        publish(
                            mapMatchingError(error),
                            null,
                            fix?.speed,
                            "Повтор через ${backoff / 1000} с"
                        )
                    } else if (fix != null) {
                        publish(latestLimitStatus ?: "Данные 2ГИС", latestLimit, fix.speed, latestLimitDetail)
                    }
                }
            }
        }
    }

    private fun applyLiveResult(result: TwoGisMatchResult, submittedLocation: Location) {
        val edge = result.currentEdgeId?.let { id -> result.edges.firstOrNull { it.id == id } }
        val point = result.matchedPoint
        val match = if (edge != null && point != null) {
            val matchedFix = LocationFix(
                latitude = point.latitude,
                longitude = point.longitude,
                accuracyMetres = 5f,
                bearingDegrees = Float.NaN,
                speedMetresPerSecond = 0f
            )
            TwoGisEdgeMatcher.match(matchedFix, listOf(edge))
        } else {
            null
        }

        latestLimit = match?.speedLimit
        latestLimitStatus = if (latestLimit != null) "Ограничение по данным 2ГИС" else "2ГИС не вернул лимит для этого участка"
        latestLimitDetail = edge?.let { "Сегмент 2ГИС ${it.id}" }
        latestMatchAtMillis = System.currentTimeMillis()
        latestMatchedLocation = point?.let {
            Location("2GIS").apply {
                latitude = it.latitude
                longitude = it.longitude
                time = submittedLocation.time
            }
        }
        val speed = lastFix?.takeIf(::isFresh)?.speed ?: submittedLocation.speed
        publish(latestLimitStatus!!, latestLimit, speed, latestLimitDetail)
    }

    private fun expireDisplayedLimit(location: Location?) {
        if (latestLimit == null) return
        val matched = latestMatchedLocation
        val age = System.currentTimeMillis() - latestMatchAtMillis
        val movedTooFar = location == null || matched == null ||
            matched.distanceTo(location) > MAX_DISPLAY_DISTANCE_METRES
        if (age > MAX_DISPLAY_AGE_MILLIS || movedTooFar) {
            clearDisplayedLimit()
        }
    }

    private fun clearDisplayedLimit() {
        latestLimit = null
        latestLimitStatus = null
        latestLimitDetail = null
        latestMatchedLocation = null
        latestMatchAtMillis = 0L
    }

    private fun Location.toFix() = LocationFix(
        latitude = latitude,
        longitude = longitude,
        accuracyMetres = accuracy,
        bearingDegrees = if (hasBearing()) bearing else Float.NaN,
        speedMetresPerSecond = if (hasSpeed()) speed else 0f
    )

    private fun Location.toTwoGisPoint() = TwoGisGpsPoint(
        latitude = latitude,
        longitude = longitude,
        utcSeconds = time / 1000L,
        speedKmh = if (hasSpeed()) speed * 3.6 else null,
        azimuthDegrees = if (hasBearing()) bearing.toDouble() else null
    )

    private fun isFresh(location: Location): Boolean =
        SystemClock.elapsedRealtimeNanos() - location.elapsedRealtimeNanos <= GPS_MAX_AGE_NANOS

    private fun mapMatchingError(error: Exception): String {
        val cause = generateSequence<Throwable>(error) { it.cause }
            .firstOrNull {
                it is SocketTimeoutException || it is UnknownHostException ||
                    it is ConnectException || (it.message?.contains("2ГИС HTTP") == true)
            }
        val message = cause?.message.orEmpty()
        return when {
            cause is SocketTimeoutException -> "2ГИС не ответил вовремя — ограничение временно недоступно"
            cause is UnknownHostException -> "Не удалось найти сервер 2ГИС"
            cause is ConnectException -> "Не удалось подключиться к 2ГИС"
            message.contains("HTTP 401") || message.contains("HTTP 403") ->
                "2ГИС отклонил API-ключ. Проверьте DGIS_API_KEY и доступ к Map Matching"
            message.contains("HTTP 429") ->
                "2ГИС ограничил частоту запросов. Повторим позже"
            message.contains("HTTP 502") || message.contains("HTTP 503") || message.contains("HTTP 504") ->
                "Сервис 2ГИС временно перегружен"
            else -> cause?.message ?: "Не удалось получить ограничение от 2ГИС"
        }
    }

    private fun publish(status: String, limit: SpeedLimit?, speed: Float?, detail: String?) {
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

    private fun buildNotification(title: String, text: String, limit: SpeedLimit?): Notification {
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

    private fun notificationIcon(limit: SpeedLimit?): IconCompat {
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
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Отслеживание ограничения скорости",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun stopTracking() {
        mainHandler.removeCallbacks(staleFixChecker)
        try {
            locationManager.removeUpdates(this)
        } catch (_: SecurityException) {
        }
        gpsSamples.clear()
        requestInFlight = false
        clearDisplayedLimit()
        getSharedPreferences(PREFERENCES, MODE_PRIVATE).edit().putBoolean(KEY_RUNNING, false).apply()
        networkExecutor.shutdownNow()
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    override fun onProviderDisabled(provider: String) {
        clearDisplayedLimit()
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
        private const val GPS_INTERVAL_MILLIS = 1_000L
        private const val GPS_MAX_AGE_NANOS = 15_000_000_000L
        private const val MAX_API_ACCURACY_METRES = 80f
        private const val MIN_SAMPLE_GAP_MILLIS = 1_000L
        private const val MAX_SAMPLE_GAP_MILLIS = 10_000L
        private const val MAX_QUERY_SPAN_MILLIS = 10_000L
        private const val INITIAL_QUERY_POINTS = 2
        private const val REFRESH_QUERY_POINTS = 10
        private const val MAX_QUERY_POINTS = 10
        private const val SUCCESSFUL_REQUEST_COOLDOWN_MILLIS = 1_000L
        private const val INITIAL_RETRY_DELAY_MILLIS = 5_000L
        private const val MAX_RETRY_DELAY_MILLIS = 60_000L
        private const val MAX_DISPLAY_AGE_MILLIS = 12_000L
        private const val MAX_DISPLAY_DISTANCE_METRES = 100f
    }
}
