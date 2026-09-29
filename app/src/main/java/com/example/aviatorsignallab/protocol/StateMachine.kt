package com.example.aviatorsignallab.protocol

enum class GameState {
    UNKNOWN,
    ROUND_START,
    LIVE,
    CRASH,
    ROUND_COMPLETE,
    NEXT_ROUND
}

interface StateChangeListener {
    fun onStateChanged(previousState: GameState, newState: GameState, currentRoundId: String, multiplier: Double)
    fun onRoundCrashDetected(roundId: String, finalMultiplier: Double, crashTimestamp: Long)
    fun onRoundStarted(roundId: String, startTimestamp: Long)
    fun onPreCrashAlert(roundId: String, currentMultiplier: Double, confidence: String, reason: String) {}
    fun onPreCrashAlertCleared() {}
}
