package com.example.aviatorsignallab

import com.example.aviatorsignallab.analysis.LeakageGuard
import com.example.aviatorsignallab.model.LiveEvent
import org.junit.Assert.assertEquals
import org.junit.Test

class LeakageGuardTest {

    @Test
    fun testPreCrashFilteringExcludesPostCrashEvents() {
        val crashTimestamp = 10000L

        val events = listOf(
            createEvent(id = 1, timestamp = 8000L, relToCrash = -2000L, isPostCrash = false),
            createEvent(id = 2, timestamp = 9500L, relToCrash = -500L, isPostCrash = false),
            createEvent(id = 3, timestamp = 10000L, relToCrash = 0L, isPostCrash = true),
            createEvent(id = 4, timestamp = 10500L, relToCrash = 500L, isPostCrash = true)
        )

        val filtered = LeakageGuard.filterPreCrashOnly(events, crashTimestamp)

        assertEquals(2, filtered.size)
        assertEquals(1L, filtered[0].id)
        assertEquals(2L, filtered[1].id)
    }

    @Test(expected = IllegalStateException::class)
    fun testAssertNoDataLeakageThrowsOnPostCrash() {
        val crashTimestamp = 10000L
        val leakedEvents = listOf(
            createEvent(id = 1, timestamp = 10000L, relToCrash = 0L, isPostCrash = true)
        )

        LeakageGuard.assertNoDataLeakage(leakedEvents, crashTimestamp)
    }

    private fun createEvent(id: Long, timestamp: Long, relToCrash: Long, isPostCrash: Boolean): LiveEvent {
        return LiveEvent(
            id = id,
            roundId = "rnd_test",
            timestamp = timestamp,
            elapsedMs = 5000L,
            relativeToCrashMs = relToCrash,
            direction = "INCOMING",
            transport = "WEBSOCKET",
            eventType = "MULTIPLIER_UPDATE",
            isPostCrash = isPostCrash
        )
    }
}
