package com.domanovsky.roadlimit.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenSpeedDatabaseTest {
    @Test
    fun decodesGoogleEncodedPolylineCoordinates() {
        val points = EncodedRoadPolyline.decode("_p~iF~ps|U_ulLnnqC_mqNvxq\u0060@")

        assertEquals(3, points.size)
        assertEquals(38.5, points[0].first, 0.00001)
        assertEquals(-120.2, points[0].second, 0.00001)
        assertEquals(40.7, points[1].first, 0.00001)
        assertEquals(-120.95, points[1].second, 0.00001)
        assertEquals(43.252, points[2].first, 0.00001)
        assertEquals(-126.453, points[2].second, 0.00001)
    }

    @Test
    fun rejectsMalformedPolylineInsteadOfReturningPartialCoordinates() {
        val failure = runCatching { EncodedRoadPolyline.decode("_p~") }
        assertTrue(failure.isFailure)
    }

    @Test
    fun projectsGpsPositionOntoRoadGeometry() {
        val points = listOf(
            53.0 to 27.0,
            53.0 to 27.001
        )

        val projection = RoadGeometryMatcher.project(
            latitude = 53.00001,
            longitude = 27.0005,
            bearingDegrees = 90f,
            speedMetresPerSecond = 10f,
            points = points
        )

        assertNotNull(projection)
        assertTrue(projection!!.distanceMetres < 2.0)
        assertTrue(projection.score >= projection.distanceMetres)
    }

    @Test
    fun ignoresHeadingWhenVehicleIsStationary() {
        val points = listOf(
            53.0 to 27.0,
            53.0 to 27.001
        )

        val forward = RoadGeometryMatcher.project(
            53.00001, 27.0005, 90f, 0f, points
        )
        val reverse = RoadGeometryMatcher.project(
            53.00001, 27.0005, 270f, 0f, points
        )

        assertNotNull(forward)
        assertNotNull(reverse)
        assertEquals(forward!!.score, reverse!!.score, 0.0001)
    }
}
