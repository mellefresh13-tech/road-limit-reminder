package com.domanovsky.roadlimit.domain

data class GeoPoint(val latitude: Double, val longitude: Double)
data class RoadSegment(
    val id: Long,
    val name: String?,
    val highway: String,
    val tags: Map<String, String>,
    val geometry: List<GeoPoint>
)
data class LocationFix(
    val latitude: Double,
    val longitude: Double,
    val accuracyMetres: Float,
    val bearingDegrees: Float,
    val speedMetresPerSecond: Float
)

sealed interface LimitResolution {
    data class Known(val limit: SpeedLimit.Known) : LimitResolution
    data class Unavailable(val reason: String) : LimitResolution
}

object RoadLimitResolver {
    fun resolve(tags: Map<String, String>): LimitResolution {
        if (tags.containsKey("maxspeed:conditional")) {
            return LimitResolution.Unavailable("Условное ограничение не поддерживается")
        }
        if (tags["maxspeed:variable"]?.equals("no", ignoreCase = true) == false) {
            return LimitResolution.Unavailable("Переменное ограничение скорости")
        }
        if (tags.keys.any { it.startsWith("maxspeed:lanes") }) {
            return LimitResolution.Unavailable("Ограничения различаются по полосам")
        }
        if (tags.containsKey("maxspeed:forward") || tags.containsKey("maxspeed:backward")) {
            return LimitResolution.Unavailable("Ограничение зависит от направления")
        }
        val raw = tags["maxspeed:motorcar"] ?: tags["maxspeed:motor_vehicle"] ?: tags["maxspeed"]
            ?: return LimitResolution.Unavailable("Для этой дороги нет явного ограничения в OSM")
        return when (val parsed = OsmMaxspeedParser.parse(raw)) {
            is SpeedLimit.Known -> LimitResolution.Known(parsed)
            is SpeedLimit.Conditional -> LimitResolution.Unavailable("Условное ограничение не поддерживается")
            is SpeedLimit.NotNumeric -> LimitResolution.Unavailable("Нечисловое значение maxspeed: $raw")
            SpeedLimit.Missing -> LimitResolution.Unavailable("Ограничение скорости не указано")
        }
    }
}
