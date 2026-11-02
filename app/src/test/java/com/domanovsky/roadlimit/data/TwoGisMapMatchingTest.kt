package com.domanovsky.roadlimit.data

import com.domanovsky.roadlimit.domain.GeoPoint
import com.domanovsky.roadlimit.domain.LocationFix
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class TwoGisMapMatchingTest {
    @Test
    fun parsesMatchedEdgesAndDetailedSpeedLimitSections() {
        val json = """
            {
              "status": "OK",
              "edges": [{
                "distance": 111.3,
                "edge_id": 12345,
                "geometry": "LINESTRING(27.000000 53.000000,27.001000 53.000000)",
                "speed_limit": 50,
                "speed_limits": [
                  {"start_offset": 0.0, "end_offset": 50.0, "speed_limit": 60},
                  {"start_offset": 50.0, "end_offset": 111.3, "speed_limit": 40}
                ]
              }],
              "query": [{
                "edge_id": 12345,
                "lat_matched": 53.0,
                "lon_matched": 27.000700,
                "query_point_idx": 1
              }]
            }
        """.trimIndent()

        val result = TwoGisResponseParser.parse(json)

        assertEquals(1, result.edges.size)
        assertEquals(12345L, result.currentEdgeId)
        assertNotNull(result.matchedPoint)
        assertEquals(2, result.edges.first().speedLimits.size)
    }

    @Test
    fun choosesDetailedLimitAtCurrentOffsetInsteadOfWeightedEdgeAverage() {
        val edge = TwoGisEdge(
            id = 12345L,
            distanceMetres = 111.3,
            geometry = listOf(GeoPoint(53.0, 27.0), GeoPoint(53.0, 27.001)),
            speedLimitKmh = 50,
            speedLimits = listOf(
                TwoGisSpeedInterval(0.0, 50.0, 60),
                TwoGisSpeedInterval(50.0, 111.3, 40)
            )
        )
        val fix = LocationFix(
            latitude = 53.0,
            longitude = 27.000700,
            accuracyMetres = 5f,
            bearingDegrees = 90f,
            speedMetresPerSecond = 12f
        )

        val match = TwoGisEdgeMatcher.match(fix, listOf(edge))

        assertNotNull(match)
        assertEquals(40, match?.speedLimit?.value)
    }

    @Test
    fun refusesAmbiguousParallelEdgesWithDifferentLimits() {
        val first = TwoGisEdge(
            id = 1L,
            distanceMetres = 111.3,
            geometry = listOf(GeoPoint(53.0, 27.0), GeoPoint(53.0, 27.001)),
            speedLimitKmh = 60,
            speedLimits = emptyList()
        )
        val second = TwoGisEdge(
            id = 2L,
            distanceMetres = 111.3,
            geometry = listOf(GeoPoint(53.000001, 27.0), GeoPoint(53.000001, 27.001)),
            speedLimitKmh = 40,
            speedLimits = emptyList()
        )
        val fix = LocationFix(53.0, 27.0005, 5f, 90f, 10f)

        assertNull(TwoGisEdgeMatcher.match(fix, listOf(first, second)))
    }

    @Test
    fun doesNotInventLimitWhenProviderHasNoSpeedLimit() {
        val edge = TwoGisEdge(
            id = 1L,
            distanceMetres = 111.3,
            geometry = listOf(GeoPoint(53.0, 27.0), GeoPoint(53.0, 27.001)),
            speedLimitKmh = null,
            speedLimits = emptyList()
        )
        val fix = LocationFix(53.0, 27.0005, 5f, 90f, 10f)

        val match = TwoGisEdgeMatcher.match(fix, listOf(edge))

        assertNotNull(match)
        assertNull(match?.speedLimit)
    }
}
