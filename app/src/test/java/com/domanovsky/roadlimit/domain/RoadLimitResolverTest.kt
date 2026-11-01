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
    fun refusesConditionalVariableLaneAndDirectionSpecificLimits() {
        assertTrue(RoadLimitResolver.resolve(mapOf("maxspeed" to "60", "maxspeed:conditional" to "40 @ wet")) is LimitResolution.Unavailable)
        assertTrue(RoadLimitResolver.resolve(mapOf("maxspeed" to "60", "maxspeed:variable" to "yes")) is LimitResolution.Unavailable)
        assertTrue(RoadLimitResolver.resolve(mapOf("maxspeed" to "60", "maxspeed:lanes" to "60|40")) is LimitResolution.Unavailable)
        assertTrue(RoadLimitResolver.resolve(mapOf("maxspeed:forward" to "60")) is LimitResolution.Unavailable)
    }

    @Test
    fun refusesToInventLimitWhenTagIsMissing() {
        assertTrue(RoadLimitResolver.resolve(mapOf("highway" to "residential")) is LimitResolution.Unavailable)
    }
}
