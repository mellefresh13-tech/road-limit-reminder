package com.domanovsky.roadlimit.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import org.json.JSONArray
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlin.math.*

data class SpeedDatabaseMatch(
    val speedKmhOrMph: Int,
    val isMph: Boolean,
    val roadRecordId: Long,
    val distanceMetres: Double
)

sealed interface SpeedDatabaseLookup {
    data class Found(val match: SpeedDatabaseMatch) : SpeedDatabaseLookup
    data class NoLimit(val nearestDistanceMetres: Double?) : SpeedDatabaseLookup
    data object DatabaseUnavailable : SpeedDatabaseLookup
    data object Ambiguous : SpeedDatabaseLookup
}

data class SpeedDatabaseUpdate(
    val ready: Boolean,
    val updated: Boolean,
    val message: String,
    val countryCode: String
)

/**
 * Reads the public OSM-derived country SQLite databases published by
 * HvdWolf / SpeedAlert_Databases. The database is stored only in app-private
 * storage and queried locally; GPS coordinates are never sent to a lookup API.
 */
class OpenSpeedDatabaseManager(context: Context) {
    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
    private val databaseDirectory = File(appContext.filesDir, "open-speed-databases")
    private var activeDatabase: SQLiteDatabase? = null
    private var activeCountry: String? = null

    @Synchronized
    fun openCached(countryCode: String): Boolean {
        val code = countryCode.lowercase(Locale.ROOT)
        if (activeCountry == code && activeDatabase?.isOpen == true) return true

        val file = databaseFile(code)
        if (!file.isFile || file.length() < MIN_SQLITE_SIZE) return false

        return runCatching {
            val opened = SQLiteDatabase.openDatabase(
                file.absolutePath,
                null,
                SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS
            )
            verifySchema(opened)
            activeDatabase?.close()
            activeDatabase = opened
            activeCountry = code
            true
        }.getOrElse {
            activeDatabase?.close()
            activeDatabase = null
            activeCountry = null
            false
        }
    }

    fun shouldRefresh(countryCode: String, now: Long = System.currentTimeMillis()): Boolean {
        val lastSuccess = preferences.getLong(KEY_REFRESH_PREFIX + countryCode.lowercase(Locale.ROOT), 0L)
        return lastSuccess == 0L || now - lastSuccess >= DATABASE_REFRESH_INTERVAL_MILLIS
    }

    fun downloadAndInstall(countryCode: String): SpeedDatabaseUpdate {
        val code = countryCode.lowercase(Locale.ROOT)
        if (!code.matches(Regex("^[a-z]{2}$"))) {
            return SpeedDatabaseUpdate(false, false, "Некорректный код страны", code)
        }

        val lastAttempt = preferences.getLong(KEY_ATTEMPT_PREFIX + code, 0L)
        if (System.currentTimeMillis() - lastAttempt < FAILED_DOWNLOAD_RETRY_MILLIS &&
            !databaseFile(code).exists()
        ) {
            return SpeedDatabaseUpdate(
                false, false, "Повторная загрузка базы будет доступна позже", code
            )
        }
        preferences.edit().putLong(KEY_ATTEMPT_PREFIX + code, System.currentTimeMillis()).apply()

        if (!databaseDirectory.exists() && !databaseDirectory.mkdirs()) {
            return SpeedDatabaseUpdate(false, false, "Не удалось создать каталог базы данных", code)
        }

        val archive = File(databaseDirectory, "$code.sqlite.7z.download")
        val extracted = File(databaseDirectory, "$code.sqlite.new")
        val target = databaseFile(code)

        return try {
            val downloadUrl = resolveDatabaseUrl(code)
                ?: throw IllegalStateException("Для страны $code нет опубликованной базы скоростных ограничений")
            downloadFile(downloadUrl, archive)
            extractDatabase(archive, extracted, "$code.sqlite")
            verifyDatabaseFile(extracted)

            synchronized(this) {
                activeDatabase?.close()
                activeDatabase = null
                activeCountry = null
                if (target.exists() && !target.delete()) {
                    throw IllegalStateException("Не удалось заменить старую базу")
                }
                if (!extracted.renameTo(target)) {
                    extracted.copyTo(target, overwrite = true)
                    extracted.delete()
                }
                val opened = SQLiteDatabase.openDatabase(
                    target.absolutePath,
                    null,
                    SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS
                )
                verifySchema(opened)
                activeDatabase = opened
                activeCountry = code
            }

            preferences.edit()
                .putLong(KEY_REFRESH_PREFIX + code, System.currentTimeMillis())
                .remove(KEY_ATTEMPT_PREFIX + code)
                .apply()
            SpeedDatabaseUpdate(true, true, "База OpenStreetMap обновлена", code)
        } catch (error: Exception) {
            val oldDatabaseAvailable = openCached(code)
            SpeedDatabaseUpdate(
                ready = oldDatabaseAvailable,
                updated = false,
                message = error.message ?: "Не удалось обновить базу OpenStreetMap",
                countryCode = code
            )
        } finally {
            archive.delete()
            extracted.delete()
        }
    }

    @Synchronized
    fun lookup(
        countryCode: String,
        latitude: Double,
        longitude: Double,
        accuracyMetres: Float,
        bearingDegrees: Float,
        speedMetresPerSecond: Float
    ): SpeedDatabaseLookup {
        val code = countryCode.lowercase(Locale.ROOT)
        if (activeCountry != code || activeDatabase?.isOpen != true) {
            if (!openCached(code)) return SpeedDatabaseLookup.DatabaseUnavailable
        }

        val db = activeDatabase ?: return SpeedDatabaseLookup.DatabaseUnavailable
        if (!latitude.isFinite() || !longitude.isFinite() ||
            !accuracyMetres.isFinite() || accuracyMetres <= 0f || accuracyMetres > MAX_GPS_ACCURACY_METRES
        ) return SpeedDatabaseLookup.NoLimit(null)

        val maxDistance = max(MIN_MATCH_DISTANCE_METRES, min(MAX_MATCH_DISTANCE_METRES, accuracyMetres * 1.5))
        val mph = code.uppercase(Locale.ROOT) in MPH_COUNTRIES
        val sql = """
            SELECT speed_index.id,
                   speed_index.minLat,
                   speed_index.maxLat,
                   speed_index.minLon,
                   speed_index.maxLon,
                   speed_index.polyline,
                   speed_data.speed
            FROM speed_index
            JOIN speed_data ON speed_index.id = speed_data.id
            WHERE speed_index.minLat <= ?
              AND speed_index.maxLat >= ?
              AND speed_index.minLon <= ?
              AND speed_index.maxLon >= ?
        """.trimIndent()

        return try {
            val latitudeMargin = maxDistance / METRES_PER_DEGREE
            val longitudeMargin = maxDistance / (METRES_PER_DEGREE * cos(Math.toRadians(latitude))).coerceAtLeast(MIN_LONGITUDE_SCALE)
            db.rawQuery(
                sql,
                arrayOf(
                    (latitude + latitudeMargin).toString(),
                    (latitude - latitudeMargin).toString(),
                    (longitude + longitudeMargin).toString(),
                    (longitude - longitudeMargin).toString()
                )
            ).use { cursor ->
                var best: Candidate? = null
                var second: Candidate? = null
                var nearest = Double.POSITIVE_INFINITY

                while (cursor.moveToNext()) {
                    val speed = cursor.getInt(6)
                    if (speed !in MIN_VALID_SPEED..MAX_VALID_SPEED) continue
                    val encodedPolyline = cursor.getString(5) ?: continue
                    val points = EncodedRoadPolyline.decode(encodedPolyline)
                    if (points.size < 2) continue

                    val projection = RoadGeometryMatcher.project(
                        latitude, longitude, bearingDegrees, speedMetresPerSecond, points
                    ) ?: continue
                    nearest = min(nearest, projection.distanceMetres)
                    if (projection.distanceMetres > maxDistance) continue

                    val candidate = Candidate(
                        id = cursor.getLong(0),
                        speed = speed,
                        distanceMetres = projection.distanceMetres,
                        score = projection.score
                    )
                    if (best == null || candidate.score < best!!.score) {
                        second = best
                        best = candidate
                    } else if (second == null || candidate.score < second!!.score) {
                        second = candidate
                    }
                }

                val selected = best ?: return@use SpeedDatabaseLookup.NoLimit(
                    nearest.takeIf { it.isFinite() }
                )
                if (second != null &&
                    second!!.speed != selected.speed &&
                    second!!.score - selected.score < AMBIGUOUS_MATCH_MARGIN_METRES
                ) {
                    return@use SpeedDatabaseLookup.Ambiguous
                }
                SpeedDatabaseLookup.Found(
                    SpeedDatabaseMatch(
                        speedKmhOrMph = selected.speed,
                        isMph = mph,
                        roadRecordId = selected.id,
                        distanceMetres = selected.distanceMetres
                    )
                )
            }
        } catch (_: Exception) {
            SpeedDatabaseLookup.NoLimit(null)
        }
    }

    @Synchronized
    fun close() {
        activeDatabase?.close()
        activeDatabase = null
        activeCountry = null
    }

    private fun databaseFile(countryCode: String) =
        File(databaseDirectory, "${countryCode.lowercase(Locale.ROOT)}.sqlite")

    private fun resolveDatabaseUrl(countryCode: String): String? {
        val preferred = "$LATEST_EUROPE_RELEASE/$countryCode.sqlite.7z"
        if (headAvailable(preferred)) return preferred

        val connection = (URL(RELEASES_API).openConnection() as HttpURLConnection)
        try {
            connection.connectTimeout = CONNECT_TIMEOUT_MILLIS
            connection.readTimeout = METADATA_TIMEOUT_MILLIS
            connection.setRequestProperty("User-Agent", USER_AGENT)
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            val code = connection.responseCode
            if (code !in 200..299) return null
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            val releases = JSONArray(body)
            val europeReleases = (0 until releases.length()).mapNotNull { releases.optJSONObject(it) }
                .filter { it.optString("tag_name").startsWith("europe-") }
                .sortedByDescending { it.optString("published_at") }

            for (release in europeReleases) {
                val assets = release.optJSONArray("assets") ?: continue
                for (index in 0 until assets.length()) {
                    val asset = assets.optJSONObject(index) ?: continue
                    if (asset.optString("name") == "$countryCode.sqlite.7z") {
                        return asset.optString("browser_download_url").takeIf { it.startsWith("https://") }
                    }
                }
            }
            return null
        } finally {
            connection.disconnect()
        }
    }

    private fun headAvailable(url: String): Boolean {
        val connection = (URL(url).openConnection() as HttpURLConnection)
        return try {
            connection.instanceFollowRedirects = true
            connection.requestMethod = "HEAD"
            connection.connectTimeout = CONNECT_TIMEOUT_MILLIS
            connection.readTimeout = METADATA_TIMEOUT_MILLIS
            connection.setRequestProperty("User-Agent", USER_AGENT)
            connection.responseCode in 200..299
        } catch (_: Exception) {
            false
        } finally {
            connection.disconnect()
        }
    }

    private fun downloadFile(url: String, target: File) {
        val connection = (URL(url).openConnection() as HttpURLConnection)
        try {
            connection.instanceFollowRedirects = true
            connection.requestMethod = "GET"
            connection.connectTimeout = CONNECT_TIMEOUT_MILLIS
            connection.readTimeout = DOWNLOAD_TIMEOUT_MILLIS
            connection.setRequestProperty("User-Agent", USER_AGENT)
            connection.setRequestProperty("Accept", "application/octet-stream")
            val response = connection.responseCode
            if (response !in 200..299) throw IllegalStateException("Сервер базы данных вернул HTTP $response")
            val length = connection.contentLengthLong
            if (length > MAX_ARCHIVE_SIZE_BYTES) throw IllegalStateException("Архив базы превышает допустимый размер")
            connection.inputStream.use { input ->
                BufferedInputStream(input).use { buffered ->
                    FileOutputStream(target).use { output ->
                        val buffer = ByteArray(BUFFER_SIZE)
                        var total = 0L
                        while (true) {
                            val count = buffered.read(buffer)
                            if (count < 0) break
                            total += count
                            if (total > MAX_ARCHIVE_SIZE_BYTES) {
                                throw IllegalStateException("Архив базы превышает допустимый размер")
                            }
                            output.write(buffer, 0, count)
                        }
                        output.fd.sync()
                    }
                }
            }
            if (target.length() < MIN_ARCHIVE_SIZE_BYTES) {
                throw IllegalStateException("Загруженный архив базы пуст или повреждён")
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun extractDatabase(archive: File, output: File, expectedName: String) {
        var extracted = false
        SevenZFile.builder().setFile(archive).get().use { sevenZ ->
            while (true) {
                val entry = sevenZ.nextEntry ?: break
                if (entry.isDirectory || entry.name.substringAfterLast('/') != expectedName) continue
                if (entry.size !in MIN_SQLITE_SIZE..MAX_SQLITE_SIZE_BYTES) {
                    throw IllegalStateException("Неожиданный размер SQLite-базы в архиве")
                }
                FileOutputStream(output).use { fileOutput ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    var total = 0L
                    while (true) {
                        val count = sevenZ.read(buffer)
                        if (count < 0) break
                        total += count
                        if (total > MAX_SQLITE_SIZE_BYTES) {
                            throw IllegalStateException("Распакованная база превышает допустимый размер")
                        }
                        fileOutput.write(buffer, 0, count)
                    }
                    fileOutput.fd.sync()
                }
                extracted = true
                break
            }
        }
        if (!extracted || !output.isFile || output.length() < MIN_SQLITE_SIZE) {
            throw IllegalStateException("В архиве не найдена база $expectedName")
        }
    }

    private fun verifyDatabaseFile(file: File) {
        val db = SQLiteDatabase.openDatabase(
            file.absolutePath,
            null,
            SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS
        )
        db.use { verifySchema(it) }
    }

    private fun verifySchema(db: SQLiteDatabase) {
        db.rawQuery(
            "SELECT speed_index.id, speed_index.minLat, speed_index.maxLat, speed_index.minLon, speed_index.maxLon, speed_index.polyline, speed_data.speed FROM speed_index JOIN speed_data ON speed_index.id = speed_data.id LIMIT 1",
            null
        ).use { }
    }

    private data class Candidate(
        val id: Long,
        val speed: Int,
        val distanceMetres: Double,
        val score: Double
    )

    companion object {
        private const val PREFERENCES = "open_speed_database"
        private const val KEY_REFRESH_PREFIX = "last_refresh_"
        private const val KEY_ATTEMPT_PREFIX = "last_attempt_"
        private const val LATEST_EUROPE_RELEASE =
            "https://github.com/hvdwolf/SpeedAlert_Databases/releases/latest/download"
        private const val RELEASES_API =
            "https://api.github.com/repos/hvdwolf/SpeedAlert_Databases/releases?per_page=20"
        private const val USER_AGENT = "RoadLimitReminder/0.5 (Android; com.domanovsky.roadlimit)"
        private const val CONNECT_TIMEOUT_MILLIS = 10_000
        private const val METADATA_TIMEOUT_MILLIS = 20_000
        private const val DOWNLOAD_TIMEOUT_MILLIS = 90_000
        private const val BUFFER_SIZE = 64 * 1024
        private const val MIN_ARCHIVE_SIZE_BYTES = 1_024L
        private const val MAX_ARCHIVE_SIZE_BYTES = 100L * 1024L * 1024L
        private const val MIN_SQLITE_SIZE = 4_096L
        private const val MAX_SQLITE_SIZE_BYTES = 500L * 1024L * 1024L
        private const val DATABASE_REFRESH_INTERVAL_MILLIS = 30L * 24L * 60L * 60L * 1000L
        private const val FAILED_DOWNLOAD_RETRY_MILLIS = 10L * 60L * 1000L
        private const val METRES_PER_DEGREE = 111_320.0
        private const val MIN_LONGITUDE_SCALE = 1.0
        private const val MIN_VALID_SPEED = 1
        private const val MAX_VALID_SPEED = 300
        private const val MAX_GPS_ACCURACY_METRES = 40f
        private const val MIN_MATCH_DISTANCE_METRES = 15.0
        private const val MAX_MATCH_DISTANCE_METRES = 40.0
        private const val AMBIGUOUS_MATCH_MARGIN_METRES = 3.0
        private val MPH_COUNTRIES = setOf("GB", "US", "LR", "MM")
    }
}

/** Google encoded polyline decoder, precision 1e-5, as used by the public SQLite datasets. */
object EncodedRoadPolyline {
    fun decode(encoded: String): List<Pair<Double, Double>> {
        val points = ArrayList<Pair<Double, Double>>()
        var index = 0
        var latitude = 0
        var longitude = 0

        while (index < encoded.length) {
            val latDelta = decodeDelta(encoded, index)
            index = latDelta.nextIndex
            val lonDelta = decodeDelta(encoded, index)
            index = lonDelta.nextIndex
            latitude = Math.addExact(latitude, latDelta.value)
            longitude = Math.addExact(longitude, lonDelta.value)
            points += latitude / 1e5 to longitude / 1e5
        }
        return points
    }

    private data class DecodedDelta(val value: Int, val nextIndex: Int)

    private fun decodeDelta(encoded: String, start: Int): DecodedDelta {
        var index = start
        var result = 0L
        var shift = 0
        while (true) {
            if (index >= encoded.length || shift > 30) {
                throw IllegalArgumentException("Malformed encoded road polyline")
            }
            val chunk = encoded[index++].code - 63
            if (chunk !in 0..63) throw IllegalArgumentException("Malformed encoded road polyline")
            result = result or ((chunk and 0x1f).toLong() shl shift)
            if (chunk < 0x20) break
            shift += 5
        }
        val delta = if ((result and 1L) != 0L) (result.inv() shr 1) else (result shr 1)
        if (delta !in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) {
            throw IllegalArgumentException("Road polyline coordinate is out of range")
        }
        return DecodedDelta(delta.toInt(), index)
    }
}

object RoadGeometryMatcher {
    data class Projection(val distanceMetres: Double, val score: Double)

    fun project(
        latitude: Double,
        longitude: Double,
        bearingDegrees: Float,
        speedMetresPerSecond: Float,
        points: List<Pair<Double, Double>>
    ): Projection? {
        if (points.size < 2) return null
        val longitudeScale = METRES_PER_DEGREE * cos(Math.toRadians(latitude))
        var bestDistance = Double.POSITIVE_INFINITY
        var bestBearing: Double? = null

        for (index in 0 until points.lastIndex) {
            val (startLat, startLon) = points[index]
            val (endLat, endLon) = points[index + 1]
            val dx = (endLon - startLon) * longitudeScale
            val dy = (endLat - startLat) * METRES_PER_DEGREE
            val px = (longitude - startLon) * longitudeScale
            val py = (latitude - startLat) * METRES_PER_DEGREE
            val lengthSquared = dx * dx + dy * dy
            if (lengthSquared < 0.0001) continue
            val t = ((px * dx + py * dy) / lengthSquared).coerceIn(0.0, 1.0)
            val distance = hypot(px - t * dx, py - t * dy)
            if (distance < bestDistance) {
                bestDistance = distance
                bestBearing = (Math.toDegrees(atan2(dx, dy)) + 360.0) % 360.0
            }
        }
        if (!bestDistance.isFinite()) return null

        val headingPenalty = if (speedMetresPerSecond >= MIN_HEADING_SPEED && bearingDegrees.isFinite()) {
            val segmentBearing = bestBearing ?: return null
            val forward = angleDifference(bearingDegrees.toDouble(), segmentBearing)
            val backward = angleDifference(bearingDegrees.toDouble(), (segmentBearing + 180.0) % 360.0)
            min(forward, backward) * HEADING_WEIGHT
        } else {
            0.0
        }
        return Projection(bestDistance, bestDistance + headingPenalty)
    }

    private fun angleDifference(first: Double, second: Double): Double {
        val difference = abs((first - second) % 360.0)
        return min(difference, 360.0 - difference)
    }

    private const val METRES_PER_DEGREE = 111_320.0
    private const val MIN_HEADING_SPEED = 3.0f
    private const val HEADING_WEIGHT = 0.06
}
