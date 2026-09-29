package com.example.aviatorsignallab.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.example.aviatorsignallab.model.GameRound

@Dao
interface RoundDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRound(round: GameRound)

    @Update
    suspend fun updateRound(round: GameRound)

    @Query("SELECT * FROM rounds WHERE round_id = :roundId LIMIT 1")
    suspend fun getRound(roundId: String): GameRound?

    @Query("SELECT * FROM rounds ORDER BY start_time DESC")
    suspend fun getAllRounds(): List<GameRound>

    @Query("SELECT * FROM rounds WHERE crash_detected = 1 AND duration_ms > 0 ORDER BY start_time ASC")
    suspend fun getCrashRounds(): List<GameRound>

    @Query("SELECT COUNT(*) FROM rounds")
    suspend fun getRoundsCount(): Int

    @Query("DELETE FROM rounds")
    suspend fun clearAll()
}
