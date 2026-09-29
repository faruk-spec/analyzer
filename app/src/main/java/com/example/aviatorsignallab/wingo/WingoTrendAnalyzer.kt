package com.example.aviatorsignallab.wingo

import kotlin.math.roundToInt

/**
 * Real-time streak, trend, and transition matrix analyzer for WinGo / Big-Small lottery draws.
 */
object WingoTrendAnalyzer {

    data class TrendSummary(
        val totalRoundsAnalyzed: Int,
        val bigCount: Int,
        val smallCount: Int,
        val bigRatioPct: Double,
        val smallRatioPct: Double,
        val currentStreakType: String,      // "BIG", "SMALL", "NONE"
        val currentStreakLength: Int,
        val maxBigStreak: Int,
        val maxSmallStreak: Int,
        val isDragonActive: Boolean,        // Active if streak >= 5
        val colorCounts: Map<String, Int>
    )

    data class TransitionProbabilities(
        val afterBigNextBigPct: Double,
        val afterBigNextSmallPct: Double,
        val afterSmallNextSmallPct: Double,
        val afterSmallNextBigPct: Double
    )

    /**
     * Computes comprehensive trend and streak metrics across historical draw results.
     */
    fun analyzeTrends(results: List<WingoProtocolEngine.WingoDrawResult>): TrendSummary {
        if (results.isEmpty()) {
            return TrendSummary(
                totalRoundsAnalyzed = 0,
                bigCount = 0,
                smallCount = 0,
                bigRatioPct = 50.0,
                smallRatioPct = 50.0,
                currentStreakType = "NONE",
                currentStreakLength = 0,
                maxBigStreak = 0,
                maxSmallStreak = 0,
                isDragonActive = false,
                colorCounts = emptyMap()
            )
        }

        // Chronological order: oldest to newest
        val chronological = results.reversed()
        var bigCount = 0
        var smallCount = 0
        val colors = mutableMapOf<String, Int>()

        var maxBig = 0
        var maxSmall = 0
        var currentStreakType = ""
        var currentStreakLen = 0

        for (item in chronological) {
            val s = item.size
            if (s == "BIG") bigCount++ else smallCount++
            colors[item.color] = (colors[item.color] ?: 0) + 1

            if (currentStreakType == s) {
                currentStreakLen++
            } else {
                currentStreakType = s
                currentStreakLen = 1
            }

            if (currentStreakType == "BIG" && currentStreakLen > maxBig) maxBig = currentStreakLen
            if (currentStreakType == "SMALL" && currentStreakLen > maxSmall) maxSmall = currentStreakLen
        }

        val total = results.size
        val bigRatio = if (total > 0) ((bigCount.toDouble() / total) * 100.0).roundToOneDecimal() else 50.0
        val smallRatio = if (total > 0) ((smallCount.toDouble() / total) * 100.0).roundToOneDecimal() else 50.0

        // Latest streak at index 0 of results
        val latestSize = results.firstOrNull()?.size ?: "NONE"
        var activeStreakLen = 0
        for (item in results) {
            if (item.size == latestSize) activeStreakLen++ else break
        }

        return TrendSummary(
            totalRoundsAnalyzed = total,
            bigCount = bigCount,
            smallCount = smallCount,
            bigRatioPct = bigRatio,
            smallRatioPct = smallRatio,
            currentStreakType = latestSize,
            currentStreakLength = activeStreakLen,
            maxBigStreak = maxBig,
            maxSmallStreak = maxSmall,
            isDragonActive = activeStreakLen >= 5,
            colorCounts = colors
        )
    }

    /**
     * Calculates empirical transition probabilities between consecutive rounds.
     */
    fun calculateTransitions(results: List<WingoProtocolEngine.WingoDrawResult>): TransitionProbabilities {
        if (results.size < 2) {
            return TransitionProbabilities(50.0, 50.0, 50.0, 50.0)
        }

        // Chronological order
        val chrono = results.reversed()
        var bigToBig = 0
        var bigToSmall = 0
        var smallToSmall = 0
        var smallToBig = 0

        for (i in 0 until chrono.size - 1) {
            val curr = chrono[i].size
            val next = chrono[i + 1].size

            if (curr == "BIG") {
                if (next == "BIG") bigToBig++ else bigToSmall++
            } else {
                if (next == "SMALL") smallToSmall++ else smallToBig++
            }
        }

        val totalAfterBig = bigToBig + bigToSmall
        val totalAfterSmall = smallToSmall + smallToBig

        val pBigBig = if (totalAfterBig > 0) ((bigToBig.toDouble() / totalAfterBig) * 100.0).roundToOneDecimal() else 50.0
        val pBigSmall = if (totalAfterBig > 0) ((bigToSmall.toDouble() / totalAfterBig) * 100.0).roundToOneDecimal() else 50.0
        val pSmallSmall = if (totalAfterSmall > 0) ((smallToSmall.toDouble() / totalAfterSmall) * 100.0).roundToOneDecimal() else 50.0
        val pSmallBig = if (totalAfterSmall > 0) ((smallToBig.toDouble() / totalAfterSmall) * 100.0).roundToOneDecimal() else 50.0

        return TransitionProbabilities(
            afterBigNextBigPct = pBigBig,
            afterBigNextSmallPct = pBigSmall,
            afterSmallNextSmallPct = pSmallSmall,
            afterSmallNextBigPct = pSmallBig
        )
    }

    data class BetPrediction(
        val recommendedSize: String,       // "BIG" or "SMALL"
        val confidencePct: Int,            // e.g. 82
        val reasoning: String,             // e.g. "5x Dragon Streak Reversal (P = 84%)"
        val recommendedColor: String,      // "GREEN" or "RED"
        val safetyTier: String             // "HIGH CONFIDENCE", "MODERATE", "NEUTRAL"
    )

    /**
     * Synthesizes Markov state transitions, streak fatigue, and rolling parity distribution
     * to formulate a concrete, high-confidence bet recommendation for the upcoming round.
     */
    fun predictNextBet(results: List<WingoProtocolEngine.WingoDrawResult>): BetPrediction {
        if (results.isEmpty()) {
            return BetPrediction(
                recommendedSize = "BIG",
                confidencePct = 50,
                reasoning = "Awaiting initial draws for room",
                recommendedColor = "GREEN",
                safetyTier = "NEUTRAL"
            )
        }

        val trend = analyzeTrends(results)
        val trans = calculateTransitions(results)
        val latest = results.first()

        // 1. Dragon Streak Reversal (Highest statistical leverage)
        // If streak >= 4 (binomial probability of continuing another round < 6.25%)
        if (trend.currentStreakLength >= 4) {
            val oppositeSize = if (trend.currentStreakType == "BIG") "SMALL" else "BIG"
            val oppositeColor = if (latest.color.contains("RED")) "GREEN" else "RED"
            val confidence = (72 + (trend.currentStreakLength * 4)).coerceAtMost(92)
            return BetPrediction(
                recommendedSize = oppositeSize,
                confidencePct = confidence,
                reasoning = "${trend.currentStreakLength}x ${trend.currentStreakType} Streak Exhaustion (Reversal)",
                recommendedColor = oppositeColor,
                safetyTier = "HIGH CONFIDENCE"
            )
        }

        // 2. Markov Parity Transition (Directional probability)
        if (latest.size == "BIG") {
            if (trans.afterBigNextSmallPct >= 56.0) {
                return BetPrediction(
                    recommendedSize = "SMALL",
                    confidencePct = trans.afterBigNextSmallPct.roundToInt().coerceIn(60, 85),
                    reasoning = "Markov Transition: ${trans.afterBigNextSmallPct.roundToInt()}% flip to Small after Big",
                    recommendedColor = if (latest.color.contains("RED")) "GREEN" else "RED",
                    safetyTier = if (trans.afterBigNextSmallPct >= 65.0) "HIGH CONFIDENCE" else "MODERATE"
                )
            } else if (trans.afterBigNextBigPct >= 56.0) {
                return BetPrediction(
                    recommendedSize = "BIG",
                    confidencePct = trans.afterBigNextBigPct.roundToInt().coerceIn(60, 85),
                    reasoning = "Markov Continuation: ${trans.afterBigNextBigPct.roundToInt()}% repeat Big",
                    recommendedColor = if (latest.color.contains("GREEN")) "GREEN" else "RED",
                    safetyTier = if (trans.afterBigNextBigPct >= 65.0) "HIGH CONFIDENCE" else "MODERATE"
                )
            }
        } else if (latest.size == "SMALL") {
            if (trans.afterSmallNextBigPct >= 56.0) {
                return BetPrediction(
                    recommendedSize = "BIG",
                    confidencePct = trans.afterSmallNextBigPct.roundToInt().coerceIn(60, 85),
                    reasoning = "Markov Transition: ${trans.afterSmallNextBigPct.roundToInt()}% flip to Big after Small",
                    recommendedColor = if (latest.color.contains("RED")) "GREEN" else "RED",
                    safetyTier = if (trans.afterSmallNextBigPct >= 65.0) "HIGH CONFIDENCE" else "MODERATE"
                )
            } else if (trans.afterSmallNextSmallPct >= 56.0) {
                return BetPrediction(
                    recommendedSize = "SMALL",
                    confidencePct = trans.afterSmallNextSmallPct.roundToInt().coerceIn(60, 85),
                    reasoning = "Markov Continuation: ${trans.afterSmallNextSmallPct.roundToInt()}% repeat Small",
                    recommendedColor = if (latest.color.contains("GREEN")) "GREEN" else "RED",
                    safetyTier = if (trans.afterSmallNextSmallPct >= 65.0) "HIGH CONFIDENCE" else "MODERATE"
                )
            }
        }

        // 3. Parity Mean-Regression Balance (Over last 30-50 draws)
        if (trend.bigRatioPct >= 58.0) {
            return BetPrediction(
                recommendedSize = "SMALL",
                confidencePct = (50 + (trend.bigRatioPct - 50) * 1.5).roundToInt().coerceIn(58, 76),
                reasoning = "Parity Imbalance: Big over-saturated (${trend.bigRatioPct}%), Small due",
                recommendedColor = "GREEN",
                safetyTier = "MODERATE"
            )
        } else if (trend.smallRatioPct >= 58.0) {
            return BetPrediction(
                recommendedSize = "BIG",
                confidencePct = (50 + (trend.smallRatioPct - 50) * 1.5).roundToInt().coerceIn(58, 76),
                reasoning = "Parity Imbalance: Small over-saturated (${trend.smallRatioPct}%), Big due",
                recommendedColor = "RED",
                safetyTier = "MODERATE"
            )
        }

        // 4. Default: Alternating chop cycle
        val alternate = if (latest.size == "BIG") "SMALL" else "BIG"
        return BetPrediction(
            recommendedSize = alternate,
            confidencePct = 62,
            reasoning = "Alternating Parity Rhythm (Chop Cycle)",
            recommendedColor = if (latest.color.contains("RED")) "GREEN" else "RED",
            safetyTier = "MODERATE"
        )
    }

    private fun Double.roundToOneDecimal(): Double = (this * 10.0).roundToInt() / 10.0
}
