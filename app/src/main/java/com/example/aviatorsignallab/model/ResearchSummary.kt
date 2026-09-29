package com.example.aviatorsignallab.model

data class ResearchSummary(
    val totalRounds: Int = 0,
    val totalEvents: Int = 0,
    val crashRoundsAnalyzed: Int = 0,
    val controlWindowsAnalyzed: Int = 0,
    val discoveredPatternsCount: Int = 0,
    val validatedPatternsCount: Int = 0,
    val bestPrecision: Double = 0.0,
    val bestRecall: Double = 0.0,
    val bestFalsePositiveRate: Double = 0.0,
    val confidence: String = "LOW", // LOW, MEDIUM, HIGH
    val conclusion: String = "NO RELIABLE PRE-CRASH ACTIVITY SIGNAL DETECTED",
    val activeCandidateDescriptor: String? = null
)
