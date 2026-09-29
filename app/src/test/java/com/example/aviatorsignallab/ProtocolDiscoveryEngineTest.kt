package com.example.aviatorsignallab

import com.example.aviatorsignallab.protocol.GameState
import com.example.aviatorsignallab.protocol.ProtocolDiscoveryEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProtocolDiscoveryEngineTest {

    @Test
    fun testLobbyNoiseDoesNotTriggerFalseRounds() {
        val engine = ProtocolDiscoveryEngine()

        // Test 1: Random banner ID and lottery countdown from lobby
        val lobbyPayload1 = """{"id": "banner_top_1", "text": "Win up to 500.00", "v": 2.50}"""
        engine.processRawEvent("FETCH", "INCOMING", lobbyPayload1)

        assertEquals(GameState.UNKNOWN, engine.currentState)
        assertEquals("--", engine.currentRoundId)
        assertNull(engine.activeRound)

        // Test 2: Lottery issue draw number
        val lobbyPayload2 = """{"issue": "20260929103001", "val": 1.25, "time": 17200000}"""
        engine.processRawEvent("XHR", "INCOMING", lobbyPayload2)

        assertEquals(GameState.UNKNOWN, engine.currentState)
        assertEquals("--", engine.currentRoundId)
        assertNull(engine.activeRound)
    }

    @Test
    fun testAuthenticAviatorRoundLifecycle() {
        val engine = ProtocolDiscoveryEngine()

        // 1. Authentic Aviator Start with round_id
        val startPayload = """{"type": "round_start", "round_id": "spribe_9827361", "multiplier": 1.00}"""
        engine.processRawEvent("WEBSOCKET", "INCOMING", startPayload)

        assertEquals("spribe_9827361", engine.currentRoundId)
        assertEquals(GameState.ROUND_START, engine.currentState)

        // 2. Flying ticks
        val tickPayload = """{"type": "stage", "multiplier": 1.45}"""
        engine.processRawEvent("WEBSOCKET", "INCOMING", tickPayload)

        assertEquals(GameState.LIVE, engine.currentState)
        assertEquals(1.45, engine.currentMultiplier, 0.001)

        // 3. Crash signal
        val crashPayload = """{"type": "crash", "multiplier": 2.18, "status": "flew_away"}"""
        engine.processRawEvent("WEBSOCKET", "INCOMING", crashPayload)

        assertEquals(GameState.CRASH, engine.currentState)
        assertEquals(2.18, engine.currentMultiplier, 0.001)
    }
}
