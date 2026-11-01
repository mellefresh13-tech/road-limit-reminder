package com.domanovsky.roadlimit.domain

enum class SpeedUnit(val label: String) {
    KILOMETRES_PER_HOUR("км/ч"),
    MILES_PER_HOUR("mph")
}

sealed interface SpeedLimit {
    data class Known(val value: Int, val unit: SpeedUnit, val rawTag: String) : SpeedLimit
    data class NotNumeric(val rawTag: String) : SpeedLimit
    data class Conditional(val rawTag: String) : SpeedLimit
    data object Missing : SpeedLimit
}
