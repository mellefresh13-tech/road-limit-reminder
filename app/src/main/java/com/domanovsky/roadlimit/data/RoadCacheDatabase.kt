package com.domanovsky.roadlimit.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase

@Entity(tableName = "nearby_road_cache")
data class RoadCacheEntity(
    @PrimaryKey val id: Int = 1,
    val latitude: Double,
    val longitude: Double,
    val fetchedAtMillis: Long,
    val payload: String
)

@Dao
interface RoadCacheDao {
    @Query("SELECT * FROM nearby_road_cache WHERE id = 1 LIMIT 1")
    fun get(): RoadCacheEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun put(entity: RoadCacheEntity)

    @Query("DELETE FROM nearby_road_cache")
    fun clear()
}

@Database(entities = [RoadCacheEntity::class], version = 1, exportSchema = false)
abstract class RoadCacheDatabase : RoomDatabase() {
    abstract fun roadCacheDao(): RoadCacheDao

    companion object {
        @Volatile private var instance: RoadCacheDatabase? = null

        fun get(context: Context): RoadCacheDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    RoadCacheDatabase::class.java,
                    "road-limit-cache.db"
                ).build().also { instance = it }
            }
    }
}
