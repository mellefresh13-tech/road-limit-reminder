package com.domanovsky.roadlimit.data

import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

class OverpassClient {
    private val endpoints = listOf(
        "https://overpass-api.de/api/interpreter",
        "https://overpass.kumi.systems/api/interpreter"
    )

    fun loadNearby(latitude: Double, longitude: Double): NearbyRoadData {
        val query = """
            [out:json][timeout:30];
            (
              way(around:1100,$latitude,$longitude)
                ["highway"~"^(motorway|trunk|primary|secondary|tertiary|unclassified|residential|living_street|service|road|motorway_link|trunk_link|primary_link|secondary_link|tertiary_link)$"];
              node(around:1100,$latitude,$longitude)["traffic_sign"];
              node(around:1100,$latitude,$longitude)["maxspeed"];
            );
            out tags geom;
            is_in($latitude,$longitude)->.containing;
            area.containing["boundary"="administrative"]["admin_level"="2"];
            out tags center;
            area.containing["place"~"^(city|town|village|hamlet|suburb|quarter|neighbourhood)$"];
            out tags center;
        """.trimIndent()
        var lastError: Exception? = null
        for (endpoint in endpoints) {
            try {
                val connection = URL(endpoint).openConnection() as HttpURLConnection
                try {
                    connection.requestMethod = "POST"
                    connection.connectTimeout = 12_000
                    connection.readTimeout = 30_000
                    connection.doOutput = true
                    connection.setRequestProperty("User-Agent", "RoadLimitReminder/0.4 (OpenStreetMap data consumer)")
                    connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
                    val body = "data=" + URLEncoder.encode(query, "UTF-8")
                    connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                    val code = connection.responseCode
                    val stream = if (code in 200..299) connection.inputStream else connection.errorStream
                    val response = stream?.use { input ->
                        BufferedReader(InputStreamReader(input, Charsets.UTF_8)).readText()
                    }.orEmpty()
                    if (code !in 200..299) throw IllegalStateException("Overpass HTTP $code")
                    return OverpassRoadParser.parseData(response)
                } finally {
                    connection.disconnect()
                }
            } catch (error: Exception) {
                lastError = error
            }
        }
        throw IllegalStateException("Не удалось получить данные OpenStreetMap", lastError)
    }
}
