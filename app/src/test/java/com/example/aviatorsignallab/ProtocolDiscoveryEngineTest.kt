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

    @Test
    fun testSpribeAviatorExactProtocolLifecycle() {
        val engine = ProtocolDiscoveryEngine()

        // 1. Spribe Aviator cmd 84, sta 1 with rbd "25068823"
        val initPayload = """{"cmd":84,"sta":1,"rbd":"25068823","ttl":5}"""
        engine.processRawEvent("WEBSOCKET", "INCOMING", initPayload)

        assertEquals("25068823", engine.currentRoundId)
        assertEquals(GameState.ROUND_START, engine.currentState)

        // 2. Takeoff: cmd 84, sta 2, mul "1.00"
        val takeoffPayload = """{"cmd":84,"sta":2,"mul":"1.00"}"""
        engine.processRawEvent("WEBSOCKET", "INCOMING", takeoffPayload)

        assertEquals(GameState.LIVE, engine.currentState)
        assertEquals(1.00, engine.currentMultiplier, 0.001)

        // 3. Flight tick: cmd 85, mul "1.36"
        val tickPayload = """{"cmd":85,"mul":"1.36"}"""
        engine.processRawEvent("WEBSOCKET", "INCOMING", tickPayload)

        assertEquals(GameState.LIVE, engine.currentState)
        assertEquals(1.36, engine.currentMultiplier, 0.001)

        // 4. Crash: cmd 84, sta 3, mul "3.36"
        val crashPayload = """{"cmd":84,"sta":3,"mul":"3.36","ss":"6eRsQm7aJs9ZPrbw9LE7TRv2aTbFDb"}"""
        engine.processRawEvent("WEBSOCKET", "INCOMING", crashPayload)

        assertEquals(GameState.CRASH, engine.currentState)
        assertEquals(3.36, engine.currentMultiplier, 0.001)
    }

    @Test
    fun testCasinoLobbyRtpAndBlackjackRejected() {
        val engine = ProtocolDiscoveryEngine()

        // 1. Lobby RTP percentage should be rejected
        val rtpPayload = """{"type":"DOM_MULTIPLIER_UPDATE","multiplier":"97.22","rawText":"RTP\n97.22%"}"""
        engine.processRawEvent("DOM", "INTERNAL", rtpPayload)

        assertEquals(GameState.UNKNOWN, engine.currentState)
        assertEquals("--", engine.currentRoundId)

        // 2. Lobby platformList containing blackjack game should be rejected as round ID
        val lobbyCatalog = """{"data":{"popular":{"platformList":[{"vendorId":23,"gameNameEn":"MultihandBlackjackPro2","winOdds":96.01}]}}}"""
        engine.processRawEvent("XHR", "INCOMING", lobbyCatalog)

        assertEquals(GameState.UNKNOWN, engine.currentState)
        assertEquals("--", engine.currentRoundId)
    }

    @Test
    fun testInstantPreCrashAlertTriggering() {
        var alertTriggered = false
        var alertMult = 0.0

        val listener = object : com.example.aviatorsignallab.protocol.StateChangeListener {
            override fun onStateChanged(previousState: GameState, newState: GameState, currentRoundId: String, multiplier: Double) {}
            override fun onRoundCrashDetected(roundId: String, finalMultiplier: Double, crashTimestamp: Long) {}
            override fun onRoundStarted(roundId: String, startTimestamp: Long) {}
            override fun onPreCrashAlert(roundId: String, currentMultiplier: Double, confidence: String, reason: String) {
                alertTriggered = true
                alertMult = currentMultiplier
            }
        }

        val engine = ProtocolDiscoveryEngine(listener)

        val t0 = 10000L
        // 1. Takeoff
        engine.processRawEvent("WEBSOCKET", "INCOMING", """{"cmd":84,"sta":1,"rbd":"25068823","ttl":5}""", t0)
        engine.processRawEvent("WEBSOCKET", "INCOMING", """{"cmd":84,"sta":2,"mul":"1.00"}""", t0 + 1000L)

        // 2. Flight tick at 1.45x
        engine.processRawEvent("WEBSOCKET", "INCOMING", """{"cmd":85,"mul":"1.45"}""", t0 + 2000L)

        assertEquals(false, alertTriggered)

        // 3. Gap check 150ms later without receiving ticks -> pre-crash alert!
        engine.checkInFlightGap(t0 + 2150L)

        assertEquals(true, alertTriggered)
        assertEquals(1.45, alertMult, 0.001)
    }
}
