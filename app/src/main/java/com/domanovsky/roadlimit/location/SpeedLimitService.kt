package com.domanovsky.roadlimit.location

import android.Manifest
import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.location.Geocoder
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
import com.domanovsky.roadlimit.data.OpenSpeedDatabaseManager
import com.domanovsky.roadlimit.data.SpeedDatabaseLookup
import com.domanovsky.roadlimit.domain.SpeedLimit
import com.domanovsky.roadlimit.domain.SpeedUnit
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.roundToInt

class SpeedLimitService : Service(), LocationListener {
    private lateinit var locationManager: LocationManager
    private lateinit var databases: OpenSpeedDatabaseManager
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val lookup = Executors.newSingleThreadExecutor()

    @Volatile private var lastFix: Location? = null
    private var active = false
    private var country: String? = null
    private var dbReady = false
    private var dbUpdating = false
    private var countryLookup = false
    private var roadLookup = false
    private var nextDatabaseAttemptAt = 0L
    private var lastCountryCheck = 0L
    private var lastCountryLocation: Location? = null

    private val staleCheck = object : Runnable {
        override fun run() {
            val fix = lastFix
            if (active && fix != null && !isFresh(fix)) {
                publish("Сигнал GPS потерян — ограничение скрыто", null, null, null)
            }
            main.postDelayed(this, 5_000L)
        }
    }

    override fun onCreate() {
        super.onCreate()
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        databases = OpenSpeedDatabaseManager(this)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopTracking()
            stopSelf()
            return START_NOT_STICKY
        }
        val notification = buildNotification("Road Limit Reminder", "Ожидание GPS", null)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } else startForeground(NOTIFICATION_ID, notification)
        startTracking()
        return START_NOT_STICKY
    }

    private fun startTracking() {
        if (active) return
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            publish("Требуется точная геолокация", null, null, null)
            stopSelf()
            return
        }
        active = true
        try {
            locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1_000L, 0f, this, Looper.getMainLooper())
            getSharedPreferences(PREFERENCES, MODE_PRIVATE).edit().putBoolean(KEY_RUNNING, true).apply()
            main.removeCallbacks(staleCheck)
            main.postDelayed(staleCheck, 5_000L)
            val cached = runCatching { locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER) }.getOrNull()
            if (cached != null && isFresh(cached)) onLocationChanged(cached)
            else publish("Ожидание точного сигнала GPS…", null, null, null)
        } catch (_: SecurityException) {
            active = false
            publish("Нет разрешения на геолокацию", null, null, null)
            stopSelf()
        } catch (_: IllegalArgumentException) {
            active = false
            publish("GPS недоступен на устройстве", null, null, null)
            stopSelf()
        }
    }

    override fun onLocationChanged(location: Location) {
        lastFix = Location(location)
        if (!isFresh(location)) {
            publish("Сигнал GPS устарел — ограничение скрыто", null, currentSpeed(location), null)
            return
        }
        if (country == null || shouldCheckCountry(location)) {
            resolveCountry(location)
            if (country == null) publish("Определяем страну по GPS…", null, currentSpeed(location), null)
            return
        }
        val countryCode = country ?: return
        if (!dbReady) {
            if (!dbUpdating && System.currentTimeMillis() >= nextDatabaseAttemptAt) {
                prepareDatabase(countryCode)
            }
            publish(
                if (dbUpdating) "Загружаем открытую базу дорог…" else "Локальная база дорог недоступна",
                null, currentSpeed(location), countryCode
            )
            return
        }
        if (!dbUpdating && databases.shouldRefresh(countryCode) &&
            System.currentTimeMillis() >= nextDatabaseAttemptAt
        ) {
            prepareDatabase(countryCode)
        }
        runLocalLookup()
    }

    private fun shouldCheckCountry(location: Location): Boolean {
        if (System.currentTimeMillis() - lastCountryCheck < 10 * 60 * 1000L) return false
        val previous = lastCountryLocation ?: return true
        return previous.distanceTo(location) >= 10_000f
    }

    private fun resolveCountry(location: Location) {
        if (countryLookup || System.currentTimeMillis() - lastCountryCheck < COUNTRY_RETRY_MILLIS && country == null) return
        countryLookup = true
        lastCountryCheck = System.currentTimeMillis()
        lastCountryLocation = Location(location)
        worker.execute {
            val detected = detectCountry(location)
            main.post {
                countryLookup = false
                if (!active) return@post
                if (detected.isNullOrBlank()) {
                    if (country == null) publish(
                        "Не удалось определить страну",
                        null, lastFix?.let { currentSpeed(it) },
                        "Нужен GPS и системный геокодер Android"
                    )
                    return@post
                }
                if (detected != country) {
                    country = detected
                    dbReady = databases.openCached(detected)
                    prepareDatabase(detected)
                } else if (dbReady) runLocalLookup()
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun detectCountry(location: Location): String? {
        if (!Geocoder.isPresent()) return null
        return runCatching {
            Geocoder(this, Locale.getDefault()).getFromLocation(location.latitude, location.longitude, 1)
                ?.firstOrNull()?.countryCode?.uppercase(Locale.ROOT)
        }.getOrNull()
    }

    private fun prepareDatabase(code: String) {
        if (dbUpdating) return
        dbReady = databases.openCached(code)
        if (dbReady) runLocalLookup()
        else publish("Загружаем базу ограничений $code…", null, lastFix?.let { currentSpeed(it) }, "OpenStreetMap")

        if (!dbReady || databases.shouldRefresh(code)) {
            dbUpdating = true
            nextDatabaseAttemptAt = System.currentTimeMillis() + DATABASE_RETRY_MILLIS
            worker.execute {
                val update = databases.downloadAndInstall(code)
                val ready = databases.openCached(code)
                main.post {
                    dbUpdating = false
                    if (!active || country != code) return@post
                    dbReady = ready
                    nextDatabaseAttemptAt = if (update.updated) 0L else System.currentTimeMillis() + DATABASE_RETRY_MILLIS
                    if (ready) {
                        publish(
                            if (update.updated) "База OpenStreetMap обновлена" else "Используется сохранённая база OpenStreetMap",
                            null, lastFix?.let { currentSpeed(it) }, "$code • локальная база готова"
                        )
                        runLocalLookup()
                    } else {
                        publish("Не удалось загрузить базу ограничений", null, lastFix?.let { currentSpeed(it) }, update.message)
                    }
                }
            }
        }
    }

    private fun runLocalLookup() {
        if (!active || !dbReady || roadLookup) return
        val fix = lastFix?.let(::Location) ?: return
        val code = country ?: return
        roadLookup = true
        lookup.execute {
            val result = databases.lookup(
                countryCode = code,
                latitude = fix.latitude,
                longitude = fix.longitude,
                accuracyMetres = fix.accuracy,
                bearingDegrees = if (fix.hasBearing()) fix.bearing else Float.NaN,
                speedMetresPerSecond = if (fix.hasSpeed()) fix.speed else 0f
            )
            main.post {
                roadLookup = false
                if (!active || country != code) return@post
                val newest = lastFix
                if (newest == null || !isFresh(newest)) {
                    publish("Сигнал GPS потерян — ограничение скрыто", null, null, null)
                    return@post
                }
                if (newest.time != fix.time) {
                    runLocalLookup()
                    return@post
                }
                when (result) {
                    is SpeedDatabaseLookup.Found -> {
                        val unit = if (result.match.isMph) SpeedUnit.MILES_PER_HOUR else SpeedUnit.KILOMETRES_PER_HOUR
                        val limit = SpeedLimit(
                            result.match.speedKmhOrMph,
                            unit,
                            "OpenStreetMap • ${result.match.roadRecordId}"
                        )
                        publish(
                            "Ограничение по данным OpenStreetMap",
                            limit, currentSpeed(newest),
                            "Сопоставление по геометрии • GPS ±${newest.accuracy.toInt()} м"
                        )
                    }
                    is SpeedDatabaseLookup.NoLimit -> publish(
                        if (result.nearestDistanceMetres != null) "Не удалось уверенно сопоставить GPS с дорожным сегментом"
                        else "В базе OSM нет подходящего ограничения для этой позиции",
                        null, currentSpeed(newest),
                        result.nearestDistanceMetres?.let { "Ближайший сегмент: ${it.toInt()} м" }
                    )
                    SpeedDatabaseLookup.GpsUnreliable -> publish(
                        "Недостаточная точность GPS для выбора дороги",
                        null, currentSpeed(newest),
                        "Ожидаем более точную координату"
                    )
                    SpeedDatabaseLookup.Ambiguous -> publish(
                        "Рядом несколько дорог с разными ограничениями", null,
                        currentSpeed(newest), "Ограничение скрыто до уверенного сопоставления"
                    )
                    SpeedDatabaseLookup.DatabaseUnavailable -> publish(
                        "Локальная база ограничений недоступна", null,
                        currentSpeed(newest), "Страна: $code"
                    )
                }
            }
        }
    }

    private fun currentSpeed(location: Location): Float? {
        if (!location.hasSpeed() || !location.speed.isFinite()) return null
        val usesMph = country?.uppercase(Locale.ROOT)?.let { it in MPH_COUNTRIES } == true
        return if (usesMph) location.speed * 2.23694f else location.speed * 3.6f
    }

    private fun isFresh(location: Location): Boolean =
        SystemClock.elapsedRealtimeNanos() - location.elapsedRealtimeNanos <= GPS_MAX_AGE_NANOS

    private fun publish(status: String, limit: SpeedLimit?, speed: Float?, detail: String?) {
        val usesMph = country?.uppercase(Locale.ROOT)?.let { it in MPH_COUNTRIES } == true
        val unit = limit?.unit ?: if (usesMph) SpeedUnit.MILES_PER_HOUR else SpeedUnit.KILOMETRES_PER_HOUR
        val speedText = speed?.takeIf { it.isFinite() }?.let { "${it.roundToInt()} ${unit.label}" } ?: "—"
        sendBroadcast(Intent(ACTION_STATE).setPackage(packageName).apply {
            putExtra(EXTRA_LIMIT, limit?.value?.toString() ?: "—")
            putExtra(EXTRA_UNIT, limit?.unit?.label.orEmpty())
            putExtra(EXTRA_STATUS, status)
            putExtra(EXTRA_SPEED, speedText)
            putExtra(EXTRA_DETAIL, detail.orEmpty())
            putExtra(EXTRA_KNOWN, limit != null)
        })
        val title = limit?.let { "${it.value} ${it.unit.label}" } ?: "Road Limit Reminder"
        val text = if (limit != null) listOf(status, detail.orEmpty()).filter { it.isNotBlank() }.joinToString(" • ") else status
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(title, text, limit))
    }

    private fun buildNotification(title: String, text: String, limit: SpeedLimit?): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, SpeedLimitService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(notificationIcon(limit))
            .setContentTitle(title).setContentText(text)
            .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true)
            .addAction(android.R.drawable.ic_media_pause, "Остановить", stop)
            .build()
    }

    private fun notificationIcon(limit: SpeedLimit?): IconCompat {
        if (limit == null) return IconCompat.createWithResource(this, android.R.drawable.ic_menu_mylocation)
        val bitmap = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888)
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
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Отслеживание ограничения скорости", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    private fun stopTracking() {
        active = false
        main.removeCallbacks(staleCheck)
        runCatching { locationManager.removeUpdates(this) }
        databases.close()
        getSharedPreferences(PREFERENCES, MODE_PRIVATE).edit().putBoolean(KEY_RUNNING, false).apply()
        lookup.shutdownNow()
        worker.shutdownNow()
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
        active = false
        main.removeCallbacks(staleCheck)
        runCatching { locationManager.removeUpdates(this) }
        databases.close()
        getSharedPreferences(PREFERENCES, MODE_PRIVATE).edit().putBoolean(KEY_RUNNING, false).apply()
        lookup.shutdownNow()
        worker.shutdownNow()
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
        private const val GPS_MAX_AGE_NANOS = 15_000_000_000L
        private const val COUNTRY_RETRY_MILLIS = 30_000L
        private const val DATABASE_RETRY_MILLIS = 10 * 60 * 1000L
        private val MPH_COUNTRIES = setOf("GB", "US", "LR", "MM")
    }
}
