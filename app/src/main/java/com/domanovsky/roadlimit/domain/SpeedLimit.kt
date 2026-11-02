package com.domanovsky.roadlimit.domain

enum class SpeedUnit(val label: String) {
    KILOMETRES_PER_HOUR("км/ч"),
    MILES_PER_HOUR("mph")
}

data class SpeedLimit(
    val value: Int,
    val unit: SpeedUnit,
    val source: String
)
