package com.example.aviatorsignallab.protocol

import com.example.aviatorsignallab.model.GameRound
import com.example.aviatorsignallab.model.LiveEvent
import com.google.gson.Gson
import java.util.concurrent.atomic.AtomicInteger

class ProtocolDiscoveryEngine(
    private val listener: StateChangeListener? = null
) {
    private val gson = Gson()
    private val syntheticRoundCounter = AtomicInteger(1)

    var currentState: GameState = GameState.UNKNOWN
        private set

    var currentRoundId: String = "rnd_init_0"
        private set

    var currentMultiplier: Double = 1.00
        private set

    var roundStartTime: Long = System.currentTimeMillis()
        private set

    var lastCrashTimestamp: Long = 0L
        private set

    var activeRound: GameRound? = null
        private set

    private val observedTransports = mutableSetOf<String>()
    private val pendingRoundEvents = mutableListOf<LiveEvent>()

    private val multRegex1 = Regex("""([0-9]{1,4}\.[0-9]{1,2})\s*[xX]""")
    private val multRegex2 = Regex("""["']?(?:multiplier|coef|coefficient|odds|rate|val|x)["']?\s*[:=]\s*["']?([0-9]{1,4}\.[0-9]{1,2})["']?""", RegexOption.IGNORE_CASE)

    @Synchronized
    fun processRawEvent(
        transport: String,
        direction: String,
        rawPayload: String?,
        timestamp: Long = System.currentTimeMillis()
    ): LiveEvent {
        observedTransports.add(transport)

        // 1. Sanitize payload
        val sanitized = SensitiveDataRedactor.sanitizePayload(cleanSocketIoPrefix(rawPayload))
        val fieldMap = SensitiveDataRedactor.extractFieldPaths(sanitized)

        // 2. Discover message metadata & values
        val eventType = discoverEventType(sanitized, fieldMap, transport)
        val command = discoverCommand(fieldMap)
        val extractedMultiplier = discoverMultiplier(sanitized, fieldMap)
        val extractedRoundId = discoverRoundId(fieldMap)
        val isCrashSignal = detectCrashSignal(sanitized, fieldMap)

        // 3. Robust State Transitions
        if (extractedMultiplier != null && extractedMultiplier >= 1.0) {
            handleMultiplierUpdate(extractedMultiplier, extractedRoundId, timestamp)
        } else if (isCrashSignal && (currentState == GameState.LIVE || currentState == GameState.ROUND_START)) {
            transitionToCrash(timestamp, currentMultiplier)
        } else if (extractedRoundId != null && extractedRoundId != currentRoundId && currentState != GameState.LIVE) {
            transitionToStart(extractedRoundId, timestamp)
        }

        // 4. Construct LiveEvent
        val elapsed = if (currentState == GameState.UNKNOWN) 0L else (timestamp - roundStartTime).coerceAtLeast(0L)
        val isPostCrash = (currentState == GameState.CRASH || currentState == GameState.ROUND_COMPLETE || (lastCrashTimestamp > 0 && timestamp >= lastCrashTimestamp))

        val relativeToCrash = if (lastCrashTimestamp > 0) {
            timestamp - lastCrashTimestamp
        } else null

        val event = LiveEvent(
            roundId = currentRoundId,
            timestamp = timestamp,
            elapsedMs = elapsed,
            relativeToCrashMs = relativeToCrash,
            direction = direction,
            transport = transport,
            eventType = eventType,
            command = command,
            messageSize = rawPayload?.length ?: 0,
            rawPreview = if (sanitized.length > 500) sanitized.substring(0, 500) + "..." else sanitized,
            parsedFieldsJson = gson.toJson(fieldMap),
            multiplier = extractedMultiplier ?: currentMultiplier,
            isPostCrash = isPostCrash
        )

        activeRound?.let {
            it.eventCount++
            if (transport == "WEBSOCKET" || transport == "FETCH" || transport == "XHR") {
                it.messageCount++
            }
            it.protocolTypesSeen = observedTransports.joinToString(",")
            it.finalMultiplier = currentMultiplier
        }

        pendingRoundEvents.add(event)
        return event
    }

    private fun handleMultiplierUpdate(newMultiplier: Double, roundIdCandidate: String?, timestamp: Long) {
        when (currentState) {
            GameState.UNKNOWN -> {
                val rid = roundIdCandidate ?: "rnd_${syntheticRoundCounter.getAndIncrement()}"
                transitionToStart(rid, timestamp)
                currentMultiplier = newMultiplier
                transitionToLive()
            }
            GameState.ROUND_START -> {
                currentMultiplier = newMultiplier
                if (newMultiplier > 1.0) {
                    transitionToLive()
                }
            }
            GameState.LIVE -> {
                // Check if multiplier reset or dropped back to 1.00x after climbing!
                if (currentMultiplier > 1.10 && newMultiplier <= 1.05) {
                    // Multiplier reset indicates previous round crashed!
                    val crashMultiplier = currentMultiplier
                    transitionToCrash(timestamp, crashMultiplier)

                    // Start new round
                    val nextRoundId = roundIdCandidate ?: "rnd_${syntheticRoundCounter.getAndIncrement()}"
                    transitionToStart(nextRoundId, timestamp)
                    currentMultiplier = newMultiplier
                    transitionToLive()
                } else {
                    currentMultiplier = newMultiplier
                }
            }
            GameState.CRASH, GameState.ROUND_COMPLETE, GameState.NEXT_ROUND -> {
                // If multiplier is active again, transition to next round
                val nextRoundId = roundIdCandidate ?: "rnd_${syntheticRoundCounter.getAndIncrement()}"
                transitionToStart(nextRoundId, timestamp)
                currentMultiplier = newMultiplier
                transitionToLive()
            }
        }
    }

    private fun transitionToStart(newRoundId: String, timestamp: Long) {
        val prev = currentState
        currentState = GameState.ROUND_START
        currentRoundId = newRoundId
        currentMultiplier = 1.00
        roundStartTime = timestamp
        lastCrashTimestamp = 0L

        activeRound = GameRound(
            roundId = newRoundId,
            startTime = timestamp,
            status = "LIVE",
            protocolTypesSeen = observedTransports.joinToString(",")
        )
        pendingRoundEvents.clear()
        listener?.onRoundStarted(newRoundId, timestamp)
        listener?.onStateChanged(prev, currentState, currentRoundId, currentMultiplier)
    }

    private fun transitionToLive() {
        val prev = currentState
        currentState = GameState.LIVE
        activeRound?.status = "LIVE"
        listener?.onStateChanged(prev, currentState, currentRoundId, currentMultiplier)
    }

    private fun transitionToCrash(timestamp: Long, finalMultiplier: Double) {
        val prev = currentState
        currentState = GameState.CRASH
        lastCrashTimestamp = timestamp
        currentMultiplier = finalMultiplier

        activeRound?.let {
            it.endTime = timestamp
            it.durationMs = (timestamp - it.startTime).coerceAtLeast(100L)
            it.finalMultiplier = finalMultiplier
            it.crashDetected = true
            it.status = "RECORDED"
        }

        // Back-propagate relativeToCrashMs to all events captured during this round
        for (evt in pendingRoundEvents) {
            val rel = evt.timestamp - timestamp
            evt.relativeToCrashMs = rel
            if (rel >= 0) {
                evt.isPostCrash = true
            }
        }

        listener?.onRoundCrashDetected(currentRoundId, finalMultiplier, timestamp)
        listener?.onStateChanged(prev, currentState, currentRoundId, currentMultiplier)
    }

    fun completeRound(): GameRound? {
        val r = activeRound
        if (currentState == GameState.CRASH) {
            currentState = GameState.ROUND_COMPLETE
        }
        return r
    }

    private fun cleanSocketIoPrefix(raw: String?): String? {
        if (raw == null) return null
        val trimmed = raw.trim()
        // Strip Socket.IO prefixes like 42[...] or 43[...] or 0{...}
        val idx = trimmed.indexOfAny(charArrayOf('{', '['))
        return if (idx in 1..4) trimmed.substring(idx) else trimmed
    }

    private fun discoverEventType(sanitized: String, fields: Map<String, String>, transport: String): String {
        for (key in listOf("type", "event", "action", "msg_type", "message_type", "cmd", "op")) {
            fields[key]?.let { if (it.isNotBlank()) return it }
        }
        if (sanitized.contains("crash", ignoreCase = true) || sanitized.contains("flew_away", ignoreCase = true) || sanitized.contains("flew away", ignoreCase = true)) {
            return "GAME_CRASH"
        }
        if (sanitized.contains("multiplier", ignoreCase = true) || sanitized.contains("coefficient", ignoreCase = true) || sanitized.contains("DOM_MULTIPLIER", ignoreCase = true)) {
            return "MULTIPLIER_UPDATE"
        }
        if (sanitized.contains("bet", ignoreCase = true)) {
            return "BET_ACTIVITY"
        }
        return "${transport}_MESSAGE"
    }

    private fun discoverCommand(fields: Map<String, String>): String? {
        for (key in listOf("command", "cmd", "opcode", "op", "code", "id")) {
            fields[key]?.let { if (it.isNotBlank()) return it }
        }
        return null
    }

    private fun discoverMultiplier(sanitized: String, fields: Map<String, String>): Double? {
        // Direct field checking
        for (key in listOf("multiplier", "rate", "coefficient", "odds", "x", "val", "v", "text", "rawText")) {
            fields[key]?.let { v ->
                val cleaned = v.replace("x", "", ignoreCase = true).replace("@", "").trim()
                cleaned.toDoubleOrNull()?.let { num ->
                    if (num in 1.0..100000.0) return num
                }
            }
        }

        // Regex pattern 1: "1.45x" or "10.50 X"
        val m1 = multRegex1.find(sanitized)
        if (m1 != null) {
            m1.groupValues[1].toDoubleOrNull()?.let { return it }
        }

        // Regex pattern 2: "multiplier": 1.45
        val m2 = multRegex2.find(sanitized)
        if (m2 != null) {
            m2.groupValues[1].toDoubleOrNull()?.let { return it }
        }

        return null
    }

    private fun discoverRoundId(fields: Map<String, String>): String? {
        for (key in listOf("round_id", "roundId", "rid", "game_id", "gameId", "round", "issueNumber")) {
            fields[key]?.let { if (it.isNotBlank() && it != "[REDACTED]") return it }
        }
        return null
    }

    private fun detectCrashSignal(sanitized: String, fields: Map<String, String>): Boolean {
        for ((k, v) in fields) {
            if (k.contains("status", ignoreCase = true) || k.contains("state", ignoreCase = true) || k.contains("event", ignoreCase = true) || k.contains("type", ignoreCase = true)) {
                if (v.equals("crash", ignoreCase = true) ||
                    v.equals("crashed", ignoreCase = true) ||
                    v.equals("flew_away", ignoreCase = true) ||
                    v.equals("flew away", ignoreCase = true) ||
                    v.equals("DOM_CRASH_SIGNAL", ignoreCase = true) ||
                    v.equals("finish", ignoreCase = true) ||
                    v.equals("ended", ignoreCase = true)) {
                    return true
                }
            }
        }
        val lower = sanitized.lowercase()
        return lower.contains("flew away") || lower.contains("flew-away") || lower.contains("\"crash\"") || lower.contains("dom_crash_signal")
    }
}
