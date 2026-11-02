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
        val urban = (RoadLimitResolver.resolve(
            mapOf("highway" to "primary"), countryCode = "BY", insideSettlement = true
        ) as LimitResolution.Known).limit
        val rural = (RoadLimitResolver.resolve(
            mapOf("highway" to "primary"), countryCode = "BY", insideSettlement = false
        ) as LimitResolution.Known).limit
        assertEquals(60, urban.value)
        assertEquals("BY:urban — ПДД РБ п.88", urban.rawTag)
        assertEquals(90, rural.value)
        assertEquals("BY:rural — ПДД РБ п.89.1", rural.rawTag)
    }

    @Test
    fun resolvesBelarusMotorwayAndLivingStreetDefaults() {
        val motorway = RoadLimitResolver.resolve(
            mapOf("highway" to "motorway"), countryCode = "BY", insideSettlement = false
        ) as LimitResolution.Known
        val livingStreet = RoadLimitResolver.resolve(
            mapOf("highway" to "living_street"), countryCode = "BY", insideSettlement = null
        ) as LimitResolution.Known
        assertEquals(120, motorway.limit.value)
        assertEquals(20, livingStreet.limit.value)
    }

    @Test
    fun resolvesBelarusSymbolicMaxspeedAndSourceTags() {
        val urban = RoadLimitResolver.resolve(mapOf("maxspeed" to "BY:urban")) as LimitResolution.Known
        val rural = RoadLimitResolver.resolve(
            mapOf("highway" to "secondary", "source:maxspeed" to "BY:rural"), countryCode = "BY"
        ) as LimitResolution.Known
        assertEquals(60, urban.limit.value)
        assertEquals(90, rural.limit.value)
    }

    @Test
    fun resolvesDirectionSpecificLimitsOnlyForMatchingDirection() {
        val tags = mapOf("highway" to "primary", "maxspeed:forward" to "40", "maxspeed:backward" to "60")
        assertEquals(40, (RoadLimitResolver.resolve(tags, direction = RoadDirection.FORWARD) as LimitResolution.Known).limit.value)
        assertEquals(60, (RoadLimitResolver.resolve(tags, direction = RoadDirection.BACKWARD) as LimitResolution.Known).limit.value)
        assertTrue(RoadLimitResolver.resolve(tags, direction = RoadDirection.UNKNOWN) is LimitResolution.Unavailable)
    }

    @Test
    fun refusesConditionalVariableLaneAndUnresolvedSignLimits() {
        assertTrue(RoadLimitResolver.resolve(mapOf("maxspeed" to "60", "maxspeed:conditional" to "40 @ wet")) is LimitResolution.Unavailable)
        assertTrue(RoadLimitResolver.resolve(mapOf("maxspeed:variable" to "yes")) is LimitResolution.Unavailable)
        assertTrue(RoadLimitResolver.resolve(mapOf("maxspeed:lanes" to "60|40")) is LimitResolution.Unavailable)
        assertTrue(RoadLimitResolver.resolve(mapOf("traffic_sign:forward" to "maxspeed"), direction = RoadDirection.FORWARD) is LimitResolution.Unavailable)
    }

    @Test
    fun doesNotGuessWhenCountryOrSettlementContextIsUnknown() {
        assertTrue(RoadLimitResolver.resolve(
            mapOf("highway" to "primary"), countryCode = "BY", insideSettlement = null
        ) is LimitResolution.Unavailable)
        assertTrue(RoadLimitResolver.resolve(mapOf("highway" to "residential")) is LimitResolution.Unavailable)
    }
}
