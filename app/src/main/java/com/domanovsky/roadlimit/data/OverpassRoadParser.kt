package com.domanovsky.roadlimit.data

import com.domanovsky.roadlimit.domain.GeoPoint
import com.domanovsky.roadlimit.domain.RoadSegment
import org.json.JSONObject

object OverpassRoadParser {
    private val settlementPlaces = setOf(
        "city", "town", "village", "hamlet", "suburb", "quarter", "neighbourhood"
    )

    fun parse(json: String): List<RoadSegment> = parseData(json).roads

    fun parseData(json: String): NearbyRoadData {
        val elements = JSONObject(json).optJSONArray("elements")
            ?: return NearbyRoadData(emptyList(), null, null)
        val roads = ArrayList<RoadSegment>(elements.length())
        val signs = ArrayList<TrafficSign>()
        var countryCode: String? = null
        var insideSettlement = false
        var countryAreaFound = false

        for (index in 0 until elements.length()) {
            val element = elements.optJSONObject(index) ?: continue
            val type = element.optString("type")
            val tagsJson = element.optJSONObject("tags") ?: continue
            val tags = LinkedHashMap<String, String>()
            val keys = tagsJson.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                tags[key] = tagsJson.optString(key)
            }

            if (type == "area") {
                if (tags["boundary"] == "administrative" && tags["admin_level"] == "2") {
                    countryAreaFound = true
                    countryCode = tags["ISO3166-1"] ?: tags["ISO3166-1:alpha2"] ?: countryCode
                }
                tags["place"]?.let { if (it in settlementPlaces) insideSettlement = true }
                continue
            }

            if (type == "node") {
                val lat = element.optDouble("lat", Double.NaN)
                val lon = element.optDouble("lon", Double.NaN)
                if (lat.isFinite() && lon.isFinite() &&
                    (tags.containsKey("traffic_sign") || tags.containsKey("traffic_sign:forward") ||
                        tags.containsKey("traffic_sign:backward") || tags.containsKey("maxspeed"))
                ) {
                    signs += TrafficSign(element.optLong("id", -1L), GeoPoint(lat, lon), tags)
                }
                continue
            }
            if (type != "way") continue

            val id = element.optLong("id", -1L)
            val highway = tags["highway"]?.takeIf { it.isNotBlank() } ?: continue
            val geometryJson = element.optJSONArray("geometry") ?: continue
            if (geometryJson.length() < 2) continue
            val geometry = ArrayList<GeoPoint>(geometryJson.length())
            for (pointIndex in 0 until geometryJson.length()) {
                val point = geometryJson.optJSONObject(pointIndex) ?: continue
                val lat = point.optDouble("lat", Double.NaN)
                val lon = point.optDouble("lon", Double.NaN)
                if (lat.isFinite() && lon.isFinite()) geometry += GeoPoint(lat, lon)
            }
            if (geometry.size < 2) continue
            if (tags["access"] == "no" || tags["motor_vehicle"] == "no" ||
                tags["motorcar"] == "no" || tags["access"] == "private"
            ) continue
            roads += RoadSegment(id, tags["name"] ?: tags["ref"], highway, tags, geometry)
        }

        val settlementState = when {
            insideSettlement -> true
            countryAreaFound -> false
            else -> null
        }
        return NearbyRoadData(roads, countryCode, settlementState, signs)
    }
}
