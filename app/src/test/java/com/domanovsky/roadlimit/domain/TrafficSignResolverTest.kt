package com.domanovsky.roadlimit.domain

import com.domanovsky.roadlimit.data.TrafficSign
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TrafficSignResolverTest {
    private val road = RoadSegment(
        10L, "Test street", "residential", mapOf("highway" to "residential"),
        listOf(GeoPoint(53.9000, 27.5500), GeoPoint(53.9010, 27.5500))
    )
    private val sign = TrafficSign(
        77L, GeoPoint(53.9003, 27.5500),
        mapOf("traffic_sign" to "BY:3.24", "maxspeed" to "40", "direction" to "forward")
    )

    @Test
    fun appliesNumericSignAfterPassingItInItsDirection() {
        val result = TrafficSignResolver.resolve(
            road, LocationFix(53.9006, 27.55001, 5f, 0f, 8f), listOf(sign), listOf(road), RoadDirection.FORWARD
        )
        assertEquals(40, result?.value)
        assertEquals(true, result?.rawTag?.contains("OSM sign node 77"))
    }

    @Test
    fun doesNotApplySignBeforePassingOrInOppositeDirection() {
        val beforeSign = TrafficSignResolver.resolve(
            road, LocationFix(53.9001, 27.55001, 5f, 0f, 8f), listOf(sign), listOf(road), RoadDirection.FORWARD
        )
        val opposite = TrafficSignResolver.resolve(
            road, LocationFix(53.9006, 27.55001, 5f, 180f, 8f), listOf(sign), listOf(road), RoadDirection.BACKWARD
        )
        assertNull(beforeSign)
        assertNull(opposite)
    }

    @Test
    fun ignoresSignsWithoutDirectionOrNumericValue() {
        val undirected = sign.copy(tags = mapOf("traffic_sign" to "BY:3.24", "maxspeed" to "40"))
        val noValue = sign.copy(tags = mapOf("traffic_sign" to "BY:3.24", "direction" to "forward"))
        val fix = LocationFix(53.9006, 27.55001, 5f, 0f, 8f)
        assertNull(TrafficSignResolver.resolve(road, fix, listOf(undirected), listOf(road), RoadDirection.FORWARD))
        assertNull(TrafficSignResolver.resolve(road, fix, listOf(noValue), listOf(road), RoadDirection.FORWARD))
    }

    @Test
    fun refusesSignThatIsEquallyCloseToAnotherRoad() {
        val parallel = road.copy(id = 11L, name = "Parallel", geometry = road.geometry.map { it.copy(longitude = it.longitude + 0.00002) })
        val fix = LocationFix(53.9006, 27.55001, 5f, 0f, 8f)
        assertNull(TrafficSignResolver.resolve(road, fix, listOf(sign), listOf(road, parallel), RoadDirection.FORWARD))
    }
}
