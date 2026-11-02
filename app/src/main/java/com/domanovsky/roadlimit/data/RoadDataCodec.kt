package com.domanovsky.roadlimit.data

import com.domanovsky.roadlimit.domain.GeoPoint
import com.domanovsky.roadlimit.domain.RoadSegment
import org.json.JSONArray
import org.json.JSONObject

object RoadDataCodec {
    fun encode(data: NearbyRoadData): String {
        val root = JSONObject()
            .put("countryCode", data.countryCode)
            .put("insideSettlement", data.insideSettlement)
        val roads = JSONArray()
        data.roads.forEach { road ->
            val geometry = JSONArray()
            road.geometry.forEach { point ->
                geometry.put(JSONObject().put("lat", point.latitude).put("lon", point.longitude))
            }
            roads.put(JSONObject()
                .put("id", road.id)
                .put("name", road.name)
                .put("highway", road.highway)
                .put("tags", JSONObject(road.tags))
                .put("geometry", geometry))
        }
        val signs = JSONArray()
        data.signs.forEach { sign ->
            signs.put(JSONObject()
                .put("id", sign.id)
                .put("lat", sign.point.latitude)
                .put("lon", sign.point.longitude)
                .put("tags", JSONObject(sign.tags)))
        }
        root.put("roads", roads).put("signs", signs)
        return root.toString()
    }

    fun decode(payload: String): NearbyRoadData {
        val root = JSONObject(payload)
        val roads = ArrayList<RoadSegment>()
        val roadArray = root.optJSONArray("roads") ?: JSONArray()
        for (i in 0 until roadArray.length()) {
            val item = roadArray.optJSONObject(i) ?: continue
            val geometryArray = item.optJSONArray("geometry") ?: JSONArray()
            val geometry = ArrayList<GeoPoint>()
            for (j in 0 until geometryArray.length()) {
                val point = geometryArray.optJSONObject(j) ?: continue
                geometry += GeoPoint(point.optDouble("lat"), point.optDouble("lon"))
            }
            if (geometry.size < 2) continue
            val tags = item.optJSONObject("tags") ?: JSONObject()
            roads += RoadSegment(
                item.optLong("id"),
                item.optString("name").takeUnless { it == "null" || it.isBlank() },
                item.optString("highway"),
                tags.toStringMap(),
                geometry
            )
        }
        val signs = ArrayList<TrafficSign>()
        val signArray = root.optJSONArray("signs") ?: JSONArray()
        for (i in 0 until signArray.length()) {
            val item = signArray.optJSONObject(i) ?: continue
            val tags = item.optJSONObject("tags") ?: JSONObject()
            signs += TrafficSign(
                item.optLong("id"),
                GeoPoint(item.optDouble("lat"), item.optDouble("lon")),
                tags.toStringMap()
            )
        }
        return NearbyRoadData(
            roads,
            root.optString("countryCode").takeUnless { it == "null" || it.isBlank() },
            if (root.isNull("insideSettlement")) null else root.optBoolean("insideSettlement"),
            signs
        )
    }

    private fun JSONObject.toStringMap(): Map<String, String> {
        val result = LinkedHashMap<String, String>()
        val keys = keys()
        while (keys.hasNext()) {
            val key = keys.next()
            result[key] = optString(key)
        }
        return result
    }
}
