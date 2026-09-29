package com.example.aviatorsignallab.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "round_features",
    indices = [
        Index(value = ["round_id"]),
        Index(value = ["window_name"]),
        Index(value = ["is_control_window"])
    ]
)
data class RoundFeature(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,

    @ColumnInfo(name = "round_id")
    val roundId: String,

    @ColumnInfo(name = "window_name")
    val windowName: String, // e.g. T-5.0s, T-3.0s, T-1.0s, T-0.1s, CONTROL_MATCHED

    @ColumnInfo(name = "window_start_rel_ms")
    val windowStartRelMs: Long,

    @ColumnInfo(name = "window_end_rel_ms")
    val windowEndRelMs: Long,

    @ColumnInfo(name = "event_count")
    val eventCount: Int,

    @ColumnInfo(name = "message_rate")
    val messageRate: Double,

    @ColumnInfo(name = "mean_inter_event_ms")
    val meanInterEventMs: Double,

    @ColumnInfo(name = "median_inter_event_ms")
    val medianInterEventMs: Double,

    @ColumnInfo(name = "min_inter_event_ms")
    val minInterEventMs: Long,

    @ColumnInfo(name = "max_inter_event_ms")
    val maxInterEventMs: Long,

    @ColumnInfo(name = "std_inter_event_ms")
    val stdInterEventMs: Double,

    @ColumnInfo(name = "burstiness")
    val burstiness: Double,

    @ColumnInfo(name = "quiet_period_ms")
    val quietPeriodMs: Long,

    @ColumnInfo(name = "unique_message_types")
    val uniqueMessageTypes: Int,

    @ColumnInfo(name = "ngrams_sequence")
    val ngramsSequence: String = "",

    @ColumnInfo(name = "is_control_window")
    val isControlWindow: Boolean = false
)
