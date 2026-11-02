package com.domanovsky.roadlimit.data

import android.content.Context
import android.location.Location
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.math.*

data class TwoGisGpsPoint(
    val latitude: Double,
    val longitude: Double,
    val utcSeconds: Long,
    val speedKmh: Double?,
    val azimuthDegrees: Double?
)

data class TwoGisSpeedInterval(
    val startOffsetMetres: Double,
    val endOffsetMetres: Double,
    val speedLimitKmh: Int
)

data class TwoGisEdge(
    val id: Long,
    val distanceMetres: Double,
    val geometry: List<com.domanovsky.roadlimit.domain.GeoPoint>,
    val speedLimitKmh: Int?,
    val speedLimits: List<TwoGisSpeedInterval>
)

data class TwoGisMatchResult(
    val edges: List<TwoGisEdge>,
    val currentEdgeId: Long?,
    val matchedPoint: com.domanovsky.roadlimit.domain.GeoPoint?
)

class TwoGisMapMatchingClient(private val apiKey: String) {
    fun match(points: List<TwoGisGpsPoint>): TwoGisMatchResult {
        require(apiKey.isNotBlank()) { "Не настроен ключ API 2ГИС" }
        require(points.size in 2..10) { "Для Map Matching нужны минимум две GPS-точки" }

        val payload = JSONObject()
            .put("query", JSONArray().apply {
                points.forEach { point ->
                    put(JSONObject()
                        .put("lon", point.longitude)
                        .put("lat", point.latitude)
                        .put("utc", point.utcSeconds)
                        .apply {
                            point.speedKmh?.let { put("speed", it) }
                            point.azimuthDegrees?.let { put("azimuth", it) }
                        })
                }
            })
            .put("need_speed_limits", true)
            .put("search_radius", SEARCH_RADIUS_METRES)
            .put("bad_point_tolerance", "high")

        val encodedKey = URLEncoder.encode(apiKey, Charsets.UTF_8.name())
        val connection = (URL("$ENDPOINT?key=$encodedKey").openConnection() as HttpURLConnection)
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = CONNECT_TIMEOUT_MILLIS
            connection.readTimeout = READ_TIMEOUT_MILLIS
            connection.doOutput = true
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            connection.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }

            val statusCode = connection.responseCode
            val stream = if (statusCode in 200..299) connection.inputStream else connection.errorStream
            val response = stream?.use { input ->
                BufferedReader(InputStreamReader(input, Charsets.UTF_8)).readText()
            }.orEmpty()

            if (statusCode !in 200..299) {
                val details = runCatching {
                    JSONObject(response).optString("message").takeIf(String::isNotBlank)
                }.getOrNull()
                throw IllegalStateException(
                    details?.let { "2ГИС HTTP $statusCode: $it" } ?: "2ГИС HTTP $statusCode"
                )
            }
            return TwoGisResponseParser.parse(response)
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        private const val ENDPOINT = "https://routing.api.2gis.com/map_matching/1.0.0"
        private const val SEARCH_RADIUS_METRES = 100
        private const val CONNECT_TIMEOUT_MILLIS = 7_000
        private const val READ_TIMEOUT_MILLIS = 12_000
    }
}

object TwoGisResponseParser {
    fun parse(json: String): TwoGisMatchResult {
        val root = JSONObject(json)
        if (!root.optString("status").equals("OK", ignoreCase = true)) {
            throw IllegalStateException("2ГИС не смог сопоставить GPS-точки с дорожной сетью")
        }

        val edgesJson = root.optJSONArray("edges") ?: JSONArray()
        val edges = ArrayList<TwoGisEdge>(edgesJson.length())
        for (index in 0 until edgesJson.length()) {
            val item = edgesJson.optJSONObject(index) ?: continue
            val geometry = parseLineString(item.optString("geometry"))
            if (geometry.size < 2) continue
            val speedLimitsJson = item.optJSONArray("speed_limits") ?: JSONArray()
            val speedLimits = ArrayList<TwoGisSpeedInterval>(speedLimitsJson.length())
            for (limitIndex in 0 until speedLimitsJson.length()) {
                val limit = speedLimitsJson.optJSONObject(limitIndex) ?: continue
                val value = limit.optInt("speed_limit", 0).takeIf { it in 1..300 } ?: continue
                val start = limit.optDouble("start_offset", Double.NaN)
                val end = limit.optDouble("end_offset", Double.NaN)
                if (start.isFinite() && end.isFinite() && end > start) {
                    speedLimits += TwoGisSpeedInterval(start, end, value)
                }
            }
            val speedLimit = item.optInt("speed_limit", 0).takeIf { it in 1..300 }
            edges += TwoGisEdge(
                id = item.optLong("edge_id", -1L),
                distanceMetres = item.optDouble("distance", 0.0),
                geometry = geometry,
                speedLimitKmh = speedLimit,
                speedLimits = speedLimits
            )
        }

        val query = root.optJSONArray("query") ?: JSONArray()
        var currentEdgeId: Long? = null
        var matchedPoint: com.domanovsky.roadlimit.domain.GeoPoint? = null
        for (index in 0 until query.length()) {
            val item = query.optJSONObject(index) ?: continue
            val edgeId = item.optLong("edge_id", -1L)
            val lat = item.optDouble("lat_matched", Double.NaN)
            val lon = item.optDouble("lon_matched", Double.NaN)
            if (edgeId > 0L && lat.isFinite() && lon.isFinite()) {
                currentEdgeId = edgeId
                matchedPoint = com.domanovsky.roadlimit.domain.GeoPoint(lat, lon)
            }
        }
        return TwoGisMatchResult(edges, currentEdgeId, matchedPoint)
    }

    private fun parseLineString(wkt: String): List<com.domanovsky.roadlimit.domain.GeoPoint> {
        val body = wkt.substringAfter("(", "").substringBeforeLast(")", "")
        if (body.isBlank()) return emptyList()
        return body.split(",").mapNotNull { coordinate ->
            val values = coordinate.trim().split(Regex("\\s+"))
            if (values.size < 2) return@mapNotNull null
            val longitude = values[0].toDoubleOrNull() ?: return@mapNotNull null
            val latitude = values[1].toDoubleOrNull() ?: return@mapNotNull null
            if (!latitude.isFinite() || !longitude.isFinite()) return@mapNotNull null
            com.domanovsky.roadlimit.domain.GeoPoint(latitude, longitude)
        }
    }
}

