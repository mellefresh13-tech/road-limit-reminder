package com.domanovsky.roadlimit.data

import com.domanovsky.roadlimit.domain.GeoPoint
import com.domanovsky.roadlimit.domain.LocationFix
import com.domanovsky.roadlimit.domain.SpeedLimit
import com.domanovsky.roadlimit.domain.SpeedUnit
import kotlin.math.*

data class TwoGisEdgeMatch(
    val edge: TwoGisEdge,
    val distanceMetres: Double,
    val offsetMetres: Double,
    val speedLimit: SpeedLimit?
)

object TwoGisEdgeMatcher {
    private data class Projection(
        val distanceMetres: Double,
        val offsetMetres: Double,
        val bearingDegrees: Double
    )

    private data class Candidate(
        val edge: TwoGisEdge,
        val projection: Projection,
        val score: Double,
        val speedLimit: Int?
    )

    fun match(fix: LocationFix, edges: List<TwoGisEdge>): TwoGisEdgeMatch? {
        if (!fix.latitude.isFinite() || !fix.longitude.isFinite() ||
            !fix.accuracyMetres.isFinite() || fix.accuracyMetres <= 0f ||
            fix.accuracyMetres > 80f
        ) return null

        val maxDistance = max(12.0, min(30.0, fix.accuracyMetres * 1.5))
        val hasHeading = fix.speedMetresPerSecond >= 3f && fix.bearingDegrees.isFinite()
        val candidates = edges.mapNotNull { edge ->
            if (edge.id <= 0L || edge.geometry.size < 2) return@mapNotNull null
            val projection = project(fix.latitude, fix.longitude, edge.geometry) ?: return@mapNotNull null
            if (projection.distanceMetres > maxDistance) return@mapNotNull null
            val headingDifference = if (hasHeading) {
                angleDifference(fix.bearingDegrees.toDouble(), projection.bearingDegrees)
            } else {
                0.0
            }
            if (hasHeading && headingDifference > 75.0) return@mapNotNull null
            val speed = speedLimitAt(edge, projection.offsetMetres)
            Candidate(
                edge,
                projection,
                projection.distanceMetres + if (hasHeading) headingDifference * 0.12 else 0.0,
                speed
            )
        }.sortedBy { it.score }

        val best = candidates.firstOrNull() ?: return null
        val second = candidates.drop(1).firstOrNull()
        if (second != null &&
            abs(second.score - best.score) <= 3.5 &&
            second.speedLimit != best.speedLimit
        ) return null

        val limit = best.speedLimit?.let {
            SpeedLimit(it, SpeedUnit.KILOMETRES_PER_HOUR, "2GIS edge ${best.edge.id}")
        }
        return TwoGisEdgeMatch(best.edge, best.projection.distanceMetres, best.projection.offsetMetres, limit)
    }

    private fun speedLimitAt(edge: TwoGisEdge, offsetMetres: Double): Int? {
        if (edge.speedLimits.isNotEmpty()) {
            return edge.speedLimits.firstOrNull {
                offsetMetres >= it.startOffsetMetres - 1.5 &&
                    offsetMetres <= it.endOffsetMetres + 1.5
            }?.speedLimitKmh
        }
        return edge.speedLimitKmh
    }

    private fun project(latitude: Double, longitude: Double, geometry: List<GeoPoint>): Projection? {
        val latitudeScale = 111_320.0
        val longitudeScale = 111_320.0 * cos(Math.toRadians(latitude))
        var offsetBefore = 0.0
        var best: Projection? = null

        for (index in 0 until geometry.lastIndex) {
            val start = geometry[index]
            val end = geometry[index + 1]
            val dx = (end.longitude - start.longitude) * longitudeScale
            val dy = (end.latitude - start.latitude) * latitudeScale
            val px = (longitude - start.longitude) * longitudeScale
            val py = (latitude - start.latitude) * latitudeScale
            val length = hypot(dx, dy)
            if (length < 0.01) continue
            val t = ((px * dx + py * dy) / (length * length)).coerceIn(0.0, 1.0)
            val distance = hypot(px - t * dx, py - t * dy)
            if (best == null || distance < best.distanceMetres) {
                val bearing = (Math.toDegrees(atan2(dx, dy)) + 360.0) % 360.0
                best = Projection(distance, offsetBefore + t * length, bearing)
            }
            offsetBefore += length
        }
        return best
    }

    private fun angleDifference(first: Double, second: Double): Double {
        val difference = abs((first - second) % 360.0)
        return min(difference, 360.0 - difference)
    }
}
