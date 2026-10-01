package com.example.aviatorsignallab.analysis

import com.example.aviatorsignallab.model.DiscoveredPattern
import com.example.aviatorsignallab.model.ResearchSummary
import com.example.aviatorsignallab.model.RoundFeature

class ScientificAnalysisEngine {

    fun analyzeDataset(
        totalRoundsCount: Int,
        totalEventsCount: Int,
        allFeatures: List<RoundFeature>
    ): Pair<ResearchSummary, List<DiscoveredPattern>> {
        val crashFeatures = allFeatures.filter { !it.isControlWindow }
        val controlFeatures = allFeatures.filter { it.isControlWindow }

        val uniqueCrashRounds = crashFeatures.map { it.roundId }.distinct().size
        val uniqueControlRounds = controlFeatures.map { it.roundId }.distinct().size

        // If insufficient data, report honestly
        if (uniqueCrashRounds < 5) {
            return Pair(
                ResearchSummary(
                    totalRounds = totalRoundsCount,
                    totalEvents = totalEventsCount,
                    crashRoundsAnalyzed = uniqueCrashRounds,
                    controlWindowsAnalyzed = uniqueControlRounds,
                    conclusion = "INSUFFICIENT HISTORICAL ROUNDS (< 5 CRASHES)",
                    confidence = "LOW"
                ),
                emptyList()
            )
        }

        // Chronological split (70% train, 30% test)
        val split = ChronologicalValidator.splitChronologically(allFeatures, 0.7)
        val trainCrashFeatures = split.trainingFeatures.filter { !it.isControlWindow }
        val trainControlFeatures = split.trainingFeatures.filter { it.isControlWindow }

        // Mine Candidate Sequence Patterns in Training Sample
        val sequenceCountsInCrash = mutableMapOf<String, Int>()
        for (f in trainCrashFeatures) {
            if (f.ngramsSequence.isNotBlank() && f.ngramsSequence != "NONE") {
                sequenceCountsInCrash[f.ngramsSequence] = sequenceCountsInCrash.getOrDefault(f.ngramsSequence, 0) + 1
            }
        }

        val discoveredPatterns = mutableListOf<DiscoveredPattern>()
        val totalTrainCrashes = trainCrashFeatures.map { it.roundId }.distinct().size

        for ((seq, count) in sequenceCountsInCrash) {
            if (com.example.aviatorsignallab.protocol.ProtocolDiscoveryEngine.isTrivialBaselinePattern(seq)) {
                continue
            }
            val supportPct = count.toDouble() / totalTrainCrashes.toDouble()
            // Only examine sequences appearing in at least 30% of crashes
            if (supportPct >= 0.30) {
                // Evaluate on out-of-sample test set
                val validatedPattern = ChronologicalValidator.evaluateOnTestSet(
                    candidateDescriptor = seq,
                    windowName = "T_3.0s_TO_0.1s",
                    testFeatures = split.testFeatures
                )
                discoveredPatterns.add(validatedPattern)
            }
        }

        // Also check timing anomalies: Burstiness & Quiet periods
        val avgCrashQuiet = trainCrashFeatures.map { it.quietPeriodMs }.average()
        val avgControlQuiet = if (trainControlFeatures.isNotEmpty()) trainControlFeatures.map { it.quietPeriodMs }.average() else 0.0

        if (avgCrashQuiet > avgControlQuiet * 2.0 && avgCrashQuiet > 300.0) {
            val quietPattern = ChronologicalValidator.evaluateOnTestSet(
                candidateDescriptor = "QUIET_GAP_${avgCrashQuiet.toInt()}ms",
                windowName = "T_1.0s_TO_0.1s",
                testFeatures = split.testFeatures
            )
            discoveredPatterns.add(quietPattern)
        }

        // HONEST REPORTING: Pick the best validated pattern (highest precision, out-of-sample) rather
        // than reporting fixed placeholder numbers. If nothing actually cleared the validation bar in
        // ChronologicalValidator.evaluateOnTestSet (precision >= 0.75, FPR <= 0.15, >= 10 test crashes),
        // we must say so instead of implying a 96%-precision detector exists when none was found.
        val validatedPatterns = discoveredPatterns.filter { it.isValidated }
        val bestPattern = validatedPatterns.maxByOrNull { it.precision }

        val validatedPatternsCount = validatedPatterns.size
        val discoveredPatternsCount = discoveredPatterns.size

        val conclusion: String
        val confidence: String
        val bestPrecision: Double
        val bestRecall: Double
        val bestFalsePositiveRate: Double
        val activeCandidateDescriptor: String

        if (bestPattern != null) {
            conclusion = "Validated out-of-sample pattern found: ${bestPattern.descriptor}"
            confidence = when {
                bestPattern.precision >= 0.90 && bestPattern.crashSupport >= 20 -> "HIGH"
                bestPattern.precision >= 0.75 -> "MODERATE"
                else -> "LOW"
            }
            bestPrecision = bestPattern.precision
            bestRecall = bestPattern.recall
            bestFalsePositiveRate = bestPattern.falsePositiveRate
            activeCandidateDescriptor = bestPattern.descriptor
        } else {
            conclusion = "No candidate pattern cleared out-of-sample validation (needs precision >= 75%, " +
                "FPR <= 15%, >= 10 test crashes). This summary reflects live telemetry collection only; " +
                "it is NOT used to drive any alert."
            confidence = "CALIBRATING"
            bestPrecision = 0.0
            bestRecall = 0.0
            bestFalsePositiveRate = 0.0
            activeCandidateDescriptor = "NONE_VALIDATED"
        }

        val summary = ResearchSummary(
            totalRounds = totalRoundsCount,
            totalEvents = totalEventsCount,
            crashRoundsAnalyzed = uniqueCrashRounds,
            controlWindowsAnalyzed = uniqueControlRounds,
            discoveredPatternsCount = discoveredPatternsCount,
            validatedPatternsCount = validatedPatternsCount,
            bestPrecision = bestPrecision,
            bestRecall = bestRecall,
            bestFalsePositiveRate = bestFalsePositiveRate,
            confidence = confidence,
            conclusion = conclusion,
            activeCandidateDescriptor = activeCandidateDescriptor
        )

        return Pair(summary, discoveredPatterns)
    }
}
