package com.example.aviatorsignallab.wingo

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * Protocol engine for WinGo / BigSmall lottery games on casino platforms.
 * Intercepts HTTP/Fetch/XHR responses and WebSocket frames containing
 * issue IDs, countdown timers, winning numbers, colors, and Big/Small classifications.
 */
class WingoProtocolEngine(
    private val listener: WingoListener? = null
) {
    private val gson = Gson()

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
        val gameType: String    // "30s", "1m", "3m", "5m"
    )

    interface WingoListener {
        fun onNewDrawResult(result: WingoDrawResult)
        fun onIssueUpdated(issue: WingoIssueInfo)
        fun onHistoryLoaded(results: List<WingoDrawResult>)
    }

    var currentIssue: WingoIssueInfo = WingoIssueInfo("--", 0, false, "1m")
        private set

    private val pastResults = mutableListOf<WingoDrawResult>()

    /**
     * Determines whether a network packet belongs to WinGo / Lottery game endpoints.
     */
    fun isWingoTraffic(url: String, payload: String?): Boolean {
        val lowerUrl = url.toLowerCase()
        val lowerPayload = (payload ?: "").toLowerCase()

        val isWingoUrl = lowerUrl.contains("wingo") ||
                lowerUrl.contains("lottery") ||
                lowerUrl.contains("win_history") ||
                lowerUrl.contains("getissue") ||
                lowerUrl.contains("win/getwin")

        val hasWingoFields = lowerPayload.contains("issuenumber") ||
                lowerPayload.contains("periodid") ||
                (lowerPayload.contains("\"number\":") && lowerPayload.contains("\"color\"")) ||
                (lowerPayload.contains("\"big\"") && lowerPayload.contains("\"small\""))

        return isWingoUrl || hasWingoFields
    }

    /**
     * Processes intercepted network payload (XHR, Fetch, or WebSocket) to discover WinGo events.
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
                parseJsonObject(element.asJsonObject)
            } else if (element.isJsonArray) {
                parseResultsArray(element.asJsonArray)
            }
        } catch (e: Exception) {
            // Non-JSON or irregular frame - ignored
        }
    }

    private fun parseJsonObject(obj: JsonObject) {
        // Check for Issue Info / Timer
        val periodCandidate = obj.get("issueNumber")?.asString
            ?: obj.get("periodId")?.asString
            ?: obj.get("issue")?.asString

        val timeRemaining = obj.get("countdown")?.asInt
            ?: obj.get("remainingTime")?.asInt
            ?: obj.get("seconds")?.asInt

        if (periodCandidate != null && timeRemaining != null) {
            val isLocked = timeRemaining <= 5
            val gameType = obj.get("gameType")?.asString ?: obj.get("type")?.asString ?: "1m"
            currentIssue = WingoIssueInfo(periodCandidate, timeRemaining, isLocked, gameType)
            listener?.onIssueUpdated(currentIssue)
        }

        // Check for nested arrays (e.g. data: [ ... ])
        for (key in listOf("data", "list", "history", "rows")) {
            if (obj.has(key) && obj.get(key).isJsonArray) {
                parseResultsArray(obj.getAsJsonArray(key))
            } else if (obj.has(key) && obj.get(key).isJsonObject) {
                parseJsonObject(obj.getAsJsonObject(key))
            }
        }

        // Check if current object represents a single draw result
        if (obj.has("number") || obj.has("openNumber") || obj.has("winNumber")) {
            val draw = extractSingleDraw(obj)
            if (draw != null) {
                registerNewDraw(draw)
            }
        }
    }

    private fun parseResultsArray(arr: JsonArray) {
        val extracted = mutableListOf<WingoDrawResult>()
        for (item in arr) {
            if (!item.isJsonObject) continue
            val draw = extractSingleDraw(item.asJsonObject)
            if (draw != null) {
                extracted.add(draw)
            }
        }

        if (extracted.isNotEmpty()) {
            synchronized(pastResults) {
                for (item in extracted) {
                    if (pastResults.none { it.periodId == item.periodId }) {
                        pastResults.add(item)
                    }
                }
                // Sort by period descending
                pastResults.sortByDescending { it.periodId }
            }
            listener?.onHistoryLoaded(pastResults.toList())
        }
    }

    private fun extractSingleDraw(obj: JsonObject): WingoDrawResult? {
        val numVal = obj.get("number")?.asInt
            ?: obj.get("openNumber")?.asInt
            ?: obj.get("winNumber")?.asInt
            ?: return null

        val period = obj.get("issueNumber")?.asString
            ?: obj.get("periodId")?.asString
            ?: obj.get("issue")?.asString
            ?: "p_${System.currentTimeMillis()}"

        val size = when {
            obj.has("size") -> obj.get("size").asString.toUpperCase()
            numVal >= 5 -> "BIG"
            else -> "SMALL"
        }

        val color = when {
            obj.has("color") -> obj.get("color").asString.toUpperCase()
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

    private fun registerNewDraw(draw: WingoDrawResult) {
        synchronized(pastResults) {
            if (pastResults.none { it.periodId == draw.periodId }) {
                pastResults.add(0, draw)
                if (pastResults.size > 200) pastResults.removeAt(pastResults.size - 1)
            }
        }
        listener?.onNewDrawResult(draw)
    }

    fun getRecentResults(): List<WingoDrawResult> {
        return synchronized(pastResults) { pastResults.toList() }
    }
}
