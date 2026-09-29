package com.example.aviatorsignallab.analysis

import com.example.aviatorsignallab.model.GameRound
import com.example.aviatorsignallab.model.LiveEvent
import com.example.aviatorsignallab.model.RoundFeature
import kotlin.math.pow
import kotlin.math.sqrt

object FeatureExtractor {

    data class WindowDefinition(
        val name: String,
        val startRelMs: Long, // e.g. -5000L
        val endRelMs: Long    // e.g. -100L
    )

    val PRE_CRASH_WINDOWS = listOf(
        WindowDefinition("T_5.0s_TO_0.1s", -5000L, -100L),
        WindowDefinition("T_3.0s_TO_0.1s", -3000L, -100L),
        WindowDefinition("T_2.0s_TO_0.1s", -2000L, -100L),
        WindowDefinition("T_1.0s_TO_0.1s", -1000L, -100L),
        WindowDefinition("T_0.5s_TO_0.1s", -500L, -100L),
        WindowDefinition("T_0.25s_TO_0.1s", -250L, -100L)
    )

    /**
     * Extracts features for all defined pre-crash windows of a completed crash round.
     * Enforces LeakageGuard before computing any feature.
     */
    fun extractPreCrashFeatures(round: GameRound, allEvents: List<LiveEvent>): List<RoundFeature> {
        if (!round.crashDetected || round.endTime <= 0L) return emptyList()

        val crashTimestamp = round.endTime
        val preCrashEvents = LeakageGuard.filterPreCrashOnly(allEvents, crashTimestamp)
        LeakageGuard.assertNoDataLeakage(preCrashEvents, crashTimestamp)

        val features = mutableListOf<RoundFeature>()

        for (win in PRE_CRASH_WINDOWS) {
            val winStartTime = crashTimestamp + win.startRelMs
            val winEndTime = crashTimestamp + win.endRelMs

            val windowEvents = preCrashEvents.filter {
                it.timestamp in winStartTime..winEndTime
            }

            features.add(calculateWindowMetrics(
                roundId = round.roundId,
                windowName = win.name,
                startRelMs = win.startRelMs,
                endRelMs = win.endRelMs,
                windowEvents = windowEvents,
                isControl = false
            ))
        }

        return features
    }

    /**
     * Extracts matched control windows from safe, mid-flight stable periods of the round.
     */
    fun extractControlFeatures(round: GameRound, allEvents: List<LiveEvent>): List<RoundFeature> {
        if (round.durationMs < 7000L) return emptyList() // Needs sufficient length for clean control

        // Mid-flight sample: e.g. 2.0s into the round to 4.9s into the round
        val controlStartTime = round.startTime + 2000L
        val controlEndTime = round.startTime + 4900L // 2900ms window, matching T_3.0s_TO_0.1s

        val controlEvents = allEvents.filter {
            it.timestamp in controlStartTime..controlEndTime && !it.isPostCrash
        }

        return listOf(
            calculateWindowMetrics(
                roundId = round.roundId,
                windowName = "CONTROL_MID_FLIGHT",
                startRelMs = -1L,
                endRelMs = -1L,
                windowEvents = controlEvents,
                isControl = true
            )
        )
    }

    fun calculateWindowMetrics(
        roundId: String,
        windowName: String,
        startRelMs: Long,
        endRelMs: Long,
        windowEvents: List<LiveEvent>,
        isControl: Boolean
    ): RoundFeature {
        val count = windowEvents.size
        val durationMs = if (isControl) 2900L else (endRelMs - startRelMs).coerceAtLeast(100L)
        val msgRate = (count.toDouble() / durationMs.toDouble()) * 1000.0 // events per second

        val intervals = mutableListOf<Long>()
        var quietPeriodMs = 0L

        for (i in 1 until count) {
            val diff = (windowEvents[i].timestamp - windowEvents[i - 1].timestamp).coerceAtLeast(0L)
            intervals.add(diff)
            if (diff > quietPeriodMs) {
                quietPeriodMs = diff
            }
        }

        val meanInterval = if (intervals.isNotEmpty()) intervals.average() else 0.0
        val medianInterval = if (intervals.isNotEmpty()) {
            val sorted = intervals.sorted()
            if (sorted.size % 2 == 1) sorted[sorted.size / 2].toDouble()
            else (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) / 2.0
        } else 0.0

        val minInterval = intervals.minOrNull() ?: 0L
        val maxInterval = intervals.maxOrNull() ?: 0L

        val variance = if (intervals.size > 1) {
            intervals.map { (it - meanInterval).pow(2) }.average()
        } else 0.0
        val stdInterval = sqrt(variance)

        // Burstiness metric B = (std - mean) / (std + mean)
        val burstiness = if (stdInterval + meanInterval > 0.0) {
            (stdInterval - meanInterval) / (stdInterval + meanInterval)
        } else 0.0

        val uniqueTypes = windowEvents.map { it.eventType }.distinct().size

        // Build sequence n-gram (e.g. TYPE1->TYPE2)
        val ngrams = if (windowEvents.size >= 2) {
            windowEvents.takeLast(4).joinToString("->") { it.eventType }
        } else if (windowEvents.isNotEmpty()) {
            windowEvents.first().eventType
        } else "NONE"

        return RoundFeature(
            roundId = roundId,
            windowName = windowName,
            windowStartRelMs = startRelMs,
            windowEndRelMs = endRelMs,
            eventCount = count,
            messageRate = msgRate,
            meanInterEventMs = meanInterval,
            medianInterEventMs = medianInterval,
            minInterEventMs = minInterval,
            maxInterEventMs = maxInterval,
            stdInterEventMs = stdInterval,
            burstiness = burstiness,
            quietPeriodMs = quietPeriodMs,
            uniqueMessageTypes = uniqueTypes,
            ngramsSequence = ngrams,
            isControlWindow = isControl
        )
    }
}
