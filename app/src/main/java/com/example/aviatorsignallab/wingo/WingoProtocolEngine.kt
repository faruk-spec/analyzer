package com.example.aviatorsignallab.wingo

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * Protocol and state engine for WinGo / BigSmall lottery rooms on casino platforms.
 * Supports distinct state tracking across all 4 rooms:
 * - WinGo 30s (Room code 10005, cycle: 30s)
 * - WinGo 1Min (Room code 10001, cycle: 60s)
 * - WinGo 3Min (Room code 10002, cycle: 180s)
 * - WinGo 5Min (Room code 10003, cycle: 300s)
 */
class WingoProtocolEngine(
    private val listener: WingoListener? = null
) {
    private val gson = Gson()

    enum class WingoRoom(val roomCode: String, val displayName: String, val cycleSeconds: Int, val idPattern: String) {
        WINGO_30S("WinGo_30S", "30sec", 30, "10005"),
        WINGO_1M("WinGo_1M", "1 Min", 60, "10001"),
        WINGO_3M("WinGo_3M", "3 Min", 180, "10002"),
        WINGO_5M("WinGo_5M", "5 Min", 300, "10003");

        companion object {
            fun fromString(str: String): WingoRoom {
                val lower = str.lowercase()
                return when {
                    lower.contains("30s") || lower.contains("30sec") || lower.contains("10005") -> WINGO_30S
                    lower.contains("3m") || lower.contains("3min") || lower.contains("10002") -> WINGO_3M
                    lower.contains("5m") || lower.contains("5min") || lower.contains("10003") -> WINGO_5M
                    lower.contains("1m") || lower.contains("1min") || lower.contains("10001") -> WINGO_1M
                    else -> WINGO_30S
                }
            }

            fun fromPeriodId(periodId: String): WingoRoom? {
                if (periodId.length >= 13) {
                    val code = periodId.substring(8, 13)
                    return when (code) {
                        "10005" -> WINGO_30S
                        "10001" -> WINGO_1M
                        "10002" -> WINGO_3M
                        "10003" -> WINGO_5M
                        else -> null
                    }
                }
                return null
            }
        }
    }

    data class WingoDrawResult(
        val periodId: String,
        val number: Int,
        val size: String,       // "BIG" (5-9) or "SMALL" (0-4)
        val color: String,      // "GREEN", "RED", "VIOLET", or "RED_VIOLET" / "GREEN_VIOLET"
        val timestamp: Long = System.currentTimeMillis()
    )

    data class WingoIssueInfo(
        val currentPeriod: String,
        val remainingSeconds: Int,
        val isLocked: Boolean,
        val room: WingoRoom
    )

    data class RoomState(
        val room: WingoRoom,
        var currentPeriod: String = "--",
        var remainingSeconds: Int = 0,
        var isLocked: Boolean = false,
        val history: MutableList<WingoDrawResult> = mutableListOf(),
        var trendSummary: WingoTrendAnalyzer.TrendSummary = WingoTrendAnalyzer.analyzeTrends(emptyList()),
        var transitions: WingoTrendAnalyzer.TransitionProbabilities = WingoTrendAnalyzer.calculateTransitions(emptyList()),
        var prediction: WingoTrendAnalyzer.BetPrediction = WingoTrendAnalyzer.predictNextBet(emptyList())
    )

    interface WingoListener {
        fun onNewDrawResult(result: WingoDrawResult, history: List<WingoDrawResult>) {}
        fun onIssueUpdated(issue: WingoIssueInfo) {}
        fun onHistoryLoaded(results: List<WingoDrawResult>) {}
        fun onRoomUpdated(
            room: WingoRoom,
            issue: WingoIssueInfo,
            history: List<WingoDrawResult>,
            trend: WingoTrendAnalyzer.TrendSummary,
            trans: WingoTrendAnalyzer.TransitionProbabilities,
            pred: WingoTrendAnalyzer.BetPrediction
        ) {}
    }

    val roomStates: Map<WingoRoom, RoomState> = WingoRoom.values().associateWith { RoomState(it) }

    var activeRoom: WingoRoom = WingoRoom.WINGO_30S
        private set

    fun setActiveRoom(room: WingoRoom) {
        activeRoom = room
        val state = roomStates[room] ?: return
        val issue = WingoIssueInfo(state.currentPeriod, state.remainingSeconds, state.isLocked, room)
        listener?.onRoomUpdated(room, issue, state.history.toList(), state.trendSummary, state.transitions, state.prediction)
    }

    fun getActiveRoomState(): RoomState = roomStates[activeRoom] ?: roomStates.values.first()

    /**
     * Determines whether a network packet belongs to WinGo / Lottery game endpoints.
     */
    fun isWingoTraffic(url: String, payload: String?): Boolean {
        val lowerUrl = url.lowercase()
        val lowerPayload = (payload ?: "").lowercase()

        val isWingoUrl = lowerUrl.contains("wingo") ||
                lowerUrl.contains("lottery") ||
                lowerUrl.contains("emerdlist") ||
                lowerUrl.contains("gethistoryissuepage") ||
                lowerUrl.contains("getlongdragon") ||
                lowerUrl.contains("getgameissue") ||
                lowerUrl.contains("getwinthelotteryresult") ||
                lowerUrl.contains("wingo_dom")

        val hasWingoFields = lowerPayload.contains("issuenumber") ||
                lowerPayload.contains("periodid") ||
                (lowerPayload.contains("\"number\":") && lowerPayload.contains("\"color\"")) ||
                lowerPayload.contains("wingo_dom_sync") ||
                lowerPayload.contains("wingo_live_state") ||
                lowerPayload.contains("gethistoryissuepage") ||
                lowerPayload.contains("premium")

        return isWingoUrl || hasWingoFields
    }

    /**
     * Ingests network payloads (XHR, Fetch, WebSocket, or DOM Sync).
     */
    @Synchronized
    fun processPayload(transport: String, url: String, payload: String?) {
        if (payload.isNullOrBlank()) return

        try {
            val trimmed = payload.trim()
            val cleanJson = if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
                trimmed
            } else {
                val idx = trimmed.indexOfAny(charArrayOf('{', '['))
                if (idx != -1) trimmed.substring(idx) else trimmed
            }

            val element = JsonParser.parseString(cleanJson)

            if (element.isJsonObject) {
                parseJsonObject(element.asJsonObject, url)
            } else if (element.isJsonArray) {
                parseResultsArray(element.asJsonArray, WingoRoom.fromString(url))
            }
        } catch (e: Exception) {
            // Irregular format ignored
        }
    }

    private fun parseJsonObject(obj: JsonObject, url: String) {
        // Handle DOM Live State directly from injected MutationObserver
        if (obj.has("type") && (obj.get("type").asString == "WINGO_DOM_SYNC" || obj.get("type").asString == "WINGO_LIVE_STATE")) {
            val roomStr = obj.get("gameType")?.asString ?: "30sec"
            val room = WingoRoom.fromString(roomStr)
            val period = obj.get("periodId")?.asString ?: "--"
            val seconds = obj.get("remainingSeconds")?.asInt ?: 0
            val isLocked = obj.get("isLocked")?.asBoolean ?: (seconds <= 5)

            updateRoomIssue(room, period, seconds, isLocked)

            // Extract balls if present
            if (obj.has("balls") && obj.get("balls").isJsonArray) {
                val balls = obj.getAsJsonArray("balls")
                val results = mutableListOf<WingoDrawResult>()
                for (b in balls) {
                    val num = b.asString.toIntOrNull() ?: continue
                    results.add(WingoDrawResult(
                        periodId = "--",
                        number = num,
                        size = if (num >= 5) "BIG" else "SMALL",
                        color = when {
                            num == 0 -> "RED_VIOLET"
                            num == 5 -> "GREEN_VIOLET"
                            num in listOf(1, 3, 7, 9) -> "GREEN"
                            else -> "RED"
                        }
                    ))
                }
                if (results.isNotEmpty()) {
                    updateRoomHistory(room, results)
                }
            }
            return
        }

        // Detect target room from URL, gameCode, or period ID
        var detectedRoom = WingoRoom.fromString(url)
        if (obj.has("gameCode")) {
            detectedRoom = WingoRoom.fromString(obj.get("gameCode").asString)
        }

        val periodCandidate = obj.get("issueNumber")?.asString
            ?: obj.get("periodId")?.asString
            ?: obj.get("issue")?.asString

        if (periodCandidate != null) {
            val roomFromPeriod = WingoRoom.fromPeriodId(periodCandidate)
            if (roomFromPeriod != null) {
                detectedRoom = roomFromPeriod
            }
        }

        val timeRemaining = obj.get("countdown")?.asInt
            ?: obj.get("remainingTime")?.asInt
            ?: obj.get("seconds")?.asInt

        if (periodCandidate != null && timeRemaining != null) {
            val isLocked = timeRemaining <= 5
            updateRoomIssue(detectedRoom, periodCandidate, timeRemaining, isLocked)
        }

        // Check for nested arrays (e.g. data: { list: [ ... ] })
        for (key in listOf("data", "list", "history", "rows")) {
            if (obj.has(key) && obj.get(key).isJsonArray) {
                parseResultsArray(obj.getAsJsonArray(key), detectedRoom)
            } else if (obj.has(key) && obj.get(key).isJsonObject) {
                parseJsonObject(obj.getAsJsonObject(key), url)
            }
        }

        // Check if object is single draw result
        if (obj.has("number") || obj.has("openNumber") || obj.has("winNumber") || obj.has("premium")) {
            val draw = extractSingleDraw(obj)
            if (draw != null) {
                val targetRoom = WingoRoom.fromPeriodId(draw.periodId) ?: detectedRoom
                registerNewDraw(targetRoom, draw)
            }
        }
    }

    private fun parseResultsArray(arr: JsonArray, fallbackRoom: WingoRoom) {
        val extracted = mutableListOf<WingoDrawResult>()
        var targetRoom = fallbackRoom

        for (item in arr) {
            if (!item.isJsonObject) continue
            val draw = extractSingleDraw(item.asJsonObject)
            if (draw != null) {
                val roomFromId = WingoRoom.fromPeriodId(draw.periodId)
                if (roomFromId != null) targetRoom = roomFromId
                extracted.add(draw)
            }
        }

        if (extracted.isNotEmpty()) {
            updateRoomHistory(targetRoom, extracted)
        }
    }

    private fun extractSingleDraw(obj: JsonObject): WingoDrawResult? {
        val numVal = try {
            when {
                obj.has("number") -> obj.get("number").asString.toIntOrNull()
                obj.has("openNumber") -> obj.get("openNumber").asString.toIntOrNull()
                obj.has("winNumber") -> obj.get("winNumber").asString.toIntOrNull()
                obj.has("premium") -> obj.get("premium").asString.toIntOrNull()
                else -> null
            }
        } catch (e: Exception) { null } ?: return null

        val period = obj.get("issueNumber")?.asString
            ?: obj.get("periodId")?.asString
            ?: obj.get("issue")?.asString
            ?: "p_${System.currentTimeMillis()}"

        val size = when {
            obj.has("size") -> obj.get("size").asString.uppercase()
            numVal >= 5 -> "BIG"
            else -> "SMALL"
        }

        val color = when {
            obj.has("color") -> obj.get("color").asString.uppercase()
            numVal == 0 -> "RED_VIOLET"
            numVal == 5 -> "GREEN_VIOLET"
            numVal in listOf(1, 3, 7, 9) -> "GREEN"
            else -> "RED"
        }

        return WingoDrawResult(
            periodId = period,
            number = numVal,
            size = size,
            color = color
        )
    }

    fun updateRoomIssue(room: WingoRoom, period: String, remainingSeconds: Int, isLocked: Boolean) {
        val state = roomStates[room] ?: return
        val periodChanged = state.currentPeriod != period
        state.currentPeriod = period
        state.remainingSeconds = remainingSeconds
        state.isLocked = isLocked

        if (periodChanged && state.history.isNotEmpty()) {
            state.prediction = WingoTrendAnalyzer.predictNextBet(state.history, period)
        }

        val issue = WingoIssueInfo(period, remainingSeconds, isLocked, room)
        if (room == activeRoom) {
            listener?.onIssueUpdated(issue)
            listener?.onRoomUpdated(room, issue, state.history.toList(), state.trendSummary, state.transitions, state.prediction)
        }
    }

    fun updateRoomHistory(room: WingoRoom, draws: List<WingoDrawResult>) {
        val state = roomStates[room] ?: return
        synchronized(state.history) {
            for (item in draws) {
                if (state.history.none { it.periodId == item.periodId && it.periodId != "--" }) {
                    state.history.add(item)
                }
            }
            state.history.sortByDescending { it.periodId }
            while (state.history.size > 200) {
                state.history.removeAt(state.history.size - 1)
            }
        }

        state.trendSummary = WingoTrendAnalyzer.analyzeTrends(state.history)
        state.transitions = WingoTrendAnalyzer.calculateTransitions(state.history)
        state.prediction = WingoTrendAnalyzer.predictNextBet(state.history, state.currentPeriod)

        val issue = WingoIssueInfo(state.currentPeriod, state.remainingSeconds, state.isLocked, room)
        if (room == activeRoom) {
            listener?.onHistoryLoaded(state.history.toList())
            listener?.onRoomUpdated(room, issue, state.history.toList(), state.trendSummary, state.transitions, state.prediction)
        }
    }

    private fun registerNewDraw(room: WingoRoom, draw: WingoDrawResult) {
        val state = roomStates[room] ?: return
        val snapshot = synchronized(state.history) {
            if (state.history.none { it.periodId == draw.periodId && it.periodId != "--" }) {
                state.history.add(0, draw)
                if (state.history.size > 200) state.history.removeAt(state.history.size - 1)
            }
            state.history.toList()
        }

        state.trendSummary = WingoTrendAnalyzer.analyzeTrends(snapshot)
        state.transitions = WingoTrendAnalyzer.calculateTransitions(snapshot)
        state.prediction = WingoTrendAnalyzer.predictNextBet(snapshot, state.currentPeriod)

        val issue = WingoIssueInfo(state.currentPeriod, state.remainingSeconds, state.isLocked, room)
        if (room == activeRoom) {
            listener?.onNewDrawResult(draw, snapshot)
            listener?.onRoomUpdated(room, issue, snapshot, state.trendSummary, state.transitions, state.prediction)
        }
    }

    fun getRecentResults(room: WingoRoom = activeRoom): List<WingoDrawResult> {
        val state = roomStates[room] ?: return emptyList()
        return synchronized(state.history) { state.history.toList() }
    }
}
