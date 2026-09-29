package com.example.aviatorsignallab

import com.example.aviatorsignallab.wingo.WingoProtocolEngine
import com.example.aviatorsignallab.wingo.WingoTrendAnalyzer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WingoProtocolEngineTest {

    @Test
    fun testReversedGetHistoryIssuePageJsonParsing() {
        var newDraw: WingoProtocolEngine.WingoDrawResult? = null
        var historySnapshot: List<WingoProtocolEngine.WingoDrawResult>? = null

        val engine = WingoProtocolEngine(object : WingoProtocolEngine.WingoListener {
            override fun onNewDrawResult(
                result: WingoProtocolEngine.WingoDrawResult,
                history: List<WingoProtocolEngine.WingoDrawResult>
            ) {
                newDraw = result
                historySnapshot = history
            }

            override fun onIssueUpdated(issue: WingoProtocolEngine.WingoIssueInfo) {}
            override fun onHistoryLoaded(results: List<WingoProtocolEngine.WingoDrawResult>) {
                historySnapshot = results
            }
        })

        // Real payload from https://draw.ar-lottery06.com/WinGo/WinGo_1M/GetHistoryIssuePage.json
        val realPayload = """
        {
          "data": {
            "list": [
              {"issueNumber":"20260929100011122","number":"6","color":"red","premium":"6","sum":0},
              {"issueNumber":"20260929100011121","number":"3","color":"green","premium":"3","sum":0},
              {"issueNumber":"20260929100011120","number":"1","color":"green","premium":"1","sum":0},
              {"issueNumber":"20260929100011119","number":"6","color":"red","premium":"6","sum":0},
              {"issueNumber":"20260929100011118","number":"9","color":"green","premium":"9","sum":0}
            ],
            "pageNo": 1,
            "totalPage": 50,
            "totalCount": 500
          },
          "code": 0,
          "msg": "Succeed"
        }
        """.trimIndent()

        engine.processPayload("FETCH", "https://draw.ar-lottery06.com/WinGo/WinGo_1M/GetHistoryIssuePage.json", realPayload)

        assertNotNull(historySnapshot)
        assertEquals(5, historySnapshot!!.size)

        val first = historySnapshot!!.first()
        assertEquals("20260929100011122", first.periodId)
        assertEquals(6, first.number)
        assertEquals("BIG", first.size)
        assertEquals("RED", first.color)

        val second = historySnapshot!![1]
        assertEquals(3, second.number)
        assertEquals("SMALL", second.size)
        assertEquals("GREEN", second.color)
    }

    @Test
    fun testTrendAnalyzerDragonStreakAndTransitions() {
        val mockResults = listOf(
            WingoProtocolEngine.WingoDrawResult("p10", 7, "BIG", "GREEN"),
            WingoProtocolEngine.WingoDrawResult("p9", 8, "BIG", "RED"),
            WingoProtocolEngine.WingoDrawResult("p8", 6, "BIG", "RED"),
            WingoProtocolEngine.WingoDrawResult("p7", 9, "BIG", "GREEN"),
            WingoProtocolEngine.WingoDrawResult("p6", 5, "BIG", "GREEN_VIOLET"), // 5x Big streak = Dragon!
            WingoProtocolEngine.WingoDrawResult("p5", 2, "SMALL", "RED"),
            WingoProtocolEngine.WingoDrawResult("p4", 4, "SMALL", "RED"),
            WingoProtocolEngine.WingoDrawResult("p3", 1, "SMALL", "GREEN")
        )

        val trend = WingoTrendAnalyzer.analyzeTrends(mockResults)
        assertEquals(8, trend.totalRoundsAnalyzed)
        assertEquals(5, trend.bigCount)
        assertEquals(3, trend.smallCount)
        assertEquals("BIG", trend.currentStreakType)
        assertEquals(5, trend.currentStreakLength)
        assertTrue(trend.isDragonActive)

        val transitions = WingoTrendAnalyzer.calculateTransitions(mockResults)
        assertTrue(transitions.afterBigNextBigPct > 0.0)
    }

    @Test
    fun testMultiRoomSeparationAndPeriodIdentification() {
        val engine = WingoProtocolEngine()

        assertEquals(WingoProtocolEngine.WingoRoom.WINGO_30S, WingoProtocolEngine.WingoRoom.fromPeriodId("20260929100052317"))
        assertEquals(WingoProtocolEngine.WingoRoom.WINGO_1M, WingoProtocolEngine.WingoRoom.fromPeriodId("20260929100011158"))
        assertEquals(WingoProtocolEngine.WingoRoom.WINGO_3M, WingoProtocolEngine.WingoRoom.fromPeriodId("20260929100020386"))
        assertEquals(WingoProtocolEngine.WingoRoom.WINGO_5M, WingoProtocolEngine.WingoRoom.fromPeriodId("20260929100030231"))

        // Process 30s payload
        val payload30s = """{"data":{"list":[{"issueNumber":"20260929100052317","number":"8","color":"red","premium":"8","sum":0}]}}"""
        engine.processPayload("FETCH", "https://draw.ar-lottery06.com/WinGo/WinGo_30S/GetHistoryIssuePage.json", payload30s)

        // Process 1M payload
        val payload1m = """{"data":{"list":[{"issueNumber":"20260929100011158","number":"3","color":"green","premium":"3","sum":0}]}}"""
        engine.processPayload("FETCH", "https://draw.ar-lottery06.com/WinGo/WinGo_1M/GetHistoryIssuePage.json", payload1m)

        val history30s = engine.getRecentResults(WingoProtocolEngine.WingoRoom.WINGO_30S)
        val history1m = engine.getRecentResults(WingoProtocolEngine.WingoRoom.WINGO_1M)

        assertEquals(1, history30s.size)
        assertEquals(8, history30s[0].number)
        assertEquals("BIG", history30s[0].size)

        assertEquals(1, history1m.size)
        assertEquals(3, history1m[0].number)
        assertEquals("SMALL", history1m[0].size)
    }
}
