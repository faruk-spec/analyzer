package com.example.aviatorsignallab.export

import com.example.aviatorsignallab.model.DiscoveredPattern
import com.example.aviatorsignallab.model.GameRound
import com.example.aviatorsignallab.model.LiveEvent
import com.example.aviatorsignallab.model.RoundFeature
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets

object CsvExporter {

    private val gson = Gson()

    /**
     * Escapes field value according to RFC 4180 standard.
     */
    fun escapeCsvField(value: Any?): String {
        if (value == null) return ""
        val str = value.toString()
        return if (str.contains(",") || str.contains("\"") || str.contains("\n") || str.contains("\r")) {
            "\"" + str.replace("\"", "\"\"") + "\""
        } else {
            str
        }
    }

    fun exportRounds(rounds: List<GameRound>, destinationFile: File) {
        OutputStreamWriter(FileOutputStream(destinationFile), StandardCharsets.UTF_8).use { writer ->
            writer.write("round_id,start_time,end_time,duration_ms,final_multiplier,event_count,message_count,crash_detected,protocol_types_seen,status\n")
            for (r in rounds) {
                val line = listOf(
                    escapeCsvField(r.roundId),
                    r.startTime,
                    r.endTime,
                    r.durationMs,
                    r.finalMultiplier,
                    r.eventCount,
                    r.messageCount,
                    r.crashDetected,
                    escapeCsvField(r.protocolTypesSeen),
                    escapeCsvField(r.status)
                ).joinToString(",")
                writer.write(line + "\n")
            }
        }
    }

    fun exportLiveEvents(events: List<LiveEvent>, destinationFile: File) {
        OutputStreamWriter(FileOutputStream(destinationFile), StandardCharsets.UTF_8).use { writer ->
            writer.write("id,round_id,timestamp,elapsed_ms,relative_to_crash_ms,direction,transport,event_type,command,message_size,raw_preview,multiplier,is_post_crash\n")
            for (e in events) {
                val line = listOf(
                    e.id,
                    escapeCsvField(e.roundId),
                    e.timestamp,
                    e.elapsedMs,
                    e.relativeToCrashMs ?: "",
                    escapeCsvField(e.direction),
                    escapeCsvField(e.transport),
                    escapeCsvField(e.eventType),
                    escapeCsvField(e.command ?: ""),
                    e.messageSize,
                    escapeCsvField(e.rawPreview),
                    e.multiplier ?: "",
                    e.isPostCrash
                ).joinToString(",")
                writer.write(line + "\n")
            }
        }
    }

    fun exportFeatures(features: List<RoundFeature>, destinationFile: File) {
        OutputStreamWriter(FileOutputStream(destinationFile), StandardCharsets.UTF_8).use { writer ->
            writer.write("id,round_id,window_name,window_start_rel_ms,window_end_rel_ms,event_count,message_rate,mean_inter_event_ms,median_inter_event_ms,min_inter_event_ms,max_inter_event_ms,std_inter_event_ms,burstiness,quiet_period_ms,unique_message_types,ngrams_sequence,is_control_window\n")
            for (f in features) {
                val line = listOf(
                    f.id,
                    escapeCsvField(f.roundId),
                    escapeCsvField(f.windowName),
                    f.windowStartRelMs,
                    f.windowEndRelMs,
                    f.eventCount,
                    "%.4f".format(f.messageRate),
                    "%.2f".format(f.meanInterEventMs),
                    "%.2f".format(f.medianInterEventMs),
                    f.minInterEventMs,
                    f.maxInterEventMs,
                    "%.2f".format(f.stdInterEventMs),
                    "%.4f".format(f.burstiness),
                    f.quietPeriodMs,
                    f.uniqueMessageTypes,
                    escapeCsvField(f.ngramsSequence),
                    f.isControlWindow
                ).joinToString(",")
                writer.write(line + "\n")
            }
        }
    }

    fun exportDiscoveredPatterns(patterns: List<DiscoveredPattern>, destinationFile: File) {
        OutputStreamWriter(FileOutputStream(destinationFile), StandardCharsets.UTF_8).use { writer ->
            writer.write("pattern_id,pattern_type,descriptor,window_name,crash_support,control_support,precision,recall,false_positive_rate,is_validated\n")
            for (p in patterns) {
                val line = listOf(
                    escapeCsvField(p.patternId),
                    escapeCsvField(p.patternType),
                    escapeCsvField(p.descriptor),
                    escapeCsvField(p.windowName),
                    p.crashSupport,
                    p.controlSupport,
                    "%.4f".format(p.precision),
                    "%.4f".format(p.recall),
                    "%.4f".format(p.falsePositiveRate),
                    p.isValidated
                ).joinToString(",")
                writer.write(line + "\n")
            }
        }
    }

    fun exportProtocolFields(events: List<LiveEvent>, destinationFile: File) {
        val mapType = object : TypeToken<Map<String, String>>() {}.type
        OutputStreamWriter(FileOutputStream(destinationFile), StandardCharsets.UTF_8).use { writer ->
            writer.write("event_id,round_id,field_path,field_value\n")
            for (e in events) {
                try {
                    val map: Map<String, String> = gson.fromJson(e.parsedFieldsJson, mapType)
                    for ((k, v) in map) {
                        val line = listOf(
                            e.id,
                            escapeCsvField(e.roundId),
                            escapeCsvField(k),
                            escapeCsvField(v)
                        ).joinToString(",")
                        writer.write(line + "\n")
                    }
                } catch (e: Exception) {}
            }
        }
    }
}
