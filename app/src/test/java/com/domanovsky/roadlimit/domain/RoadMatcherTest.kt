package com.domanovsky.roadlimit.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RoadMatcherTest {
    @Test
    fun matchesRoadCloseToGpsPosition() {
        val road = RoadSegment(
            id = 10,
            name = "Test road",
            highway = "residential",
            tags = mapOf("maxspeed" to "50"),
            geometry = listOf(GeoPoint(53.9000, 27.5500), GeoPoint(53.9010, 27.5500))
        )
        val result = RoadMatcher.match(
            LocationFix(53.9005, 27.55005, 5f, 0f, 8f),
            listOf(road)
        )
        assertTrue(result is MatchResult.Matched)
        assertEquals(10L, (result as MatchResult.Matched).road.id)
    }

    @Test
    fun rejectsGpsFixFarFromRoad() {
        val road = RoadSegment(
            id = 11,
            name = null,
            highway = "primary",
            tags = mapOf("maxspeed" to "80"),
            geometry = listOf(GeoPoint(53.9, 27.55), GeoPoint(53.901, 27.55))
        )
        assertEquals(
            MatchResult.NoMatch,
            RoadMatcher.match(LocationFix(53.9, 27.552, 5f, 0f, 10f), listOf(road))
        )
    }

    @Test
    fun rejectsOppositeDirectionOnOneWayRoad() {
        val road = RoadSegment(
            id = 12,
            name = null,
            highway = "primary",
            tags = mapOf("maxspeed" to "80", "oneway" to "yes"),
            geometry = listOf(GeoPoint(53.9000, 27.55), GeoPoint(53.9010, 27.55))
        )
        assertEquals(
            MatchResult.NoMatch,
            RoadMatcher.match(LocationFix(53.9005, 27.55, 4f, 180f, 12f), listOf(road))
        )
    }
}
