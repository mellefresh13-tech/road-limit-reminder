package com.domanovsky.roadlimit.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RoadMatcherTest {
    @Test
    fun matchesRoadAndDeterminesTravelDirection() {
        val road = RoadSegment(
            id = 10, name = "Test road", highway = "residential",
            tags = mapOf("maxspeed:forward" to "40", "maxspeed:backward" to "60"),
            geometry = listOf(GeoPoint(53.9000, 27.5500), GeoPoint(53.9010, 27.5500))
        )
        val forward = RoadMatcher.match(LocationFix(53.9005, 27.55005, 5f, 0f, 8f), listOf(road))
        assertTrue(forward is MatchResult.Matched)
        assertEquals(RoadDirection.FORWARD, (forward as MatchResult.Matched).direction)

        val backward = RoadMatcher.match(LocationFix(53.9005, 27.55005, 5f, 180f, 8f), listOf(road))
        assertTrue(backward is MatchResult.Matched)
        assertEquals(RoadDirection.BACKWARD, (backward as MatchResult.Matched).direction)
    }

    @Test
    fun rejectsGpsFixFarFromRoad() {
        val road = RoadSegment(
            id = 11, name = null, highway = "primary", tags = mapOf("maxspeed" to "80"),
            geometry = listOf(GeoPoint(53.9, 27.55), GeoPoint(53.901, 27.55))
        )
        assertEquals(MatchResult.NoMatch, RoadMatcher.match(
            LocationFix(53.9, 27.552, 5f, 0f, 10f), listOf(road)
        ))
    }

    @Test
    fun rejectsOppositeDirectionOnOneWayRoad() {
        val road = RoadSegment(
            id = 12, name = null, highway = "primary",
            tags = mapOf("maxspeed" to "80", "oneway" to "yes"),
            geometry = listOf(GeoPoint(53.9000, 27.55), GeoPoint(53.9010, 27.55))
        )
        assertEquals(MatchResult.NoMatch, RoadMatcher.match(
            LocationFix(53.9005, 27.55, 4f, 180f, 12f), listOf(road)
        ))
    }

    @Test
    fun reportsAmbiguousCrossingWhenLimitsDiffer() {
        val northSouth = RoadSegment(
            id = 20, name = "North-South", highway = "primary", tags = mapOf("maxspeed" to "60"),
            geometry = listOf(GeoPoint(53.899, 27.55), GeoPoint(53.901, 27.55))
        )
        val eastWest = RoadSegment(
            id = 21, name = "East-West", highway = "secondary", tags = mapOf("maxspeed" to "40"),
            geometry = listOf(GeoPoint(53.9, 27.549), GeoPoint(53.9, 27.551))
        )
        assertEquals(MatchResult.Ambiguous, RoadMatcher.match(
            LocationFix(53.9, 27.55, 3f, 0f, 0f), listOf(northSouth, eastWest)
        ))
    }
}
