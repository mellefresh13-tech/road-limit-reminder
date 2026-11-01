package com.domanovsky.roadlimit.domain

import kotlin.math.*

sealed interface MatchResult {
    data class Matched(val road: RoadSegment, val distanceMetres: Double) : MatchResult
    data object NoMatch : MatchResult
    data object Ambiguous : MatchResult
}

object RoadMatcher {
    private data class Candidate(val road: RoadSegment, val distance: Double, val score: Double)

    fun match(fix: LocationFix, roads: List<RoadSegment>, previousWayId: Long? = null): MatchResult {
        if (!fix.latitude.isFinite() || !fix.longitude.isFinite() ||
            fix.accuracyMetres <= 0f || fix.accuracyMetres > 60f
        ) return MatchResult.NoMatch

        val candidates = roads.mapNotNull { road ->
            bestCandidate(fix, road, previousWayId)
                ?.takeIf { it.distance <= max(25.0, fix.accuracyMetres.toDouble() * 1.25) }
        }.sortedBy { it.score }
        if (candidates.isEmpty()) return MatchResult.NoMatch

        val best = candidates.first()
        val second = candidates.drop(1).firstOrNull { it.road.id != best.road.id }
        if (second != null && abs(second.score - best.score) < 4.0 &&
            limitFingerprint(second.road) != limitFingerprint(best.road)
        ) return MatchResult.Ambiguous
        return MatchResult.Matched(best.road, best.distance)
    }

    private fun bestCandidate(fix: LocationFix, road: RoadSegment, previousWayId: Long?): Candidate? {
        var bestDistance = Double.POSITIVE_INFINITY
        var bestHeadingPenalty = 0.0
        for (index in 0 until road.geometry.lastIndex) {
            val start = road.geometry[index]
            val end = road.geometry[index + 1]
            val distance = distanceToSegment(fix.latitude, fix.longitude, start, end)
            if (distance >= bestDistance) continue
            val segmentBearing = bearing(start, end)
            val headingPenalty = if (fix.speedMetresPerSecond >= 3f) {
                val difference = angleDifference(fix.bearingDegrees.toDouble(), segmentBearing)
                val oneWay = road.tags["oneway"]
                if ((oneWay == "yes" || oneWay == "1" || oneWay == "true") && difference > 100.0) continue
                if (oneWay == "-1" && angleDifference(fix.bearingDegrees.toDouble(), (segmentBearing + 180.0) % 360.0) > 100.0) continue
                if (oneWay == "yes" || oneWay == "1" || oneWay == "true") difference * 0.16
                else min(difference, 180.0 - difference) * 0.12
            } else 0.0
            bestDistance = distance
            bestHeadingPenalty = headingPenalty
        }
        if (!bestDistance.isFinite()) return null
        val continuityBonus = if (road.id == previousWayId) 5.0 else 0.0
        return Candidate(road, bestDistance, bestDistance + bestHeadingPenalty - continuityBonus)
    }

    private fun limitFingerprint(road: RoadSegment): String =
        road.tags["maxspeed:motorcar"] ?: road.tags["maxspeed:motor_vehicle"]
        ?: road.tags["maxspeed"] ?: road.tags["maxspeed:conditional"] ?: "unknown"

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
