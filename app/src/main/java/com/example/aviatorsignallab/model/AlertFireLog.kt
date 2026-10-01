package com.example.aviatorsignallab.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Records every time a pre-crash alert was ACTUALLY published to the UI (not every candidate
 * check - only the one that won arbitration in maybePublishPreCrashAlert). This is intentionally
 * separate from RoundFeature/LiveEvent: it exists purely so real play sessions can be exported and
 * analyzed offline for alert accuracy - lead time before the real crash, and whether the fired
 * multiplier was reasonably close to the round's actual final multiplier.
 */
@Entity(tableName = "alert_fire_log")
data class AlertFireLog(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    @ColumnInfo(name = "round_id")
    val roundId: String,

    // STUTTER, CADENCE, CADENCE_CONFIRMED (cadence fired after stutter already did), or
    // STUTTER_CONFIRMED (stutter fired after cadence already did)
    @ColumnInfo(name = "source")
    val source: String,

    @ColumnInfo(name = "reason")
    val reason: String,

    @ColumnInfo(name = "confidence")
    val confidence: String,

    @ColumnInfo(name = "target_mode")
    val targetMode: String, // "ALL_2X" or "TARGET_10X"

    @ColumnInfo(name = "fire_wall_time")
    val fireWallTime: Long,

    @ColumnInfo(name = "multiplier_at_fire")
    val multiplierAtFire: Double,

    // Filled in later once the round actually crashes (updateOutcome); -1 means not yet known.
    @ColumnInfo(name = "round_final_multiplier")
    var roundFinalMultiplier: Double = -1.0,

    @ColumnInfo(name = "round_crash_wall_time")
    var roundCrashWallTime: Long = -1L,

    // round_crash_wall_time - fire_wall_time: positive = alert fired BEFORE the crash (good),
    // negative would mean it fired after (should never happen, flags a bug if seen).
    @ColumnInfo(name = "lead_time_ms")
    var leadTimeMs: Long = -1L
)
