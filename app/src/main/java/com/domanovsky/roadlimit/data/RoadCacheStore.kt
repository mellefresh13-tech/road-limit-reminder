package com.domanovsky.roadlimit.data

import android.content.Context
import kotlin.math.*

data class CachedRoadData(val data: NearbyRoadData, val fetchedAtMillis: Long)

class RoadCacheStore(context: Context) {
    private val dao = RoadCacheDatabase.get(context).roadCacheDao()

    /** All methods are called from the service's background executor. */
    fun read(latitude: Double, longitude: Double, now: Long = System.currentTimeMillis()): CachedRoadData? {
        val entity = dao.get() ?: return null
        if (now - entity.fetchedAtMillis > MAX_CACHE_AGE_MILLIS) return null
        if (distanceMetres(latitude, longitude, entity.latitude, entity.longitude) > CACHE_RADIUS_METRES) return null
        return runCatching { CachedRoadData(RoadDataCodec.decode(entity.payload), entity.fetchedAtMillis) }.getOrNull()
    }

    fun write(latitude: Double, longitude: Double, data: NearbyRoadData, now: Long = System.currentTimeMillis()) {
        dao.put(RoadCacheEntity(
            latitude = latitude,
            longitude = longitude,
            fetchedAtMillis = now,
            payload = RoadDataCodec.encode(data)
        ))
    }

    private fun distanceMetres(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2) + cos(Math.toRadians(lat1)) *
            cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
        return 6_371_000.0 * 2 * atan2(sqrt(a), sqrt(1 - a))
    }

    companion object {
        const val CACHE_RADIUS_METRES = 650.0
        const val MAX_CACHE_AGE_MILLIS = 24 * 60 * 60 * 1000L
    }
}
