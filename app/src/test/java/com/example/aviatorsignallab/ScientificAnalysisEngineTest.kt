package com.example.aviatorsignallab

import com.example.aviatorsignallab.analysis.FeatureExtractor
import com.example.aviatorsignallab.analysis.ScientificAnalysisEngine
import com.example.aviatorsignallab.model.GameRound
import com.example.aviatorsignallab.model.LiveEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScientificAnalysisEngineTest {

    @Test
    fun testWindowFeatureExtraction() {
        val round = GameRound(
            roundId = "rnd_1",
            startTime = 1000L,
            endTime = 8000L,
            durationMs = 7000L,
            finalMultiplier = 2.45,
            crashDetected = true
        )

        val events = listOf(
            // Pre-crash events within T-3.0s to T-0.1s (5000ms to 7900ms)
            createEvent(timestamp = 5500L, relToCrash = -2500L),
            createEvent(timestamp = 6500L, relToCrash = -1500L),
            createEvent(timestamp = 7500L, relToCrash = -500L),
            createEvent(timestamp = 7800L, relToCrash = -200L),
            // Post-crash event (8000ms)
            createEvent(timestamp = 8000L, relToCrash = 0L, isPost = true)
        )

        val preFeatures = FeatureExtractor.extractPreCrashFeatures(round, events)
        val controlFeatures = FeatureExtractor.extractControlFeatures(round, events)

        assertTrue(preFeatures.isNotEmpty())
        assertEquals(FeatureExtractor.PRE_CRASH_WINDOWS.size, preFeatures.size)
        assertTrue(controlFeatures.isNotEmpty())
    }

    @Test
    fun testHonestNoSignalReportingOnRandomData() {
        val engine = ScientificAnalysisEngine()

        // Test with fewer than 5 rounds -> Should return insufficient data
        val (summaryInsufficient, _) = engine.analyzeDataset(2, 50, emptyList())
        assertEquals("LOW", summaryInsufficient.confidence)
        assertTrue(summaryInsufficient.conclusion.contains("INSUFFICIENT"))
    }

    @Test
    fun testProtocolEngineCapturesRoundsOnMultiplierReset() {
        val engine = com.example.aviatorsignallab.protocol.ProtocolDiscoveryEngine()

        // 1. Initial multiplier event
        engine.processRawEvent("DOM", "INTERNAL", """{"text":"1.05x"}""", 1000L)
        assertEquals(com.example.aviatorsignallab.protocol.GameState.LIVE, engine.currentState)
        assertEquals(1.05, engine.currentMultiplier, 0.01)

        // 2. Flight progression
        engine.processRawEvent("WEBSOCKET", "INCOMING", """{"multiplier":2.45}""", 3000L)
        assertEquals(2.45, engine.currentMultiplier, 0.01)

        // 3. Reset back to 1.00x -> Crash of previous round and start of next!
        engine.processRawEvent("DOM", "INTERNAL", """{"text":"1.00x"}""", 5000L)
        assertEquals(com.example.aviatorsignallab.protocol.GameState.LIVE, engine.currentState)

        // 4. Nested WebSocket payload with custom round ID
        engine.processRawEvent("WEBSOCKET", "INCOMING", """{"data":{"multiplier":3.15,"round_id":"884920"}}""", 7000L)
        assertEquals(com.example.aviatorsignallab.protocol.GameState.LIVE, engine.currentState)
        assertEquals(3.15, engine.currentMultiplier, 0.01)
        assertEquals("884920", engine.currentRoundId)
    }

    private fun createEvent(timestamp: Long, relToCrash: Long, isPost: Boolean = false): LiveEvent {
        return LiveEvent(
            roundId = "rnd_1",
            timestamp = timestamp,
            elapsedMs = timestamp - 1000L,
            relativeToCrashMs = relToCrash,
            direction = "INCOMING",
            transport = "WEBSOCKET",
            eventType = "STATE_UPDATE",
            isPostCrash = isPost
        )
    }
}
