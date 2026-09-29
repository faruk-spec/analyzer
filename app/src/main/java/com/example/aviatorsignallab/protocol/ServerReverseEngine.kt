package com.example.aviatorsignallab.protocol

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * Deep Server Reverse Engineering Engine for Aviator Protocol.
 *
 * Implements:
 * 1. Live Player Bet Volume & Casino Net Exposure Tracking (cmd:86, cmd:87)
 * 2. Provably Fair SHA-512 Cryptographic Verification & Seed Chain Auditing
 * 3. Raw WebSocket Opcode Disassembly & Latency Jitter Profiling
 */
object ServerReverseEngine {

    private val gson = Gson()

    data class WhaleBet(
        val userId: String,
        val amount: Double,
        val autoCashout: Double,
        val isCashedOut: Boolean = false,
        val cashedMultiplier: Double = 0.0
    )

    data class RoundBetTelemetry(
        val roundId: String,
        val totalBetsCount: Int = 0,
        val totalWagerPool: Double = 0.0,
        val cashedOutAmount: Double = 0.0,
        val activeWagerRemaining: Double = 0.0,
        val casinoNetProfitLoss: Double = 0.0,
        val whaleThreatCount: Int = 0,
        val whaleBets: List<WhaleBet> = emptyList(),
        val cashoutVelocityPct: Double = 0.0
    )

    data class ProvablyFairResult(
        val isValid: Boolean,
        val calculatedMultiplier: Double,
        val recordedMultiplier: Double,
        val discrepancy: Double,
        val hmacSha512Hex: String,
        val first13Hex: String,
        val decimalValue: Long,
        val instantCrash: Boolean
    )

    data class DisassembledPacket(
        val opcode: String,
        val commandName: String,
        val rawSize: Int,
        val direction: String,
        val summary: String,
        val timestamp: Long = System.currentTimeMillis()
    )

    // Current Round Live Bet State
    @Volatile
    var currentTelemetry: RoundBetTelemetry = RoundBetTelemetry("--")
        private set

    private val activeBets = mutableMapOf<String, WhaleBet>()
    private var lastCashoutUpdateTime = 0L
    private var recentCashoutSum = 0.0

    /**
     * Resets telemetry state on new round start.
     */
    @Synchronized
    fun onRoundStart(roundId: String) {
        activeBets.clear()
        recentCashoutSum = 0.0
        lastCashoutUpdateTime = System.currentTimeMillis()
        currentTelemetry = RoundBetTelemetry(roundId = roundId)
    }

    /**
     * Parses incoming WebSocket messages for bet activity (cmd:86) and cashouts (cmd:87).
     */
    @Synchronized
    fun processPacket(opcode: String?, rawPayload: String?, currentMultiplier: Double): RoundBetTelemetry {
        if (rawPayload.isNullOrBlank()) return currentTelemetry

        try {
            val trimmed = rawPayload.trim()
            val cleanJson = if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
                trimmed
            } else {
                val idx = trimmed.indexOfAny(charArrayOf('{', '['))
                if (idx != -1) trimmed.substring(idx) else trimmed
            }

            val element = JsonParser.parseString(cleanJson)

            // Handle Batch Bet Broadcasts (cmd: 86)
            if (opcode == "86" || rawPayload.contains("\"cmd\":86") || rawPayload.contains("\"bets\"")) {
                parseBetsArray(element)
            }

            // Handle Individual or Batch Cashouts (cmd: 87)
            if (opcode == "87" || rawPayload.contains("\"cmd\":87") || rawPayload.contains("\"cashout\"") || rawPayload.contains("\"cashed\"")) {
                parseCashoutEvent(element, currentMultiplier)
            }

            // Recalculate casino net exposure at current multiplier
            recalculateExposure(currentMultiplier)

        } catch (e: Exception) {
            // Non-JSON or irregular frame - graceful continue
        }

        return currentTelemetry
    }

    private fun parseBetsArray(element: com.google.gson.JsonElement) {
        val betsArr = when {
            element.isJsonArray -> element.asJsonArray
            element.isJsonObject && element.asJsonObject.has("bets") -> element.asJsonObject.getAsJsonArray("bets")
            element.isJsonObject && element.asJsonObject.has("data") -> element.asJsonObject.getAsJsonArray("data")
            else -> null
        } ?: return

        for (item in betsArr) {
            if (!item.isJsonObject) continue
            val obj = item.asJsonObject
            val uid = obj.get("uid")?.asString ?: obj.get("userId")?.asString ?: obj.get("id")?.asString ?: "u_${activeBets.size + 1}"
            val bet = obj.get("bet")?.asDouble ?: obj.get("amount")?.asDouble ?: obj.get("val")?.asDouble ?: 0.0
            val auto = obj.get("auto")?.asDouble ?: obj.get("autoCashout")?.asDouble ?: 0.0

            if (bet > 0.0 && !activeBets.containsKey(uid)) {
                activeBets[uid] = WhaleBet(
                    userId = uid,
                    amount = bet,
                    autoCashout = auto,
                    isCashedOut = false
                )
            }
        }
    }

    private fun parseCashoutEvent(element: com.google.gson.JsonElement, currentMultiplier: Double) {
        if (element.isJsonObject) {
            val obj = element.asJsonObject
            val uid = obj.get("uid")?.asString ?: obj.get("userId")?.asString
            val mul = obj.get("mul")?.asDouble ?: obj.get("multiplier")?.asDouble ?: currentMultiplier

            if (uid != null && activeBets.containsKey(uid)) {
                val existing = activeBets[uid]!!
                activeBets[uid] = existing.copy(
                    isCashedOut = true,
                    cashedMultiplier = mul
                )
                recentCashoutSum += existing.amount * mul
            }
        } else if (element.isJsonArray) {
            for (item in element.asJsonArray) {
                if (item.isJsonObject) {
                    val obj = item.asJsonObject
                    val uid = obj.get("uid")?.asString ?: obj.get("userId")?.asString
                    val mul = obj.get("mul")?.asDouble ?: currentMultiplier
                    if (uid != null && activeBets.containsKey(uid)) {
                        val existing = activeBets[uid]!!
                        activeBets[uid] = existing.copy(isCashedOut = true, cashedMultiplier = mul)
                        recentCashoutSum += existing.amount * mul
                    }
                }
            }
        }
    }

    @Synchronized
    fun recalculateExposure(currentMultiplier: Double) {
        var totalWager = 0.0
        var totalPaidOut = 0.0
        var activeUncashedWager = 0.0
        val whales = mutableListOf<WhaleBet>()

        val thresholdWhale = 500.0 // Whale threshold: > 500 currency units

        for (b in activeBets.values) {
            totalWager += b.amount
            if (b.isCashedOut) {
                totalPaidOut += b.amount * b.cashedMultiplier
            } else {
                activeUncashedWager += b.amount
                if (b.amount >= thresholdWhale) {
                    whales.add(b)
                }
            }
        }

        // Live Casino Net P/L = Total Inflow - Paid Out - (Active Money * Current Multiplier)
        val currentLiability = activeUncashedWager * currentMultiplier
        val netCasinoPL = totalWager - totalPaidOut - currentLiability

        // Cashout Velocity: % of total pool cashed out per second
        val now = System.currentTimeMillis()
        val dt = ((now - lastCashoutUpdateTime) / 1000.0).coerceAtLeast(0.1)
        val velocity = if (totalWager > 0.0) ((recentCashoutSum / totalWager) / dt) * 100.0 else 0.0

        currentTelemetry = RoundBetTelemetry(
            roundId = currentTelemetry.roundId,
            totalBetsCount = activeBets.size,
            totalWagerPool = totalWager.roundToTwoDecimals(),
            cashedOutAmount = totalPaidOut.roundToTwoDecimals(),
            activeWagerRemaining = activeUncashedWager.roundToTwoDecimals(),
            casinoNetProfitLoss = netCasinoPL.roundToTwoDecimals(),
            whaleThreatCount = whales.size,
            whaleBets = whales,
            cashoutVelocityPct = velocity.coerceIn(0.0, 100.0).roundToOneDecimal()
        )
    }

    /**
     * Provably Fair SHA-512 Verification:
     * Calculates the exact mathematical crash multiplier from Server Seed and Client Seeds.
     */
    fun verifyProvablyFair(
        serverSeed: String,
        clientSeed: String,
        recordedMultiplier: Double
    ): ProvablyFairResult {
        return try {
            val keySpec = SecretKeySpec(serverSeed.toByteArray(Charsets.UTF_8), "HmacSHA512")
            val mac = Mac.getInstance("HmacSHA512")
            mac.init(keySpec)
            val hmacBytes = mac.doFinal(clientSeed.toByteArray(Charsets.UTF_8))
            val hex = hmacBytes.joinToString("") { "%02x".format(it) }

            // Take the first 13 characters (52 bits) of the HMAC hex hash
            val first13 = hex.substring(0, 13)
            val h = first13.toLong(16)
            val e = 4503599627370496L // 2^52

            // If divisible by 33, round crashes immediately at 1.00x (3% house edge rule)
            val isInstantCrash = (h % 33L == 0L)
            val calculated = if (isInstantCrash) {
                1.00
            } else {
                floor((100.0 * e - h) / (e - h)) / 100.0
            }

            val diff = kotlin.math.abs(calculated - recordedMultiplier)
            val isValid = diff < 0.02

            ProvablyFairResult(
                isValid = isValid,
                calculatedMultiplier = calculated,
                recordedMultiplier = recordedMultiplier,
                discrepancy = diff.roundToTwoDecimals(),
                hmacSha512Hex = hex,
                first13Hex = first13,
                decimalValue = h,
                instantCrash = isInstantCrash
            )
        } catch (e: Exception) {
            ProvablyFairResult(
                isValid = false,
                calculatedMultiplier = 0.0,
                recordedMultiplier = recordedMultiplier,
                discrepancy = 999.0,
                hmacSha512Hex = "ERROR",
                first13Hex = "ERROR",
                decimalValue = 0L,
                instantCrash = false
            )
        }
    }

    /**
     * Disassembles a raw WebSocket packet into opcode, command name, and human-readable summary.
     */
    fun disassemblePacket(
        transport: String,
        direction: String,
        payload: String?
    ): DisassembledPacket {
        val safe = payload ?: ""
        val (opcode, name, summary) = when {
            safe.contains("\"cmd\":84") && safe.contains("\"sta\":1") ->
                Triple("84:1", "CMD_LIFECYCLE_BETTING", "5-second bet placement phase open")
            safe.contains("\"cmd\":84") && safe.contains("\"sta\":2") ->
                Triple("84:2", "CMD_LIFECYCLE_TAKEOFF", "Plane takeoff initialized at 1.00x")
            safe.contains("\"cmd\":84") && safe.contains("\"sta\":3") ->
                Triple("84:3", "CMD_LIFECYCLE_CRASH", "Flew away / round termination packet")
            safe.contains("\"cmd\":85") ->
                Triple("85", "CMD_MULTIPLIER_TICK", "Live in-flight coordinate stream update")
            safe.contains("\"cmd\":86") ->
                Triple("86", "CMD_PLAYER_BETS", "Batch player wagers & auto-cashout targets")
            safe.contains("\"cmd\":87") ->
                Triple("87", "CMD_PLAYER_CASHOUT", "Live cashout broadcast event")
            safe.contains("\"cmd\":88") ->
                Triple("88", "CMD_HISTORY_BAR", "Top ribbon multiplier history update")
            safe.contains("\"cmd\":90") || safe.contains("ping") || safe.contains("pong") ->
                Triple("90", "CMD_HEARTBEAT", "Server keepalive ping/pong handshake")
            else ->
                Triple("RAW", "UNKNOWN_OPCODE", if (safe.length > 60) safe.substring(0, 60) + "..." else safe)
        }

        return DisassembledPacket(
            opcode = opcode,
            commandName = name,
            rawSize = safe.length,
            direction = direction,
            summary = summary
        )
    }

    private fun Double.roundToOneDecimal(): Double = (this * 10.0).roundToInt() / 10.0
    private fun Double.roundToTwoDecimals(): Double = (this * 100.0).roundToInt() / 100.0
}
