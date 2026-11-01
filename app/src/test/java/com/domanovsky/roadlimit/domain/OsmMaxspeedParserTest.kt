package com.domanovsky.roadlimit.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class OsmMaxspeedParserTest {
    @Test
    fun parsesBareNumberWithoutAssumingGlobalUnit() {
        assertEquals(
            SpeedLimit.Known(60, null, "60"),
            OsmMaxspeedParser.parse("60")
        )
    }

    @Test
    fun parsesExplicitMph() {
        assertEquals(
            SpeedLimit.Known(30, SpeedUnit.MILES_PER_HOUR, "30 mph"),
            OsmMaxspeedParser.parse("30 mph")
        )
    }

    @Test
    fun parsesExplicitKph() {
        assertEquals(
            SpeedLimit.Known(90, SpeedUnit.KILOMETRES_PER_HOUR, "90 km/h"),
            OsmMaxspeedParser.parse("90 km/h")
        )
        assertEquals(
            SpeedLimit.Known(90, SpeedUnit.KILOMETRES_PER_HOUR, "90 kph"),
            OsmMaxspeedParser.parse("90 kph")
        )
    }

    @Test
    fun doesNotGuessMissingOrSymbolicValues() {
        assertEquals(SpeedLimit.Missing, OsmMaxspeedParser.parse(null))
        assertEquals(SpeedLimit.NotNumeric("signals"), OsmMaxspeedParser.parse("signals"))
        assertEquals(SpeedLimit.NotNumeric("none"), OsmMaxspeedParser.parse("none"))
        assertEquals(SpeedLimit.NotNumeric("walk"), OsmMaxspeedParser.parse("walk"))
    }

    @Test
    fun leavesConditionalValuesUnresolved() {
        assertEquals(
            SpeedLimit.Conditional("50 @ (Mo-Fr 07:00-19:00)"),
            OsmMaxspeedParser.parse("50 @ (Mo-Fr 07:00-19:00)")
        )
    }

    @Test
    fun rejectsOutOfRangeAndMalformedValues() {
        assertEquals(SpeedLimit.NotNumeric("500"), OsmMaxspeedParser.parse("500"))
        assertEquals(SpeedLimit.NotNumeric("fast"), OsmMaxspeedParser.parse("fast"))
    }
}
