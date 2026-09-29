package com.example.aviatorsignallab.protocol

import com.example.aviatorsignallab.model.GameRound
import com.example.aviatorsignallab.model.LiveEvent
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
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

    @Synchronized
    fun processRawEvent(
        transport: String,
        direction: String,
        rawPayload: String?,
        timestamp: Long = System.currentTimeMillis()
    ): LiveEvent {
        observedTransports.add(transport)

        // 1. Sanitize payload
        val sanitized = SensitiveDataRedactor.sanitizePayload(rawPayload)
        val fieldMap = SensitiveDataRedactor.extractFieldPaths(sanitized)

        // 2. Discover message type / opcode
        val eventType = discoverEventType(sanitized, fieldMap, transport)
        val command = discoverCommand(fieldMap)
        val extractedMultiplier = discoverMultiplier(sanitized, fieldMap)
        val extractedRoundId = discoverRoundId(fieldMap)

        if (extractedMultiplier != null && extractedMultiplier > 1.0) {
            currentMultiplier = extractedMultiplier
        }

        val isCrashSignal = detectCrashSignal(sanitized, fieldMap)
        val isStartSignal = detectStartSignal(sanitized, fieldMap, extractedMultiplier)

        // 3. State Machine transitions
        if (isCrashSignal && (currentState == GameState.LIVE || currentState == GameState.ROUND_START)) {
            transitionToCrash(timestamp)
        } else if (isStartSignal && (currentState == GameState.CRASH || currentState == GameState.ROUND_COMPLETE || currentState == GameState.UNKNOWN)) {
            val newRoundId = extractedRoundId ?: "rnd_${syntheticRoundCounter.getAndIncrement()}"
            transitionToStart(newRoundId, timestamp)
        } else if (extractedRoundId != null && extractedRoundId != currentRoundId && currentState != GameState.LIVE) {
            transitionToStart(extractedRoundId, timestamp)
        } else if (currentState == GameState.ROUND_START && (currentMultiplier > 1.0 || eventType.contains("FLY", ignoreCase = true))) {
            transitionToLive()
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

    private fun transitionToCrash(timestamp: Long) {
        val prev = currentState
        currentState = GameState.CRASH
        lastCrashTimestamp = timestamp

        activeRound?.let {
            it.endTime = timestamp
            it.durationMs = timestamp - it.startTime
            it.finalMultiplier = currentMultiplier
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

        listener?.onRoundCrashDetected(currentRoundId, currentMultiplier, timestamp)
        listener?.onStateChanged(prev, currentState, currentRoundId, currentMultiplier)
    }

    fun completeRound(): GameRound? {
        val r = activeRound
        if (currentState == GameState.CRASH) {
            currentState = GameState.ROUND_COMPLETE
        }
        return r
    }

    private fun discoverEventType(sanitized: String, fields: Map<String, String>, transport: String): String {
        for (key in listOf("type", "event", "action", "msg_type", "message_type", "cmd", "op")) {
            fields[key]?.let { if (it.isNotBlank()) return it }
        }
        if (sanitized.contains("crash", ignoreCase = true) || sanitized.contains("flew_away", ignoreCase = true)) {
            return "GAME_CRASH"
        }
        if (sanitized.contains("multiplier", ignoreCase = true) || sanitized.contains("coefficient", ignoreCase = true)) {
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
        for (key in listOf("multiplier", "rate", "coefficient", "odds", "x", "val", "v")) {
            fields[key]?.let {
                it.toDoubleOrNull()?.let { num ->
                    if (num in 1.0..100000.0) return num
                }
            }
        }
        // Fallback regex matching e.g. "1.45x" or "\"x\": 1.45"
        val regex = Regex("""(?:multiplier|x|coef)["']?\s*[:=]\s*["']?([0-9]+\.[0-9]+)""", RegexOption.IGNORE_CASE)
        val match = regex.find(sanitized)
        if (match != null) {
            return match.groupValues[1].toDoubleOrNull()
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
            if (k.contains("status", ignoreCase = true) || k.contains("state", ignoreCase = true) || k.contains("event", ignoreCase = true)) {
                if (v.equals("crash", ignoreCase = true) ||
                    v.equals("crashed", ignoreCase = true) ||
                    v.equals("flew_away", ignoreCase = true) ||
                    v.equals("finish", ignoreCase = true) ||
                    v.equals("ended", ignoreCase = true)) {
                    return true
                }
            }
        }
        return sanitized.contains("flew away", ignoreCase = true) || sanitized.contains("\"crash\"", ignoreCase = true)
    }

    private fun detectStartSignal(sanitized: String, fields: Map<String, String>, multiplier: Double?): Boolean {
        for ((k, v) in fields) {
            if (k.contains("status", ignoreCase = true) || k.contains("state", ignoreCase = true) || k.contains("event", ignoreCase = true)) {
                if (v.equals("start", ignoreCase = true) ||
                    v.equals("flying", ignoreCase = true) ||
                    v.equals("run", ignoreCase = true) ||
                    v.equals("running", ignoreCase = true) ||
                    v.equals("new_round", ignoreCase = true)) {
                    return true
                }
            }
        }
        return (multiplier != null && multiplier in 1.0..1.05 && currentState == GameState.CRASH)
    }
}
