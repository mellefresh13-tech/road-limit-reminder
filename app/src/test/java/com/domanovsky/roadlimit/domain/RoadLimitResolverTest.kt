package com.domanovsky.roadlimit.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RoadLimitResolverTest {
    @Test
    fun resolvesExplicitNumericLimit() {
        val result = RoadLimitResolver.resolve(mapOf("highway" to "residential", "maxspeed" to "60"))
        assertEquals(
            LimitResolution.Known(SpeedLimit.Known(60, SpeedUnit.KILOMETRES_PER_HOUR, "60")),
            result
        )
    }

    @Test
    fun resolvesBelarusUrbanAndRuralDefaultsFromOsmContext() {
        val urban = RoadLimitResolver.resolve(
            mapOf("highway" to "primary"),
            countryCode = "BY",
            insideSettlement = true
        )
        val rural = RoadLimitResolver.resolve(
            mapOf("highway" to "primary"),
            countryCode = "BY",
            insideSettlement = false
        )
        assertEquals(60, (urban as LimitResolution.Known).limit.value)
        assertEquals("BY:urban — ПДД РБ п.88", urban.limit.rawTag)
        assertEquals(90, (rural as LimitResolution.Known).limit.value)
        assertEquals("BY:rural — ПДД РБ п.89.1", rural.limit.rawTag)
    }

    @Test
    fun resolvesBelarusMotorwayAndLivingStreetDefaults() {
        val motorway = RoadLimitResolver.resolve(
            mapOf("highway" to "motorway"),
            countryCode = "BY",
            insideSettlement = false
        )
        val livingStreet = RoadLimitResolver.resolve(
            mapOf("highway" to "living_street"),
            countryCode = "BY",
            insideSettlement = null
        )
        assertEquals(120, (motorway as LimitResolution.Known).limit.value)
        assertEquals(20, (livingStreet as LimitResolution.Known).limit.value)
    }

    @Test
    fun resolvesBelarusSymbolicMaxspeedAndSourceTags() {
        assertEquals(
            60,
            (RoadLimitResolver.resolve(mapOf("maxspeed" to "BY:urban")) as LimitResolution.Known).limit.value
        )
        assertEquals(
            90,
            (RoadLimitResolver.resolve(
                mapOf("highway" to "secondary", "source:maxspeed" to "BY:rural"),
                countryCode = "BY"
            ) as LimitResolution.Known).limit.value
        )
    }

    @Test
    fun doesNotGuessWhenCountryOrSettlementContextIsUnknown() {
        assertTrue(
            RoadLimitResolver.resolve(
                mapOf("highway" to "primary"),
                countryCode = "BY",
                insideSettlement = null
            ) is LimitResolution.Unavailable
        )
        assertTrue(RoadLimitResolver.resolve(mapOf("highway" to "residential")) is LimitResolution.Unavailable)
    }

    @Test
    fun refusesConditionalVariableLaneAndDirectionSpecificLimits() {
        assertTrue(RoadLimitResolver.resolve(mapOf("maxspeed" to "60", "maxspeed:conditional" to "40 @ wet")) is LimitResolution.Unavailable)
        assertTrue(RoadLimitResolver.resolve(mapOf("maxspeed" to "60", "maxspeed:variable" to "yes")) is LimitResolution.Unavailable)
        assertTrue(RoadLimitResolver.resolve(mapOf("maxspeed" to "60", "maxspeed:lanes" to "60|40")) is LimitResolution.Unavailable)
        assertTrue(RoadLimitResolver.resolve(mapOf("maxspeed:forward" to "60")) is LimitResolution.Unavailable)
    }
}
