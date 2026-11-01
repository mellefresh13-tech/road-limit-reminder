package com.domanovsky.roadlimit.domain

/**
 * Parses unconditional numeric OSM maxspeed values.
 *
 * A bare number has no globally safe unit: the country-specific default must
 * be supplied by a later country/context resolver.
 */
object OsmMaxspeedParser {
    private val numericValue = Regex("""^(\d{1,3})(?:\s*(km/?h|kph|mph))?$""", RegexOption.IGNORE_CASE)

    fun parse(rawValue: String?): SpeedLimit {
        val raw = rawValue?.trim()?.takeIf { it.isNotEmpty() }
            ?: return SpeedLimit.Missing

        if (raw.contains('@')) return SpeedLimit.Conditional(raw)
        if (raw.equals("none", ignoreCase = true) ||
            raw.equals("signals", ignoreCase = true) ||
            raw.equals("walk", ignoreCase = true)
        ) {
            return SpeedLimit.NotNumeric(raw)
        }

        val match = numericValue.matchEntire(raw)
            ?: return SpeedLimit.NotNumeric(raw)

        val value = match.groupValues[1].toIntOrNull()
            ?: return SpeedLimit.NotNumeric(raw)
        if (value !in 1..300) return SpeedLimit.NotNumeric(raw)

        val unit = when (match.groupValues[2].lowercase()) {
            "mph" -> SpeedUnit.MILES_PER_HOUR
            "km/h", "kmh", "kph" -> SpeedUnit.KILOMETRES_PER_HOUR
            else -> null
        }

        return SpeedLimit.Known(value, unit, raw)
    }
}
