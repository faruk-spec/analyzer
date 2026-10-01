package com.example.aviatorsignallab.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.example.aviatorsignallab.model.AlertFireLog

@Dao
interface AlertFireLogDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(log: AlertFireLog): Long

    @Update
    suspend fun update(log: AlertFireLog)

    @Query("SELECT * FROM alert_fire_log WHERE round_id = :roundId ORDER BY id ASC")
    suspend fun getForRound(roundId: String): List<AlertFireLog>

    @Query("SELECT * FROM alert_fire_log ORDER BY fire_wall_time ASC")
    suspend fun getAll(): List<AlertFireLog>

    @Query("DELETE FROM alert_fire_log")
    suspend fun clearAll()
}
