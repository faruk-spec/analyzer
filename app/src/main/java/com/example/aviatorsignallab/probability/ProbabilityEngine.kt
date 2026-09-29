package com.example.aviatorsignallab.probability

import com.example.aviatorsignallab.model.GameRound
import kotlin.math.roundToInt

/**
 * Real-time Mathematical Probability & Survival Engine for Aviator Game.
 *
 * Implements Provably Fair conditional probability distributions:
 *   P(M >= target | M >= current) = current / target
 *
 * Provides real-time survival odds, zone likelihoods, milestone progression,
 * and empirical calibration from captured Room DB round history.
 */
object ProbabilityEngine {

    const val DEFAULT_HOUSE_EDGE: Double = 0.03 // 3% House Edge / 97% RTP

    enum class RiskLevel {
        SAFE,      // >= 70% survival odds (Emerald Green)
        MODERATE,  // 45% - 69% survival odds (Amber / Yellow)
        HIGH,      // 20% - 44% survival odds (Rose / Red)
        EXTREME    // < 20% survival odds (Purple / Crimson)
    }

    data class ZoneProbabilities(
        val safePct: Double,     // 1.00x - 2.00x
        val boostPct: Double,    // 2.01x - 5.00x
        val rocketPct: Double,   // 5.01x - 20.00x
        val moonshotPct: Double  // 20.01x+
    )

    data class SurvivalEstimate(
        val currentMultiplier: Double,
        val nextMilestone: Double,
        val milestoneSurvivalPct: Double,
        val targetMultiplier: Double,
        val targetHitPct: Double,
        val riskLevel: RiskLevel
    )

    data class EmpiricalStats(
        val sampleSize: Int,
        val medianMultiplier: Double,
        val safeZonePct: Double,
        val boostZonePct: Double,
        val rocketZonePct: Double,
        val moonshotZonePct: Double,
        val averageMultiplier: Double
    )

    /**
     * Calculates the real-time conditional survival probability:
     * P(M >= target | M >= current) = current / target
     */
    fun calculateConditionalSurvival(
        currentMultiplier: Double,
        targetMultiplier: Double,
        houseEdge: Double = DEFAULT_HOUSE_EDGE
    ): Double {
        if (targetMultiplier <= 1.00) return 100.0
        val curr = currentMultiplier.coerceAtLeast(1.00)

        if (curr >= targetMultiplier) return 100.0

        // If plane hasn't launched yet (at baseline 1.00x), account for the instant 1.00x crash house edge
        val prob = if (curr <= 1.00) {
            ((1.0 - houseEdge) / targetMultiplier) * 100.0
        } else {
            (curr / targetMultiplier) * 100.0
        }

        return prob.coerceIn(0.0, 100.0)
    }

    /**
     * Determines the next natural flight milestone threshold for display.
     */
    fun getNextMilestone(currentMultiplier: Double): Double {
        val curr = currentMultiplier.coerceAtLeast(1.00)
        return when {
            curr < 1.50 -> 1.50
            curr < 2.00 -> 2.00
            curr < 3.00 -> 3.00
            curr < 5.00 -> 5.00
            curr < 10.00 -> 10.00
            curr < 20.00 -> 20.00
            curr < 50.00 -> 50.00
            curr < 100.00 -> 100.00
            else -> (curr * 1.5).roundToTwoDecimals()
        }
    }

    /**
     * Classifies risk level based on current survival probability.
     */
    fun getRiskLevel(survivalPct: Double): RiskLevel {
        return when {
            survivalPct >= 70.0 -> RiskLevel.SAFE
            survivalPct >= 45.0 -> RiskLevel.MODERATE
            survivalPct >= 20.0 -> RiskLevel.HIGH
            else -> RiskLevel.EXTREME
        }
    }

    /**
     * Calculates comprehensive survival estimate for current flight and user target.
     */
    fun estimateSurvival(
        currentMultiplier: Double,
        userTargetMultiplier: Double = 2.00
    ): SurvivalEstimate {
        val curr = currentMultiplier.coerceAtLeast(1.00)
        val target = if (userTargetMultiplier > 1.00) userTargetMultiplier else 2.00
        val nextMilestone = getNextMilestone(curr)

        val milestoneProb = calculateConditionalSurvival(curr, nextMilestone)
        val targetProb = calculateConditionalSurvival(curr, target)
        val risk = getRiskLevel(milestoneProb)

        return SurvivalEstimate(
            currentMultiplier = curr,
            nextMilestone = nextMilestone,
            milestoneSurvivalPct = milestoneProb.roundToOneDecimal(),
            targetMultiplier = target,
            targetHitPct = targetProb.roundToOneDecimal(),
            riskLevel = risk
        )
    }

    /**
     * Dynamically computes the probability of the plane crashing in each of the 4 zones,
     * conditioned on the current flight multiplier.
     */
    fun calculateZoneProbabilities(currentMultiplier: Double): ZoneProbabilities {
        val curr = currentMultiplier.coerceAtLeast(1.00)

        when {
            // Case 1: In Safe Zone (1.00x - 2.00x)
            curr < 2.00 -> {
                val pCrashSafe = (1.0 - (curr / 2.00)) * 100.0
                val pSurviveSafe = (curr / 2.00)
                val pCrashBoost = pSurviveSafe * (1.0 - (2.00 / 5.00)) * 100.0
                val pCrashRocket = pSurviveSafe * (2.00 / 5.00) * (1.0 - (5.00 / 20.00)) * 100.0
                val pCrashMoonshot = pSurviveSafe * (2.00 / 20.00) * 100.0

                return ZoneProbabilities(
                    safePct = pCrashSafe.roundToOneDecimal(),
                    boostPct = pCrashBoost.roundToOneDecimal(),
                    rocketPct = pCrashRocket.roundToOneDecimal(),
                    moonshotPct = pCrashMoonshot.roundToOneDecimal()
                )
            }

            // Case 2: In Boost Zone (2.01x - 5.00x)
            curr in 2.00..5.00 -> {
                val pCrashBoost = (1.0 - (curr / 5.00)) * 100.0
                val pSurviveBoost = (curr / 5.00)
                val pCrashRocket = pSurviveBoost * (1.0 - (5.00 / 20.00)) * 100.0
                val pCrashMoonshot = pSurviveBoost * (5.00 / 20.00) * 100.0

                return ZoneProbabilities(
                    safePct = 0.0,
                    boostPct = pCrashBoost.roundToOneDecimal(),
                    rocketPct = pCrashRocket.roundToOneDecimal(),
                    moonshotPct = pCrashMoonshot.roundToOneDecimal()
                )
            }

            // Case 3: In Rocket Zone (5.01x - 20.00x)
            curr in 5.00..20.00 -> {
                val pCrashRocket = (1.0 - (curr / 20.00)) * 100.0
                val pCrashMoonshot = (curr / 20.00) * 100.0

                return ZoneProbabilities(
                    safePct = 0.0,
                    boostPct = 0.0,
                    rocketPct = pCrashRocket.roundToOneDecimal(),
                    moonshotPct = pCrashMoonshot.roundToOneDecimal()
                )
            }

            // Case 4: Moonshot Zone (20.01x+)
            else -> {
                return ZoneProbabilities(
                    safePct = 0.0,
                    boostPct = 0.0,
                    rocketPct = 0.0,
                    moonshotPct = 100.0
                )
            }
        }
    }

    /**
     * Analyzes empirical historical rounds to calibrate local platform behavior.
     */
    fun analyzeEmpiricalDistribution(rounds: List<GameRound>): EmpiricalStats? {
        val validRounds = rounds.filter { it.finalMultiplier >= 1.00 }
        if (validRounds.isEmpty()) return null

        val multipliers = validRounds.map { it.finalMultiplier }.sorted()
        val total = multipliers.size

        val median = if (total % 2 == 0) {
            (multipliers[total / 2 - 1] + multipliers[total / 2]) / 2.0
        } else {
            multipliers[total / 2]
        }

        var safeCount = 0
        var boostCount = 0
        var rocketCount = 0
        var moonshotCount = 0
        var sum = 0.0

        for (m in multipliers) {
            sum += m
            when {
                m <= 2.00 -> safeCount++
                m <= 5.00 -> boostCount++
                m <= 20.00 -> rocketCount++
                else -> moonshotCount++
            }
        }

        return EmpiricalStats(
            sampleSize = total,
            medianMultiplier = median.roundToTwoDecimals(),
            safeZonePct = ((safeCount.toDouble() / total) * 100.0).roundToOneDecimal(),
            boostZonePct = ((boostCount.toDouble() / total) * 100.0).roundToOneDecimal(),
            rocketZonePct = ((rocketCount.toDouble() / total) * 100.0).roundToOneDecimal(),
            moonshotZonePct = ((moonshotCount.toDouble() / total) * 100.0).roundToOneDecimal(),
            averageMultiplier = (sum / total).roundToTwoDecimals()
        )
    }

    private fun Double.roundToOneDecimal(): Double = (this * 10.0).roundToInt() / 10.0
    private fun Double.roundToTwoDecimals(): Double = (this * 100.0).roundToInt() / 100.0
}
