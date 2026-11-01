package com.domanovsky.roadlimit.domain

enum class SpeedUnit {
    KILOMETRES_PER_HOUR,
    MILES_PER_HOUR
}

sealed interface SpeedLimit {
    data class Known(
        val value: Int,
        val unit: SpeedUnit,
        val rawTag: String
    ) : SpeedLimit

    data class NotNumeric(val rawTag: String) : SpeedLimit
    data object Missing : SpeedLimit
    data class Conditional(val rawTag: String) : SpeedLimit
}
