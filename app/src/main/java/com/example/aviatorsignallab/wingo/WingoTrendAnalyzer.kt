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
        val recommendedSize: String,             // "BIG" or "SMALL"
        val confidencePct: Int,                  // e.g. 84
        val reasoning: String,                   // Primary concise reasoning
        val recommendedColor: String,            // "GREEN" or "RED"
        val safetyTier: String,                  // "HIGH CONFIDENCE", "MODERATE", "CAUTION / SKIP"
        val patternName: String = "MULTI_MODEL_CONSENSUS",
        val recommendedNumbers: List<Int> = emptyList(),
        val modelConsensus: String = ""
    )

    private data class SubModelVote(
        val targetSize: String, // "BIG", "SMALL", or "NEUTRAL"
        val weight: Double,
        val patternName: String,
        val reason: String
    )

    /**
     * Enhanced Multi-Factor Ensemble AI Predictor for WinGo.
     * Integrates:
     *  1. Historical k-Gram Pattern Matching (Scanning previous identical 3/4-sequences)
     *  2. Structural Chart Archetypes (Double-Double 2-2, Chop 1-1, Mirror 1-2-1, Dragon Rider/Reversal)
     *  3. Order-2 Markov State Transitions (Conditional bigram/trigram probabilities)
     *  4. Rolling Window Parity Imbalance & Mean Reversion
     *  5. Color Transition Cycles & Number Due/Hot Heuristics
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

        // Chronological order: oldest at index 0, latest at index size - 1
        val chrono = results.reversed()
        val total = chrono.size
        val latest = chrono.last()
        val votes = mutableListOf<SubModelVote>()

        // -------------------------------------------------------------
        // SUB-MODEL 1: Historical k-Gram Sequence Matcher (Pattern Search)
        // -------------------------------------------------------------
        if (total >= 8) {
            val kGramVote = scanHistoricalKgrams(chrono)
            if (kGramVote != null) {
                votes.add(kGramVote)
            }
        }

        // -------------------------------------------------------------
        // SUB-MODEL 2: Structural Chart Archetypes (2-2, 1-1, Dragon)
        // -------------------------------------------------------------
        val archetypeVote = detectChartArchetypes(chrono)
        if (archetypeVote != null) {
            votes.add(archetypeVote)
        }

        // -------------------------------------------------------------
        // SUB-MODEL 3: Order-2 Markov Transition Probabilities
        // -------------------------------------------------------------
        if (total >= 10) {
            val markovVote = calculateMarkovOrder2(chrono)
            if (markovVote != null) {
                votes.add(markovVote)
            }
        }

        // -------------------------------------------------------------
        // SUB-MODEL 4: Parity Saturation & Mean Reversion
        // -------------------------------------------------------------
        val reversionVote = calculateParityReversion(chrono)
        if (reversionVote != null) {
            votes.add(reversionVote)
        }

        // -------------------------------------------------------------
        // SUB-MODEL 5: Trend Summary & Basic Transition Direction
        // -------------------------------------------------------------
        val trans = calculateTransitions(results)
        val basicMarkovVote = if (latest.size == "BIG") {
            if (trans.afterBigNextSmallPct >= 57.0) {
                SubModelVote("SMALL", 1.8, "MARKOV_FLIP", "Markov Flip: ${trans.afterBigNextSmallPct.roundToInt()}% flip to Small")
            } else if (trans.afterBigNextBigPct >= 57.0) {
                SubModelVote("BIG", 1.8, "MARKOV_REPEAT", "Markov Repeat: ${trans.afterBigNextBigPct.roundToInt()}% repeat Big")
            } else null
        } else {
            if (trans.afterSmallNextBigPct >= 57.0) {
                SubModelVote("BIG", 1.8, "MARKOV_FLIP", "Markov Flip: ${trans.afterSmallNextBigPct.roundToInt()}% flip to Big")
            } else if (trans.afterSmallNextSmallPct >= 57.0) {
                SubModelVote("SMALL", 1.8, "MARKOV_REPEAT", "Markov Repeat: ${trans.afterSmallNextSmallPct.roundToInt()}% repeat Small")
            } else null
        }
        if (basicMarkovVote != null) {
            votes.add(basicMarkovVote)
        }

        // -------------------------------------------------------------
        // ENSEMBLE CONSENSUS VOTING AGGREGATION
        // -------------------------------------------------------------
        var weightBig = 0.0
        var weightSmall = 0.0
        var bigVotesCount = 0
        var smallVotesCount = 0

        for (v in votes) {
            when (v.targetSize) {
                "BIG" -> {
                    weightBig += v.weight
                    bigVotesCount++
                }
                "SMALL" -> {
                    weightSmall += v.weight
                    smallVotesCount++
                }
            }
        }

        val totalWeight = weightBig + weightSmall
        val recommendedSize: String
        val dominantWeight: Double
        val dominantVotes: Int
        val primaryPattern: String
        val primaryReason: String

        if (weightBig >= weightSmall) {
            recommendedSize = "BIG"
            dominantWeight = weightBig
            dominantVotes = bigVotesCount
            val topVote = votes.filter { it.targetSize == "BIG" }.maxByOrNull { it.weight }
            primaryPattern = topVote?.patternName ?: "TREND_MOMENTUM"
            primaryReason = topVote?.reason ?: "Weighted Ensemble favors Big"
        } else {
            recommendedSize = "SMALL"
            dominantWeight = weightSmall
            dominantVotes = smallVotesCount
            val topVote = votes.filter { it.targetSize == "SMALL" }.maxByOrNull { it.weight }
            primaryPattern = topVote?.patternName ?: "TREND_MOMENTUM"
            primaryReason = topVote?.reason ?: "Weighted Ensemble favors Small"
        }

        val consensusRatio = if (totalWeight > 0.0) dominantWeight / totalWeight else 0.5
        val totalActiveModels = votes.count { it.targetSize == "BIG" || it.targetSize == "SMALL" }
        val modelConsensus = "$dominantVotes/$totalActiveModels Models Agree"

        // Calibrate Safety Tier and Confidence
        val safetyTier: String
        val confidencePct: Int
        val finalReason: String

        if (consensusRatio >= 0.74 && dominantVotes >= 2) {
            safetyTier = "HIGH CONFIDENCE"
            confidencePct = (78 + (consensusRatio - 0.74) * 55).roundToInt().coerceIn(78, 92)
            finalReason = primaryReason
        } else if (consensusRatio >= 0.60) {
            safetyTier = "MODERATE"
            confidencePct = (64 + (consensusRatio - 0.60) * 45).roundToInt().coerceIn(64, 76)
            finalReason = primaryReason
        } else {
            safetyTier = "CAUTION / SKIP"
            confidencePct = 54
            finalReason = "Diverging Models ($modelConsensus) • High Volatility / Chop"
        }

        // Color & Number Forecast
        val recommendedColor = forecastColor(chrono, recommendedSize)
        val recommendedNumbers = rankNumbers(chrono, recommendedSize, recommendedColor)

        return BetPrediction(
            recommendedSize = recommendedSize,
            confidencePct = confidencePct,
            reasoning = finalReason,
            recommendedColor = recommendedColor,
            safetyTier = safetyTier,
            patternName = primaryPattern,
            recommendedNumbers = recommendedNumbers,
            modelConsensus = modelConsensus
        )
    }

    private fun scanHistoricalKgrams(chrono: List<WingoProtocolEngine.WingoDrawResult>): SubModelVote? {
        val n = chrono.size
        if (n < 6) return null

        // Try 4-gram first
        val k4 = listOf(chrono[n - 4].size, chrono[n - 3].size, chrono[n - 2].size, chrono[n - 1].size)
        var k4Big = 0
        var k4Small = 0
        for (i in 0 until n - 4) {
            if (chrono[i].size == k4[0] &&
                chrono[i + 1].size == k4[1] &&
                chrono[i + 2].size == k4[2] &&
                chrono[i + 3].size == k4[3]
            ) {
                if (chrono[i + 4].size == "BIG") k4Big++ else k4Small++
            }
        }
        val k4Total = k4Big + k4Small
        if (k4Total >= 2) {
            val winRate = if (k4Big > k4Small) k4Big.toDouble() / k4Total else k4Small.toDouble() / k4Total
            if (winRate >= 0.60) {
                val target = if (k4Big > k4Small) "BIG" else "SMALL"
                val pct = (winRate * 100).roundToInt()
                return SubModelVote(target, 3.5, "PATTERN_4GRAM", "4-Round Sequence Match: ${pct}% historical repeat to $target ($k4Total occurrences)")
            }
        }

        // Try 3-gram
        val k3 = listOf(chrono[n - 3].size, chrono[n - 2].size, chrono[n - 1].size)
        var k3Big = 0
        var k3Small = 0
        for (i in 0 until n - 3) {
            if (chrono[i].size == k3[0] &&
                chrono[i + 1].size == k3[1] &&
                chrono[i + 2].size == k3[2]
            ) {
                if (chrono[i + 3].size == "BIG") k3Big++ else k3Small++
            }
        }
        val k3Total = k3Big + k3Small
        if (k3Total >= 3) {
            val winRate = if (k3Big > k3Small) k3Big.toDouble() / k3Total else k3Small.toDouble() / k3Total
            if (winRate >= 0.62) {
                val target = if (k3Big > k3Small) "BIG" else "SMALL"
                val pct = (winRate * 100).roundToInt()
                return SubModelVote(target, 3.0, "PATTERN_3GRAM", "3-Round Pattern Match: ${pct}% historical continuation to $target ($k3Total occurrences)")
            }
        }
        return null
    }

    private fun detectChartArchetypes(chrono: List<WingoProtocolEngine.WingoDrawResult>): SubModelVote? {
        val n = chrono.size
        if (n < 4) return null
        val latest = chrono.last().size

        // 1. Double-Double (2-2) Pattern Recognition
        // Pattern: [B, B, S, S] -> next B | [S, S, B, B] -> next S
        if (n >= 4) {
            val s0 = chrono[n - 4].size
            val s1 = chrono[n - 3].size
            val s2 = chrono[n - 2].size
            val s3 = chrono[n - 1].size

            if (s0 == s1 && s2 == s3 && s0 != s2) {
                val predicted = s0
                return SubModelVote(predicted, 3.2, "DOUBLE_DOUBLE_2_2", "2-2 Double Pair Rhythm: Next cycle expected to start with $predicted")
            }

            if (s1 == s2 && s2 != s3 && (n < 5 || chrono[n - 5].size != s1)) {
                val predicted = s3
                return SubModelVote(predicted, 3.0, "DOUBLE_PAIR_COMPLETION", "2-2 Incomplete Pair: Second $predicted expected to complete pair")
            }
        }

        // 2. Ping-Pong / Chop (1-1 Alternation)
        var chopLen = 1
        for (i in n - 1 downTo 1) {
            if (chrono[i].size != chrono[i - 1].size) {
                chopLen++
            } else {
                break
            }
        }
        if (chopLen >= 3) {
            val predicted = if (latest == "BIG") "SMALL" else "BIG"
            return SubModelVote(predicted, 3.1, "CHOP_1_1_RHYTHM", "1-1 Alternating Chop (${chopLen}x Cycle): Continuation flip to $predicted")
        }

        // 3. Dragon Streak Management (3+ consecutive identical outcomes)
        var streakLen = 1
        for (i in n - 1 downTo 1) {
            if (chrono[i].size == chrono[i - 1].size) streakLen++ else break
        }

        if (streakLen in 3..4) {
            return SubModelVote(latest, 2.6, "DRAGON_RIDER", "Dragon Momentum (${streakLen}x $latest): Trend Continuation")
        } else if (streakLen >= 6) {
            val opposite = if (latest == "BIG") "SMALL" else "BIG"
            return SubModelVote(opposite, 4.0, "DRAGON_REVERSAL", "Extreme Dragon (${streakLen}x $latest): Binomial Fatigue Reversal to $opposite")
        }

        return null
    }

    private fun calculateMarkovOrder2(chrono: List<WingoProtocolEngine.WingoDrawResult>): SubModelVote? {
        val n = chrono.size
        if (n < 6) return null
        val prev1 = chrono[n - 2].size
        val prev0 = chrono[n - 1].size

        var afterNextBig = 0
        var afterNextSmall = 0

        for (i in 0 until n - 2) {
            if (chrono[i].size == prev1 && chrono[i + 1].size == prev0) {
                if (chrono[i + 2].size == "BIG") afterNextBig++ else afterNextSmall++
            }
        }
        val total = afterNextBig + afterNextSmall
        if (total >= 4) {
            val pBig = (afterNextBig.toDouble() / total) * 100.0
            val pSmall = (afterNextSmall.toDouble() / total) * 100.0

            if (pBig >= 58.0) {
                return SubModelVote("BIG", 2.2, "MARKOV_ORDER_2", "2nd-Order Markov Chain: ${pBig.roundToInt()}% historical transition to Big")
            } else if (pSmall >= 58.0) {
                return SubModelVote("SMALL", 2.2, "MARKOV_ORDER_2", "2nd-Order Markov Chain: ${pSmall.roundToInt()}% historical transition to Small")
            }
        }
        return null
    }

    private fun calculateParityReversion(chrono: List<WingoProtocolEngine.WingoDrawResult>): SubModelVote? {
        val window = chrono.takeLast(30)
        if (window.size < 20) return null

        val bigs = window.count { it.size == "BIG" }
        val smalls = window.count { it.size == "SMALL" }
        val total = window.size

        val bigPct = (bigs.toDouble() / total) * 100.0
        val smallPct = (smalls.toDouble() / total) * 100.0

        if (bigPct >= 62.0) {
            return SubModelVote("SMALL", 2.4, "MEAN_REVERSION", "Parity Saturation: Big over-indexed at ${bigPct.roundToInt()}% (Small due)")
        } else if (smallPct >= 62.0) {
            return SubModelVote("BIG", 2.4, "MEAN_REVERSION", "Parity Saturation: Small over-indexed at ${smallPct.roundToInt()}% (Big due)")
        }
        return null
    }

    private fun forecastColor(chrono: List<WingoProtocolEngine.WingoDrawResult>, predictedSize: String): String {
        if (chrono.isEmpty()) return "GREEN"
        val lastColor = chrono.last().color.uppercase()

        var redToGreen = 0
        var redToRed = 0
        var greenToGreen = 0
        var greenToRed = 0

        for (i in 0 until chrono.size - 1) {
            val curr = chrono[i].color.uppercase()
            val next = chrono[i + 1].color.uppercase()
            if (curr.contains("RED")) {
                if (next.contains("GREEN")) redToGreen++ else if (next.contains("RED")) redToRed++
            } else if (curr.contains("GREEN")) {
                if (next.contains("RED")) greenToRed++ else if (next.contains("GREEN")) greenToGreen++
            }
        }

        return if (lastColor.contains("RED")) {
            if (redToGreen > redToRed) "GREEN" else "RED"
        } else {
            if (greenToRed > greenToGreen) "RED" else "GREEN"
        }
    }

    private fun rankNumbers(
        chrono: List<WingoProtocolEngine.WingoDrawResult>,
        predictedSize: String,
        predictedColor: String
    ): List<Int> {
        val window = chrono.takeLast(40)
        val counts = IntArray(10)
        val lastSeen = IntArray(10) { 99 }

        for ((idx, draw) in window.reversed().withIndex()) {
            val n = draw.number
            if (n in 0..9) {
                counts[n]++
                if (lastSeen[n] == 99) lastSeen[n] = idx
            }
        }

        val candidates = (0..9).filter { num ->
            val matchSize = if (predictedSize == "BIG") num >= 5 else num < 5
            val matchColor = when {
                num == 0 -> predictedColor == "RED"
                num == 5 -> predictedColor == "GREEN"
                num in listOf(1, 3, 7, 9) -> predictedColor == "GREEN"
                else -> predictedColor == "RED"
            }
            matchSize && matchColor
        }

        val scored = candidates.map { num ->
            val freqScore = counts[num] * 1.5
            val dueScore = if (lastSeen[num] >= 8) 3.0 else 0.0
            Pair(num, freqScore + dueScore)
        }.sortedByDescending { it.second }

        return scored.take(3).map { it.first }.ifEmpty {
            if (predictedSize == "BIG") listOf(6, 7, 8) else listOf(1, 2, 3)
        }
    }

    private fun Double.roundToOneDecimal(): Double = (this * 10.0).roundToInt() / 10.0
}
