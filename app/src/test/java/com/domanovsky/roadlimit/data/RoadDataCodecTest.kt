package com.domanovsky.roadlimit.data

import com.domanovsky.roadlimit.domain.GeoPoint
import com.domanovsky.roadlimit.domain.RoadSegment
import org.junit.Assert.assertEquals
import org.junit.Test

class RoadDataCodecTest {
    @Test
    fun roundTripsRoadsSignsAndCountryContext() {
        val original = NearbyRoadData(
            roads = listOf(RoadSegment(
                12L, "Test road", "primary",
                mapOf("maxspeed:forward" to "40", "highway" to "primary"),
                listOf(GeoPoint(53.9, 27.55), GeoPoint(53.91, 27.56))
            )),
            countryCode = "BY",
            insideSettlement = true,
            signs = listOf(TrafficSign(77L, GeoPoint(53.9, 27.55), mapOf("traffic_sign" to "BY:3.24", "maxspeed" to "40")))
        )
        val decoded = RoadDataCodec.decode(RoadDataCodec.encode(original))
        assertEquals(original, decoded)
    }
}
