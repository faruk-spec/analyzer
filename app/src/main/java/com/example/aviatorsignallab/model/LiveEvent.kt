package com.example.aviatorsignallab.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "live_events",
    indices = [
        Index(value = ["round_id"]),
        Index(value = ["timestamp"]),
        Index(value = ["is_post_crash"])
    ]
)
data class LiveEvent(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,

    @ColumnInfo(name = "round_id")
    val roundId: String,

    @ColumnInfo(name = "timestamp")
    val timestamp: Long,

    @ColumnInfo(name = "elapsed_ms")
    val elapsedMs: Long,

    @ColumnInfo(name = "relative_to_crash_ms")
    var relativeToCrashMs: Long? = null,

    @ColumnInfo(name = "direction")
    val direction: String, // INCOMING, OUTGOING, INTERNAL

    @ColumnInfo(name = "transport")
    val transport: String, // WEBSOCKET, FETCH, XHR, SSE, POST_MESSAGE, DOM

    @ColumnInfo(name = "event_type")
    val eventType: String,

    @ColumnInfo(name = "command")
    val command: String? = null,

    @ColumnInfo(name = "message_size")
    val messageSize: Int = 0,

    @ColumnInfo(name = "raw_preview")
    val rawPreview: String = "",

    @ColumnInfo(name = "parsed_fields_json")
    val parsedFieldsJson: String = "{}",

    @ColumnInfo(name = "multiplier")
    val multiplier: Double? = null,

    @ColumnInfo(name = "is_post_crash")
    var isPostCrash: Boolean = false
)
