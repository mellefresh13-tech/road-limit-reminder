package com.domanovsky.roadlimit.domain

data class GeoPoint(val latitude: Double, val longitude: Double)

data class LocationFix(
    val latitude: Double,
    val longitude: Double,
    val accuracyMetres: Float,
    val bearingDegrees: Float,
    val speedMetresPerSecond: Float
)
