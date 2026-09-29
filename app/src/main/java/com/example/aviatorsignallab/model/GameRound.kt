package com.example.aviatorsignallab.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "rounds")
data class GameRound(
    @PrimaryKey
    @ColumnInfo(name = "round_id")
    val roundId: String,

    @ColumnInfo(name = "start_time")
    val startTime: Long,

    @ColumnInfo(name = "end_time")
    var endTime: Long = 0L,

    @ColumnInfo(name = "duration_ms")
    var durationMs: Long = 0L,

    @ColumnInfo(name = "final_multiplier")
    var finalMultiplier: Double = 1.0,

    @ColumnInfo(name = "event_count")
    var eventCount: Int = 0,

    @ColumnInfo(name = "message_count")
    var messageCount: Int = 0,

    @ColumnInfo(name = "crash_detected")
    var crashDetected: Boolean = false,

    @ColumnInfo(name = "protocol_types_seen")
    var protocolTypesSeen: String = "",

    @ColumnInfo(name = "status")
    var status: String = "LIVE"
)
