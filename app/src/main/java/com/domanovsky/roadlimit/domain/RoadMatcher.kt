package com.domanovsky.roadlimit.domain

import kotlin.math.*

sealed interface MatchResult {
    data class Matched(
        val road: RoadSegment,
        val distanceMetres: Double,
        val direction: RoadDirection
    ) : MatchResult
    data object NoMatch : MatchResult
    data object Ambiguous : MatchResult
}

object RoadMatcher {
    private data class Candidate(
        val road: RoadSegment,
        val distance: Double,
        val score: Double,
        val direction: RoadDirection
    )

    fun match(fix: LocationFix, roads: List<RoadSegment>, previousWayId: Long? = null): MatchResult {
        if (!fix.latitude.isFinite() || !fix.longitude.isFinite() ||
            fix.accuracyMetres <= 0f || fix.accuracyMetres > 40f
        ) return MatchResult.NoMatch

        val candidates = roads.mapNotNull { road ->
            bestCandidate(fix, road, previousWayId)
                ?.takeIf { it.distance <= max(15.0, min(30.0, fix.accuracyMetres.toDouble() * 1.5)) }
        }.sortedBy { it.score }
        if (candidates.isEmpty()) return MatchResult.NoMatch

        val best = candidates.first()
        val second = candidates.drop(1).firstOrNull { it.road.id != best.road.id }
        if (second != null && abs(second.score - best.score) < 4.0 &&
            (limitFingerprint(second.road) != limitFingerprint(best.road) ||
                second.direction != best.direction)
        ) return MatchResult.Ambiguous
        return MatchResult.Matched(best.road, best.distance, best.direction)
    }

    private fun bestCandidate(fix: LocationFix, road: RoadSegment, previousWayId: Long?): Candidate? {
        var bestDistance = Double.POSITIVE_INFINITY
        var bestHeadingPenalty = 0.0
        var bestDirection = RoadDirection.UNKNOWN
        for (index in 0 until road.geometry.lastIndex) {
            val start = road.geometry[index]
            val end = road.geometry[index + 1]
            val distance = distanceToSegment(fix.latitude, fix.longitude, start, end)
            if (distance >= bestDistance) continue
            val segmentBearing = bearing(start, end)
            val hasHeading = fix.speedMetresPerSecond >= 3f
            val difference = if (hasHeading) angleDifference(fix.bearingDegrees.toDouble(), segmentBearing) else 180.0
            val oneWay = road.tags["oneway"]
            if (hasHeading && (oneWay == "yes" || oneWay == "1" || oneWay == "true") && difference > 100.0) continue
            if (hasHeading && oneWay == "-1" && angleDifference(fix.bearingDegrees.toDouble(), (segmentBearing + 180.0) % 360.0) > 100.0) continue
            bestDistance = distance
            bestDirection = when {
                !hasHeading -> RoadDirection.UNKNOWN
                difference <= 90.0 -> RoadDirection.FORWARD
                else -> RoadDirection.BACKWARD
            }
            bestHeadingPenalty = if (hasHeading) {
                if (oneWay == "yes" || oneWay == "1" || oneWay == "true") difference * 0.16
                else min(difference, 180.0 - difference) * 0.12
            } else 0.0
        }
        if (!bestDistance.isFinite()) return null
        val continuityBonus = if (road.id == previousWayId) 3.0 else 0.0
        return Candidate(road, bestDistance, bestDistance + bestHeadingPenalty - continuityBonus, bestDirection)
    }

    private fun limitFingerprint(road: RoadSegment): String =
        listOf("maxspeed:motorcar", "maxspeed:motor_vehicle", "maxspeed",
            "maxspeed:forward", "maxspeed:backward", "maxspeed:conditional")
            .mapNotNull { key -> road.tags[key]?.let { "$key=$it" } }.joinToString("|").ifBlank { "unknown" }

    private fun distanceToSegment(lat: Double, lon: Double, start: GeoPoint, end: GeoPoint): Double {
        val latitudeScale = 111_320.0
        val longitudeScale = 111_320.0 * cos(Math.toRadians(lat))
        val px = (lon - start.longitude) * longitudeScale
        val py = (lat - start.latitude) * latitudeScale
        val dx = (end.longitude - start.longitude) * longitudeScale
        val dy = (end.latitude - start.latitude) * latitudeScale
        val lengthSquared = dx * dx + dy * dy
        val t = if (lengthSquared == 0.0) 0.0 else ((px * dx + py * dy) / lengthSquared).coerceIn(0.0, 1.0)
        return hypot(px - t * dx, py - t * dy)
    }

    private fun bearing(start: GeoPoint, end: GeoPoint): Double {
        val lat1 = Math.toRadians(start.latitude)
        val lat2 = Math.toRadians(end.latitude)
        val deltaLon = Math.toRadians(end.longitude - start.longitude)
        val y = sin(deltaLon) * cos(lat2)
        val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(deltaLon)
        return (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
    }

    private fun angleDifference(first: Double, second: Double): Double {
        val difference = abs((first - second) % 360.0)
        return min(difference, 360.0 - difference)
    }
}
