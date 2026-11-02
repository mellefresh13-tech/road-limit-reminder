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
import com.domanovsky.roadlimit.data.TwoGisEdge
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

    // 2GIS response geometry is kept only in memory during the active tracking session.
    private var matchedEdges: List<TwoGisEdge> = emptyList()
    private var lastFix: Location? = null
    private var latestLimit: SpeedLimit? = null
    private var latestLimitStatus: String? = null
    private var latestLimitDetail: String? = null
    private var requestInFlight = false
    private var nextRetryAt = 0L
    private var consecutiveFailures = 0

    private val staleFixChecker = object : Runnable {
        override fun run() {
            val fix = lastFix
            if (fix != null && !isFresh(fix)) {
                clearMatchedRoadData()
                publish("Сигнал GPS потерян — ограничение скрыто", null, null, null)
            }
            mainHandler.postDelayed(this, STALE_FIX_CHECK_INTERVAL_MILLIS)
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
            mainHandler.postDelayed(staleFixChecker, STALE_FIX_CHECK_INTERVAL_MILLIS)
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
            clearMatchedRoadData()
            publish("Сигнал GPS устарел — ограничение скрыто", null, currentSpeed(location), null)
            return
        }

        appendGpsSample(location)

        if (BuildConfig.DGIS_API_KEY.isBlank()) {
            if (!updateFromMatchedEdges(location)) {
                publish(
                    "Не настроен ключ API 2ГИС. Добавьте секрет DGIS_API_KEY в GitHub Actions.",
                    null,
                    currentSpeed(location),
                    "Для получения ограничений нужен активный ключ Map Matching API"
                )
            }
            return
        }

        // Re-use the current 2GIS route in memory. No API request is sent while
        // the latest GPS fix still belongs to one of its road edges.
        if (updateFromMatchedEdges(location)) return

        clearDisplayedLimit()
        requestMapMatching()
        if (!requestInFlight) {
            val status = if (System.currentTimeMillis() < nextRetryAt) {
                "Ожидаем повторного запроса 2ГИС…"
            } else {
                "Определяем дорожный участок через 2ГИС…"
            }
            publish(status, null, currentSpeed(location), "GPS ±${location.accuracy.toInt()} м")
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
                clearMatchedRoadData()
            }
        }
        gpsSamples.addLast(Location(location))
        while (gpsSamples.size > MAX_QUERY_POINTS) gpsSamples.removeFirst()
    }

    private fun requestMapMatching() {
        if (requestInFlight || System.currentTimeMillis() < nextRetryAt) return
        if (gpsSamples.size < MIN_QUERY_POINTS) return

        val samples = gpsSamples.toList().takeLast(MAX_QUERY_POINTS)
        val newestFix = lastFix ?: return
        val newestSample = samples.lastOrNull() ?: return
        if (newestFix.accuracy > MAX_API_ACCURACY_METRES ||
            newestFix.time - newestSample.time > MAX_SAMPLE_AGE_FOR_REQUEST_MILLIS
        ) return
        if (samples.last().time - samples.first().time > MAX_QUERY_SPAN_MILLIS) return

        val queryPoints = samples.map { it.toTwoGisPoint() }
        requestInFlight = true

        networkExecutor.execute {
            try {
                val result = TwoGisMapMatchingClient(BuildConfig.DGIS_API_KEY).match(queryPoints)
                mainHandler.post {
                    requestInFlight = false
                    consecutiveFailures = 0
                    nextRetryAt = System.currentTimeMillis() + SUCCESSFUL_REQUEST_COOLDOWN_MILLIS
                    matchedEdges = result.edges

                    val fix = lastFix
                    val matched = fix?.takeIf(::isFresh)?.let { matchCurrentFix(it, result) }
                    if (fix != null && matched != null) {
                        publishMatched(matched, fix)
                    } else {
                        clearDisplayedLimit()
                        publish(
                            "2ГИС получил GPS-трек, но текущая дорога пока не подтверждена",
                            null,
                            fix?.let(::currentSpeed),
                            "Участков в ответе: ${result.edges.size}"
                        )
                    }
                }
            } catch (error: Exception) {
                mainHandler.post {
                    requestInFlight = false
                    consecutiveFailures++
                    val backoff = min(
                        MAX_RETRY_DELAY_MILLIS,
                        INITIAL_RETRY_DELAY_MILLIS * (1L shl min(consecutiveFailures - 1, MAX_BACKOFF_EXPONENT))
                    )
                    nextRetryAt = System.currentTimeMillis() + backoff
                    val fix = lastFix
                    if (fix == null || !isFresh(fix) || !updateFromMatchedEdges(fix)) {
                        publish(
                            mapMatchingError(error),
                            null,
                            fix?.let(::currentSpeed),
                            "Повтор через ${backoff / 1000} с"
                        )
                    }
                }
            }
        }
    }

    private fun matchCurrentFix(
        location: Location,
        result: TwoGisMatchResult? = null
    ): com.domanovsky.roadlimit.data.TwoGisEdgeMatch? {
        val edges = result?.edges ?: matchedEdges
        if (edges.isEmpty()) return null
        val fix = location.toFix()
        return TwoGisEdgeMatcher.match(fix, edges)
    }

    private fun updateFromMatchedEdges(location: Location): Boolean {
        if (!isFresh(location) || matchedEdges.isEmpty()) return false
        val match = matchCurrentFix(location) ?: return false
        publishMatched(match, location)
        return true
    }

    private fun publishMatched(
        match: com.domanovsky.roadlimit.data.TwoGisEdgeMatch,
        location: Location
    ) {
        latestLimit = match.speedLimit
        latestLimitStatus = if (latestLimit != null) {
            "Ограничение по данным 2ГИС"
        } else {
            "2ГИС определил дорогу, но ограничение не указано"
        }
        latestLimitDetail = "Сегмент 2ГИС ${match.edge.id}"
        publish(latestLimitStatus!!, latestLimit, currentSpeed(location), latestLimitDetail)
    }

    private fun clearDisplayedLimit() {
        latestLimit = null
        latestLimitStatus = null
        latestLimitDetail = null
    }

    private fun clearMatchedRoadData() {
        matchedEdges = emptyList()
        clearDisplayedLimit()
        gpsSamples.clear()
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

    private fun currentSpeed(location: Location): Float? =
        location.speed.takeIf { location.hasSpeed() && it.isFinite() }

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
        clearMatchedRoadData()
        requestInFlight = false
        getSharedPreferences(PREFERENCES, MODE_PRIVATE).edit().putBoolean(KEY_RUNNING, false).apply()
        networkExecutor.shutdownNow()
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    override fun onProviderDisabled(provider: String) {
        clearMatchedRoadData()
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
        clearMatchedRoadData()
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
        private const val STALE_FIX_CHECK_INTERVAL_MILLIS = 5_000L
        private const val MAX_API_ACCURACY_METRES = 80f
        private const val MIN_SAMPLE_GAP_MILLIS = 1_000L
        private const val MAX_SAMPLE_GAP_MILLIS = 10_000L
        private const val MAX_SAMPLE_AGE_FOR_REQUEST_MILLIS = 2_000L
        private const val MAX_QUERY_SPAN_MILLIS = 10_000L
        private const val MIN_QUERY_POINTS = 2
        private const val MAX_QUERY_POINTS = 10
        private const val SUCCESSFUL_REQUEST_COOLDOWN_MILLIS = 2_000L
        private const val INITIAL_RETRY_DELAY_MILLIS = 5_000L
        private const val MAX_RETRY_DELAY_MILLIS = 60_000L
        private const val MAX_BACKOFF_EXPONENT = 4
    }
}
