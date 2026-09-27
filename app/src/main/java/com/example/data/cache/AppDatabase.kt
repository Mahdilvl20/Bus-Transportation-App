package com.example.data.cache

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

@Entity(tableName = "stop_details")
data class StopDetailEntity(
    @PrimaryKey val stopId: Long,
    val stationCode: String?,
    val addressName: String?,
    val fullAddress: String?,
    val hasPanorama: Boolean = false
)

@Dao
interface StopDetailDao {
    @Query("SELECT * FROM stop_details WHERE stopId = :stopId")
    suspend fun getStopDetail(stopId: Long): StopDetailEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: StopDetailEntity)
}

@Database(entities = [StopDetailEntity::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun stopDetailDao(): StopDetailDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "isfahan_bus_database"
                ).build()
                INSTANCE = instance
                instance
            }
        }
    }
}
