package com.domanovsky.roadlimit.domain

import com.domanovsky.roadlimit.data.TrafficSign
import kotlin.math.*

/**
 * Uses a numeric 3.24 OSM sign only on the same mapped way, in its explicit
 * direction, after passing the sign. It never extrapolates beyond that way.
 */
object TrafficSignResolver {
    private data class Projection(val alongMetres: Double, val distanceMetres: Double)

    fun resolve(
        road: RoadSegment,
        fix: LocationFix,
        signs: List<TrafficSign>,
        allRoads: List<RoadSegment>,
        direction: RoadDirection
    ): SpeedLimit.Known? {
        if (direction == RoadDirection.UNKNOWN) return null
        val current = project(GeoPoint(fix.latitude, fix.longitude), road.geometry) ?: return null
        if (current.distanceMetres > 30.0) return null

        val candidates = signs.mapNotNull { sign ->
            val signKind = sign.tags["traffic_sign"].orEmpty()
            if (!Regex("""(^|[^0-9])3\.24([^0-9]|$)""").containsMatchIn(signKind)) return@mapNotNull null
            val signDirection = when (sign.tags["direction"]?.lowercase()) {
                "forward" -> RoadDirection.FORWARD
                "backward" -> RoadDirection.BACKWARD
                else -> when {
                    sign.tags.containsKey("traffic_sign:forward") -> RoadDirection.FORWARD
                    sign.tags.containsKey("traffic_sign:backward") -> RoadDirection.BACKWARD
                    else -> RoadDirection.UNKNOWN
                }
            }
            if (signDirection != direction) return@mapNotNull null
            val raw = sign.tags["maxspeed"] ?: sign.tags["maxspeed:motorcar"] ?: return@mapNotNull null
            val parsed = OsmMaxspeedParser.parse(raw) as? SpeedLimit.Known ?: return@mapNotNull null
            val signProjection = project(sign.point, road.geometry) ?: return@mapNotNull null
            if (signProjection.distanceMetres > 15.0) return@mapNotNull null
            val nearestRoads = allRoads.mapNotNull { candidate ->
                project(sign.point, candidate.geometry)?.let { candidate to it.distanceMetres }
            }.sortedBy { it.second }
            if (nearestRoads.firstOrNull()?.first?.id != road.id) return@mapNotNull null
            val secondDistance = nearestRoads.getOrNull(1)?.second
            if (secondDistance != null && secondDistance - signProjection.distanceMetres < 5.0) {
                return@mapNotNull null
            }
            val passed = when (direction) {
                RoadDirection.FORWARD -> current.alongMetres >= signProjection.alongMetres - 5.0
                RoadDirection.BACKWARD -> current.alongMetres <= signProjection.alongMetres + 5.0
                RoadDirection.UNKNOWN -> false
            }
            if (!passed) return@mapNotNull null
            Triple(signProjection.alongMetres, SpeedLimit.Known(
                parsed.value, parsed.unit, parsed.rawTag + " (OSM sign node " + sign.id + ")"
            ))
        }
        return when (direction) {
            RoadDirection.FORWARD -> candidates.maxByOrNull { it.first }?.second
            RoadDirection.BACKWARD -> candidates.minByOrNull { it.first }?.second
            RoadDirection.UNKNOWN -> null
        }
    }

    private fun project(point: GeoPoint, geometry: List<GeoPoint>): Projection? {
        if (geometry.size < 2) return null
        var cumulative = 0.0
        var best: Projection? = null
        for (i in 0 until geometry.lastIndex) {
            val start = geometry[i]
            val end = geometry[i + 1]
            val longitudeScale = 111_320.0 * cos(Math.toRadians(point.latitude))
            val dx = (end.longitude - start.longitude) * longitudeScale
            val dy = (end.latitude - start.latitude) * 111_320.0
            val px = (point.longitude - start.longitude) * longitudeScale
            val py = (point.latitude - start.latitude) * 111_320.0
            val length = hypot(dx, dy)
            val lengthSquared = length * length
            val t = if (lengthSquared == 0.0) 0.0 else ((px * dx + py * dy) / lengthSquared).coerceIn(0.0, 1.0)
            val distance = hypot(px - t * dx, py - t * dy)
            val candidate = Projection(cumulative + t * length, distance)
            if (best == null || distance < best.distanceMetres) best = candidate
            cumulative += length
        }
        return best
    }
}
