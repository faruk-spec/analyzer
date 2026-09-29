package com.example.aviatorsignallab.analysis

import kotlin.math.sqrt

/**
 * LiveTickCadenceAnalyzer: Real-time tick freeze detection for the CURRENT live round.
 *
 * HOW IT WORKS (pure live analysis, zero dependence on historical round database):
 * 1. During live flight, records timestamps of incoming multiplier ticks (cmd:85).
 * 2. Calculates rolling average inter-tick interval (avgTickInterval, baseline ~120ms).
 * 3. Fast polling loop (every 15ms) checks if time elapsed since last tick exceeds the
 *    freeze threshold: max(175ms, (avgInterval * 1.45).toLong()).
 * 4. When a freeze is detected, fires pre-crash alert DURING the gap — ~50-150ms BEFORE
 *    the Spribe server dispatches the cmd:84, sta:3 (flew away) packet.
 * 5. If flight resumes (packet jitter), automatically clears alert and re-arms for the round.
 */
class LiveTickCadenceAnalyzer {

    // Timestamps of recent ticks in current round (ring buffer of last 30)
    private val tickTimestamps = mutableListOf<Long>()

    // Rolling inter-tick intervals for this round (last 20 intervals)
    private val tickIntervals = mutableListOf<Long>()

    // Calculated rolling average tick interval (seeded with 120ms typical Spribe Aviator cadence)
    private var avgTickInterval: Long = 120L

    // Minimum ticks needed before activating detection (4 ticks to establish steady rhythm)
    private val minTicksForDetection: Int = 4

    // Minimum multiplier before freeze detection is active (avoids early takeoff setup)
    var minMultiplierForDetection: Double = 1.15

    // Adaptive threshold multiplier: 2.3x normal interval
    var freezeThresholdMultiplier: Double = 2.3

    // Absolute minimum silence duration in ms to avoid false alarms from mobile network jitter (300ms)
    var absoluteMinGapMs: Long = 300L

    // Max allowable gap: if >2500ms, round has stalled or disconnected, not pre-crash
    var absoluteMaxGapMs: Long = 2500L

    // Alert state for current round: STRICT SINGLE ALERT PER ROUND
    @Volatile
    private var alertFiredThisRound: Boolean = false
    private var currentRoundId: String = ""

    /**
     * Reset state for a new round
     */
    @Synchronized
    fun onNewRound(roundId: String) {
        tickTimestamps.clear()
        tickIntervals.clear()
        avgTickInterval = 120L // Re-seed with baseline
        alertFiredThisRound = false
        currentRoundId = roundId
    }

    /**
     * Record a multiplier tick from the current round.
     * Call whenever a cmd:85 tick or multiplier increment is received.
     */
    @Synchronized
    fun recordTick(timestamp: Long) {
        if (tickTimestamps.isNotEmpty()) {
            val interval = timestamp - tickTimestamps.last()
            // Valid inter-tick interval range (20ms to 1500ms)
            if (interval in 20..1500) {
                tickIntervals.add(interval)
                if (tickIntervals.size > 20) {
                    tickIntervals.removeAt(0)
                }
                avgTickInterval = tickIntervals.average().toLong().coerceIn(60L, 350L)
            }
        }
        tickTimestamps.add(timestamp)
        if (tickTimestamps.size > 30) {
            tickTimestamps.removeAt(0)
        }
        // STRICT: Do NOT reset alertFiredThisRound here. Once fired, it remains fired for the round!
    }

    /**
     * Has sufficient tick cadence been established?
     */
    val isCalibrated: Boolean
        get() = tickIntervals.size >= minTicksForDetection

    val currentAvgInterval: Long
        get() = avgTickInterval

    val tickCount: Int
        get() = tickTimestamps.size

    val hasAlertFired: Boolean
        get() = alertFiredThisRound

    fun markAlertFired() {
        alertFiredThisRound = true
    }

    fun resetAlertFired() {
        // Only allow reset via onNewRound
    }

    /**
     * CORE EVALUATION: Check if tick stream has frozen RIGHT NOW.
     *
     * @param currentTime Current epoch millisecond
     * @param currentMultiplier Current flight multiplier
     */
    @Synchronized
    fun checkForFreeze(currentTime: Long, currentMultiplier: Double): TickFreezeResult {
        if (!isCalibrated || currentMultiplier < minMultiplierForDetection) {
            val status = if (!isCalibrated) {
                "CALIBRATING (${tickIntervals.size}/$minTicksForDetection ticks)"
            } else {
                "BELOW_MIN_MULT (${"%.2f".format(currentMultiplier)}x < ${minMultiplierForDetection}x)"
            }
            return TickFreezeResult(
                freezeDetected = false,
                currentGapMs = 0,
                avgIntervalMs = avgTickInterval,
                freezeRatio = 0.0,
                status = status
            )
        }

        if (alertFiredThisRound) {
            return TickFreezeResult(
                freezeDetected = false,
                currentGapMs = 0,
                avgIntervalMs = avgTickInterval,
                freezeRatio = 0.0,
                status = "ALERT_ALREADY_FIRED"
            )
        }

        val lastTickTime = tickTimestamps.lastOrNull() ?: return TickFreezeResult(
            freezeDetected = false,
            currentGapMs = 0,
            avgIntervalMs = avgTickInterval,
            freezeRatio = 0.0,
            status = "NO_TICKS"
        )

        val currentGap = currentTime - lastTickTime

        if (currentGap > absoluteMaxGapMs) {
            return TickFreezeResult(
                freezeDetected = false,
                currentGapMs = currentGap,
                avgIntervalMs = avgTickInterval,
                freezeRatio = currentGap.toDouble() / avgTickInterval.toDouble(),
                status = "GAP_TOO_LARGE"
            )
        }

        val adaptiveThreshold = (avgTickInterval * freezeThresholdMultiplier).toLong()
            .coerceAtLeast(absoluteMinGapMs)

        val isFreeze = currentGap >= adaptiveThreshold
        val freezeRatio = if (avgTickInterval > 0) currentGap.toDouble() / avgTickInterval.toDouble() else 1.0

        return TickFreezeResult(
            freezeDetected = isFreeze,
            currentGapMs = currentGap,
            avgIntervalMs = avgTickInterval,
            freezeRatio = freezeRatio,
            status = if (isFreeze) {
                "TICK_FREEZE_DETECTED (gap=${currentGap}ms >= thr=${adaptiveThreshold}ms)"
            } else {
                "CADENCE_OK (gap=${currentGap}ms, avg=${avgTickInterval}ms)"
            }
        )
    }

    fun getDiagnostics(): String {
        if (tickIntervals.isEmpty()) return "Ticks: $tickCount (Calibrating baseline: ${avgTickInterval}ms)"
        val min = tickIntervals.minOrNull() ?: 0L
        val max = tickIntervals.maxOrNull() ?: 0L
        val avg = avgTickInterval
        val jitter = if (tickIntervals.size > 1) {
            val variance = tickIntervals.map { (it - avg).toDouble() * (it - avg).toDouble() }.average()
            sqrt(variance).toLong()
        } else 0L
        val thr = (avg * freezeThresholdMultiplier).toLong().coerceAtLeast(absoluteMinGapMs)
        return "Ticks: ${tickTimestamps.size} | Avg: ${avg}ms | Min: ${min}ms | Max: ${max}ms | Jitter: ±${jitter}ms | TriggerGap: ${thr}ms"
    }
}

data class TickFreezeResult(
    val freezeDetected: Boolean,
    val currentGapMs: Long,
    val avgIntervalMs: Long,
    val freezeRatio: Double,
    val status: String
)
