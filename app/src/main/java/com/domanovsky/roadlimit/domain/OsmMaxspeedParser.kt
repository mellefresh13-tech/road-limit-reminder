package com.domanovsky.roadlimit.domain

/** OSM numeric maxspeed values default to km/h when no unit is written. */
object OsmMaxspeedParser {
    private val numericValue = Regex("""^(\d{1,3})(?:\s*(km/?h|kph|mph))?$""", RegexOption.IGNORE_CASE)

    fun parse(rawValue: String?): SpeedLimit {
        val raw = rawValue?.trim()?.takeIf { it.isNotEmpty() } ?: return SpeedLimit.Missing
        if (raw.contains('@')) return SpeedLimit.Conditional(raw)
        val match = numericValue.matchEntire(raw) ?: return SpeedLimit.NotNumeric(raw)
        val value = match.groupValues[1].toIntOrNull() ?: return SpeedLimit.NotNumeric(raw)
        if (value !in 1..300) return SpeedLimit.NotNumeric(raw)
        val unit = if (match.groupValues[2].lowercase() == "mph") {
            SpeedUnit.MILES_PER_HOUR
        } else {
            SpeedUnit.KILOMETRES_PER_HOUR
        }
        return SpeedLimit.Known(value, unit, raw)
    }
}
