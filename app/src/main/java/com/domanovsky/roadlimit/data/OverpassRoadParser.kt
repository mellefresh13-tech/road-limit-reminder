package com.domanovsky.roadlimit.data

import com.domanovsky.roadlimit.domain.GeoPoint
import com.domanovsky.roadlimit.domain.RoadSegment
import org.json.JSONObject

object OverpassRoadParser {
    fun parse(json: String): List<RoadSegment> {
        val elements = JSONObject(json).optJSONArray("elements") ?: return emptyList()
        val roads = ArrayList<RoadSegment>(elements.length())
        for (index in 0 until elements.length()) {
            val element = elements.optJSONObject(index) ?: continue
            if (element.optString("type") != "way") continue
            val id = element.optLong("id", -1L)
            val tagsJson = element.optJSONObject("tags") ?: continue
            val highway = tagsJson.optString("highway").takeIf { it.isNotBlank() } ?: continue
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
            val tags = LinkedHashMap<String, String>()
            val keys = tagsJson.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                tags[key] = tagsJson.optString(key)
            }
            if (tags["access"] == "no" || tags["motor_vehicle"] == "no" ||
                tags["motorcar"] == "no" || tags["access"] == "private"
            ) continue
            roads += RoadSegment(id, tags["name"] ?: tags["ref"], highway, tags, geometry)
        }
        return roads
    }
}
