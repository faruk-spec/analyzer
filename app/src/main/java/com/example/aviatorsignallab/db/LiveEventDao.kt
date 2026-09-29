package com.example.aviatorsignallab.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.aviatorsignallab.model.LiveEvent

@Dao
interface LiveEventDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEvent(event: LiveEvent): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEvents(events: List<LiveEvent>)

    @Query("SELECT * FROM live_events WHERE round_id = :roundId ORDER BY timestamp ASC")
    suspend fun getEventsForRound(roundId: String): List<LiveEvent>

    @Query("SELECT * FROM live_events WHERE round_id = :roundId AND is_post_crash = 0 AND relative_to_crash_ms < 0 ORDER BY timestamp ASC")
    suspend fun getPreCrashEvents(roundId: String): List<LiveEvent>

    @Query("SELECT COUNT(*) FROM live_events")
    suspend fun getTotalEventsCount(): Int

    @Query("SELECT * FROM live_events ORDER BY id DESC LIMIT :limit")
    suspend fun getRecentEvents(limit: Int = 100): List<LiveEvent>

    @Query("SELECT * FROM live_events ORDER BY timestamp ASC")
    suspend fun getAllEvents(): List<LiveEvent>

    @Query("DELETE FROM live_events")
    suspend fun clearAll()
}
