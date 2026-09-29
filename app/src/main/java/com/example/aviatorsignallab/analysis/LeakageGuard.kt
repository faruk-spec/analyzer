package com.example.aviatorsignallab.analysis

import com.example.aviatorsignallab.model.LiveEvent

object LeakageGuard {

    /**
     * Filters out any post-crash events, ensuring absolute leakage prevention.
     * Any event occurring at or after the crash timestamp (relativeToCrashMs >= 0 or isPostCrash == true)
     * is strictly excluded from pre-crash feature analysis.
     */
    fun filterPreCrashOnly(events: List<LiveEvent>, crashTimestamp: Long): List<LiveEvent> {
        return events.filter { event ->
            // Assertion 1: timestamp must precede crash timestamp
            val isStrictlyBefore = event.timestamp < crashTimestamp

            // Assertion 2: relativeToCrashMs must be negative if populated
            val relValid = (event.relativeToCrashMs == null) || (event.relativeToCrashMs!! < 0)

            // Assertion 3: flag must not be post-crash
            val notFlagged = !event.isPostCrash

            isStrictlyBefore && relValid && notFlagged
        }
    }

    /**
     * Throws an IllegalStateException if any event in the list violates pre-crash temporal causality.
     */
    fun assertNoDataLeakage(events: List<LiveEvent>, crashTimestamp: Long) {
        for (event in events) {
            if (event.timestamp >= crashTimestamp) {
                throw IllegalStateException("LEAKAGE DETECTED: Event timestamp ${event.timestamp} >= crash timestamp $crashTimestamp")
            }
            if (event.isPostCrash) {
                throw IllegalStateException("LEAKAGE DETECTED: Event marked as isPostCrash is present in pre-crash analysis")
            }
            if (event.relativeToCrashMs != null && event.relativeToCrashMs!! >= 0) {
                throw IllegalStateException("LEAKAGE DETECTED: Event relativeToCrashMs (${event.relativeToCrashMs}) >= 0")
            }
        }
    }
}
