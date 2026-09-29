package com.example.aviatorsignallab.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "discovered_patterns")
data class DiscoveredPattern(
    @PrimaryKey
    @ColumnInfo(name = "pattern_id")
    val patternId: String,

    @ColumnInfo(name = "pattern_type")
    val patternType: String, // SEQUENCE, TIMING_BURST, FIELD_MUTATION, QUIET_PERIOD

    @ColumnInfo(name = "descriptor")
    val descriptor: String,

    @ColumnInfo(name = "window_name")
    val windowName: String, // e.g. T-0.1s to T-5.0s

    @ColumnInfo(name = "crash_support")
    val crashSupport: Int,

    @ColumnInfo(name = "control_support")
    val controlSupport: Int,

    @ColumnInfo(name = "precision")
    val precision: Double,

    @ColumnInfo(name = "recall")
    val recall: Double,

    @ColumnInfo(name = "false_positive_rate")
    val falsePositiveRate: Double,

    @ColumnInfo(name = "is_validated")
    val isValidated: Boolean = false,

    @ColumnInfo(name = "discovered_timestamp")
    val discoveredTimestamp: Long = System.currentTimeMillis()
)
