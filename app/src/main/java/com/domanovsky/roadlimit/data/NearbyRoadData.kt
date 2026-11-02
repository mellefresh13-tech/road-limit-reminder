package com.domanovsky.roadlimit.data

import com.domanovsky.roadlimit.domain.RoadSegment

/**
 * OSM roads plus the administrative/place areas containing the current GPS point.
 * A null country means Overpass could not identify the containing country area.
 * A null settlement state means no reliable settlement area was returned.
 */
data class NearbyRoadData(
    val roads: List<RoadSegment>,
    val countryCode: String?,
    val insideSettlement: Boolean?
)
