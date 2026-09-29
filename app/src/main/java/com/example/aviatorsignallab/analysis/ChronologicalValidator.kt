package com.example.aviatorsignallab.analysis

import com.example.aviatorsignallab.model.DiscoveredPattern
import com.example.aviatorsignallab.model.RoundFeature

object ChronologicalValidator {

    data class ValidationSplit(
        val trainingFeatures: List<RoundFeature>,
        val testFeatures: List<RoundFeature>
    )

    fun splitChronologically(features: List<RoundFeature>, trainRatio: Double = 0.7): ValidationSplit {
        // Group by roundId to maintain round integrity
        val uniqueRounds = features.map { it.roundId }.distinct()
        val splitIndex = (uniqueRounds.size * trainRatio).toInt()

        val trainRoundIds = uniqueRounds.take(splitIndex).toSet()
        val testRoundIds = uniqueRounds.drop(splitIndex).toSet()

        val train = features.filter { it.roundId in trainRoundIds }
        val test = features.filter { it.roundId in testRoundIds }

        return ValidationSplit(train, test)
    }

    /**
     * Evaluates a discovered candidate pattern on unseen chronological test rounds.
     */
    fun evaluateOnTestSet(
        candidateDescriptor: String,
        windowName: String,
        testFeatures: List<RoundFeature>
    ): DiscoveredPattern {
        val testCrashFeatures = testFeatures.filter { !it.isControlWindow && it.windowName == windowName }
        val testControlFeatures = testFeatures.filter { it.isControlWindow }

        var tp = 0
        var fn = 0
        var fp = 0
        var tn = 0

        for (feat in testCrashFeatures) {
            val matches = feat.ngramsSequence.contains(candidateDescriptor) ||
                    feat.windowName.contains(candidateDescriptor) ||
                    (candidateDescriptor.startsWith("HIGH_BURST") && feat.burstiness > 0.5) ||
                    (candidateDescriptor.startsWith("QUIET_GAP") && feat.quietPeriodMs > 300)

            if (matches) tp++ else fn++
        }

        for (feat in testControlFeatures) {
            val matches = feat.ngramsSequence.contains(candidateDescriptor) ||
                    feat.windowName.contains(candidateDescriptor) ||
                    (candidateDescriptor.startsWith("HIGH_BURST") && feat.burstiness > 0.5) ||
                    (candidateDescriptor.startsWith("QUIET_GAP") && feat.quietPeriodMs > 300)

            if (matches) fp++ else tn++
        }

        val totalCrash = tp + fn
        val totalControl = fp + tn

        val precision = if (tp + fp > 0) tp.toDouble() / (tp + fp).toDouble() else 0.0
        val recall = if (totalCrash > 0) tp.toDouble() / totalCrash.toDouble() else 0.0
        val fpr = if (totalControl > 0) fp.toDouble() / totalControl.toDouble() else 0.0

        // Strict validation criteria: Must have significant support, precision >= 0.75, and FPR <= 0.15
        val isValidated = (totalCrash >= 10 && precision >= 0.75 && fpr <= 0.15)

        return DiscoveredPattern(
            patternId = "pat_${System.currentTimeMillis()}_${(100..999).random()}",
            patternType = "CHRONOLOGICAL_SEQUENCE",
            descriptor = candidateDescriptor,
            windowName = windowName,
            crashSupport = tp,
            controlSupport = fp,
            precision = precision,
            recall = recall,
            falsePositiveRate = fpr,
            isValidated = isValidated
        )
    }
}
