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

        val bestPattern = discoveredPatterns.maxByOrNull { it.precision }
        val validatedCount = discoveredPatterns.count { it.isValidated }

        val conclusion: String
        val confidence: String

        if (validatedCount > 0 && bestPattern != null && bestPattern.isValidated) {
            conclusion = "A repeatable pre-crash activity pattern was detected and validated on unseen rounds."
            confidence = "HIGH"
        } else if (bestPattern != null && bestPattern.precision > 0.60 && bestPattern.falsePositiveRate < 0.25) {
            conclusion = "POSSIBLE PRE-CRASH ACTIVITY MATCH (Needs more validation rounds)"
            confidence = "MEDIUM"
        } else {
            conclusion = "NO RELIABLE PRE-CRASH ACTIVITY SIGNAL DETECTED"
            confidence = "LOW"
        }

        val summary = ResearchSummary(
            totalRounds = totalRoundsCount,
            totalEvents = totalEventsCount,
            crashRoundsAnalyzed = uniqueCrashRounds,
            controlWindowsAnalyzed = uniqueControlRounds,
            discoveredPatternsCount = discoveredPatterns.size,
            validatedPatternsCount = validatedCount,
            bestPrecision = bestPattern?.precision ?: 0.0,
            bestRecall = bestPattern?.recall ?: 0.0,
            bestFalsePositiveRate = bestPattern?.falsePositiveRate ?: 0.0,
            confidence = confidence,
            conclusion = conclusion,
            activeCandidateDescriptor = bestPattern?.descriptor
        )

        return Pair(summary, discoveredPatterns)
    }
}
