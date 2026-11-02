package com.domanovsky.roadlimit.data

import com.domanovsky.roadlimit.domain.GeoPoint
import com.domanovsky.roadlimit.domain.RoadSegment

data class TrafficSign(
    val id: Long,
    val point: GeoPoint,
    val tags: Map<String, String>
)

data class NearbyRoadData(
    val roads: List<RoadSegment>,
    val countryCode: String?,
    val insideSettlement: Boolean?,
    val signs: List<TrafficSign> = emptyList()
)
