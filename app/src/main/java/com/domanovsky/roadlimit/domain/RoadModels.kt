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

enum class RoadDirection { FORWARD, BACKWARD, UNKNOWN }

sealed interface LimitResolution {
    data class Known(val limit: SpeedLimit.Known) : LimitResolution
    data class Unavailable(val reason: String) : LimitResolution
}

object RoadLimitResolver {
    fun resolve(
        tags: Map<String, String>,
        countryCode: String? = null,
        insideSettlement: Boolean? = null,
        direction: RoadDirection = RoadDirection.UNKNOWN
    ): LimitResolution {
        if (tags.containsKey("maxspeed:conditional")) {
            return LimitResolution.Unavailable("Условное ограничение не поддерживается")
        }
        if (tags["maxspeed:variable"]?.equals("no", ignoreCase = true) == false) {
            return LimitResolution.Unavailable("Переменное ограничение скорости")
        }
        if (tags.keys.any { it.startsWith("maxspeed:lanes") }) {
            return LimitResolution.Unavailable("Ограничения различаются по полосам")
        }

        val hasDirectionalLimit = tags.containsKey("maxspeed:forward") || tags.containsKey("maxspeed:backward")
        if (hasDirectionalLimit && direction == RoadDirection.UNKNOWN) {
            return LimitResolution.Unavailable("Не удалось уверенно определить направление движения")
        }
        val directionalRaw = when (direction) {
            RoadDirection.FORWARD -> tags["maxspeed:forward"]
            RoadDirection.BACKWARD -> tags["maxspeed:backward"]
            RoadDirection.UNKNOWN -> null
        }
        val raw = directionalRaw ?: tags["maxspeed:motorcar"]
            ?: tags["maxspeed:motor_vehicle"] ?: tags["maxspeed"]

        if (raw != null) {
            belarusSymbolicLimit(raw)?.let { return LimitResolution.Known(it) }
            return when (val parsed = OsmMaxspeedParser.parse(raw)) {
                is SpeedLimit.Known -> LimitResolution.Known(parsed)
                is SpeedLimit.Conditional -> LimitResolution.Unavailable("Условное ограничение не поддерживается")
                is SpeedLimit.NotNumeric -> LimitResolution.Unavailable("Нечисловое значение maxspeed: $raw")
                SpeedLimit.Missing -> LimitResolution.Unavailable("Ограничение скорости не указано")
            }
        }

        val signDirectionTag = when (direction) {
            RoadDirection.FORWARD -> tags["traffic_sign:forward"]
            RoadDirection.BACKWARD -> tags["traffic_sign:backward"]
            RoadDirection.UNKNOWN -> null
        }
        val signText = signDirectionTag ?: tags["traffic_sign"]
        if (signText != null && signText.contains("maxspeed", ignoreCase = true) &&
            tags["maxspeed"] == null
        ) {
            return LimitResolution.Unavailable("Знак ограничения отмечен в OSM, но значение maxspeed не указано")
        }

        val belarusZone = tags["zone:traffic"] ?: tags["source:maxspeed"] ?: tags["maxspeed:type"]
        if (belarusZone != null) {
            belarusSymbolicLimit(belarusZone)?.let { return LimitResolution.Known(it) }
        }

        if (countryCode == "BY") {
            val highway = tags["highway"]
            val isMotorway = highway == "motorway" || highway == "motorway_link" ||
                tags["motorroad"] == "yes"
            val default = when {
                highway == "living_street" -> 20
                insideSettlement == true -> 60
                insideSettlement == false && isMotorway -> 120
                insideSettlement == false -> 90
                else -> null
            }
            if (default != null) {
                val rule = when {
                    highway == "living_street" -> "BY:living_street — ПДД РБ п.88"
                    insideSettlement == true -> "BY:urban — ПДД РБ п.88"
                    isMotorway -> "BY:motorway — ПДД РБ п.89.1"
                    else -> "BY:rural — ПДД РБ п.89.1"
                }
                return LimitResolution.Known(
                    SpeedLimit.Known(default, SpeedUnit.KILOMETRES_PER_HOUR, rule)
                )
            }
        }
        return LimitResolution.Unavailable("Для этой дороги нет явного ограничения или подтверждённого контекста ПДД")
    }

    private fun belarusSymbolicLimit(raw: String): SpeedLimit.Known? {
        val value = when (raw.trim().uppercase()) {
            "BY:URBAN" -> 60
            "BY:RURAL" -> 90
            "BY:MOTORWAY" -> 120
            "BY:LIVING_STREET" -> 20
            else -> return null
        }
        return SpeedLimit.Known(value, SpeedUnit.KILOMETRES_PER_HOUR, raw.trim())
    }
}
