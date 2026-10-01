package com.example.aviatorsignallab.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.example.aviatorsignallab.AviatorLabApplication
import com.example.aviatorsignallab.analysis.LiveTickCadenceAnalyzer
import com.example.aviatorsignallab.analysis.FeatureExtractor
import com.example.aviatorsignallab.analysis.TickFreezeResult
import com.example.aviatorsignallab.analysis.ScientificAnalysisEngine
import com.example.aviatorsignallab.probability.ProbabilityEngine
import com.example.aviatorsignallab.export.ZipExportManager
import com.example.aviatorsignallab.model.GameRound
import com.example.aviatorsignallab.model.LiveEvent
import com.example.aviatorsignallab.model.ResearchSummary
import com.example.aviatorsignallab.model.TrafficItem
import com.example.aviatorsignallab.protocol.GameState
import com.example.aviatorsignallab.protocol.ProtocolDiscoveryEngine
import com.example.aviatorsignallab.protocol.ServerReverseEngine
import com.example.aviatorsignallab.protocol.StateChangeListener
import com.example.aviatorsignallab.wingo.WingoProtocolEngine
import com.example.aviatorsignallab.wingo.WingoTrendAnalyzer
import com.example.aviatorsignallab.ai.AiConsensusEngine
import com.example.aviatorsignallab.webview.NetworkEventListener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

data class PreCrashAlertState(
    val active: Boolean,
    val roundId: String,
    val multiplier: Double,
    val confidence: String,
    val reason: String,
    val timestamp: Long = System.currentTimeMillis()
)

class ResearchViewModel(application: Application) : AndroidViewModel(application), StateChangeListener, NetworkEventListener {

    private val db = (application as AviatorLabApplication).database
    private val analysisEngine = ScientificAnalysisEngine()
    val zipExportManager = ZipExportManager(application)

    val protocolEngine = ProtocolDiscoveryEngine(this)
    val webhookSyncManager = com.example.aviatorsignallab.sync.WebhookSyncManager(application)
    val liveTickAnalyzer = LiveTickCadenceAnalyzer()

    // AI Hybrid State Management (Supports OpenAI & Gemini)
    private val aiPrefs by lazy { application.getSharedPreferences("ai_engine_prefs", android.content.Context.MODE_PRIVATE) }

    private val _aiProvider = MutableLiveData(AiConsensusEngine.AiProvider.OPENAI)
    val aiProvider: LiveData<AiConsensusEngine.AiProvider> = _aiProvider

    private val _aiApiKey = MutableLiveData("")
    val aiApiKey: LiveData<String> = _aiApiKey

    private val _aiStatus = MutableLiveData("⚡ AI Consensus: Offline (Local Math Engine Active)")
    val aiStatus: LiveData<String> = _aiStatus

    // Aliases for backward compatibility
    val geminiApiKey: LiveData<String> = _aiApiKey
    val geminiStatus: LiveData<String> = _aiStatus

    fun saveAiConfig(provider: AiConsensusEngine.AiProvider, key: String) {
        val trimmed = key.trim()
        aiPrefs.edit()
            .putString("ai_provider", provider.code)
            .putString("ai_api_key", trimmed)
            .apply()

        _aiProvider.postValue(provider)
        _aiApiKey.postValue(trimmed)

        if (trimmed.isBlank()) {
            _aiStatus.postValue("⚡ AI Consensus: Offline (Local Math Engine Active)")
        } else {
            _aiStatus.postValue("⚡ ${provider.displayName}: Connected (Dual-Engine Active)")
        }
    }

    fun saveGeminiApiKey(key: String) {
        saveAiConfig(AiConsensusEngine.AiProvider.GEMINI, key)
    }

    // UI LiveData states
    private val _connectionStatus = MutableLiveData("STANDBY")
    val connectionStatus: LiveData<String> = _connectionStatus

    private val _totalRounds = MutableLiveData(0)
    val totalRounds: LiveData<Int> = _totalRounds

    private val _totalEvents = MutableLiveData(0)
    val totalEvents: LiveData<Int> = _totalEvents
    private val totalEventsCount = AtomicInteger(0)
    private var lastTrafficPostTime = 0L
    private var lastPacketPostTime = 0L

    private val _currentRoundId = MutableLiveData("--")
    val currentRoundId: LiveData<String> = _currentRoundId

    private val _currentMultiplier = MutableLiveData(1.00)
    val currentMultiplier: LiveData<Double> = _currentMultiplier

    private val _elapsedSeconds = MutableLiveData(0.0)
    val elapsedSeconds: LiveData<Double> = _elapsedSeconds

    private val _liveEventRate = MutableLiveData("0 evt/s")
    val liveEventRate: LiveData<String> = _liveEventRate

    private val _researchSummary = MutableLiveData(ResearchSummary())
    val researchSummary: LiveData<ResearchSummary> = _researchSummary

    private val _preCrashAlert = MutableLiveData<PreCrashAlertState?>()
    val preCrashAlert: LiveData<PreCrashAlertState?> = _preCrashAlert

    private val _diagnosticsLog = MutableLiveData<List<String>>(emptyList())
    val diagnosticsLog: LiveData<List<String>> = _diagnosticsLog

    // Real-time tick freeze status for live rounds
    private val _tickFreezeStatus = MutableLiveData<String>("")
    val tickFreezeStatus: LiveData<String> = _tickFreezeStatus

    // Real-Time Probability Engine LiveData
    private val _userTargetMultiplier = MutableLiveData(2.00)
    val userTargetMultiplier: LiveData<Double> = _userTargetMultiplier

    private val _survivalEstimate = MutableLiveData(
        ProbabilityEngine.estimateSurvival(1.00, 2.00)
    )
    val survivalEstimate: LiveData<ProbabilityEngine.SurvivalEstimate> = _survivalEstimate

    private val _zoneProbabilities = MutableLiveData(
        ProbabilityEngine.calculateZoneProbabilities(1.00)
    )
    val zoneProbabilities: LiveData<ProbabilityEngine.ZoneProbabilities> = _zoneProbabilities

    private val _empiricalStats = MutableLiveData<ProbabilityEngine.EmpiricalStats?>()
    val empiricalStats: LiveData<ProbabilityEngine.EmpiricalStats?> = _empiricalStats

    fun setTargetMultiplier(target: Double) {
        val validTarget = if (target >= 1.05) target else 2.00
        _userTargetMultiplier.postValue(validTarget)
        updateProbabilityEstimates(protocolEngine.currentMultiplier, validTarget)
    }

    fun updateProbabilityEstimates(currentMultiplier: Double, target: Double = _userTargetMultiplier.value ?: 2.00) {
        val estimate = ProbabilityEngine.estimateSurvival(currentMultiplier, target)
        val zones = ProbabilityEngine.calculateZoneProbabilities(currentMultiplier)
        _survivalEstimate.postValue(estimate)
        _zoneProbabilities.postValue(zones)
    }

    // Server Reverse Engineering & Live Bet Telemetry LiveData
    private val _betTelemetry = MutableLiveData(ServerReverseEngine.RoundBetTelemetry("--"))
    val betTelemetry: LiveData<ServerReverseEngine.RoundBetTelemetry> = _betTelemetry

    private val _recentDisassembledPackets = MutableLiveData<List<ServerReverseEngine.DisassembledPacket>>(emptyList())
    val recentDisassembledPackets: LiveData<List<ServerReverseEngine.DisassembledPacket>> = _recentDisassembledPackets
    private val packetDisassemblyBuffer = mutableListOf<ServerReverseEngine.DisassembledPacket>()

    // App Mode Toggle: AVIATOR vs WINGO (Default to AVIATOR)
    enum class AppMode { AVIATOR, WINGO }

    private val _appMode = MutableLiveData(AppMode.AVIATOR)
    val appMode: LiveData<AppMode> = _appMode

    fun setAppMode(mode: AppMode) {
        _appMode.postValue(mode)
    }

    // Target Multiplier Mode: Standard (All flights >= 1.80x) vs Sniper (>10x Only)
    private val _isTarget10xOnly = MutableLiveData(false)
    val isTarget10xOnly: LiveData<Boolean> = _isTarget10xOnly

    fun setTarget10xMode(enabled: Boolean) {
        _isTarget10xOnly.postValue(enabled)
        onDiagnosticsReceived(if (enabled) "🎯 Target Mode: >10x (High-Multiplier Sniper Alert Armed)" else "⚡ Target Mode: Standard (All Flights >= 1.80x)")
    }

    // WinGo / BigSmall Lottery Protocol Engine & LiveData
    private val _activeWingoRoom = MutableLiveData(WingoProtocolEngine.WingoRoom.WINGO_30S)
    val activeWingoRoom: LiveData<WingoProtocolEngine.WingoRoom> = _activeWingoRoom

    private val _wingoPrediction = MutableLiveData(WingoTrendAnalyzer.predictNextBet(emptyList()))
    val wingoPrediction: LiveData<WingoTrendAnalyzer.BetPrediction> = _wingoPrediction

    fun setWingoRoom(room: WingoProtocolEngine.WingoRoom) {
        _activeWingoRoom.postValue(room)
        wingoEngine.setActiveRoom(room)
        val state = wingoEngine.getActiveRoomState()
        val issue = WingoProtocolEngine.WingoIssueInfo(state.currentPeriod, state.remainingSeconds, state.isLocked, room)
        _wingoIssue.postValue(issue)
        _wingoHistory.postValue(state.history.toList())
        _wingoTrendSummary.postValue(state.trendSummary)
        _wingoTransitions.postValue(state.transitions)
        _wingoPrediction.postValue(state.prediction)
        _wingoAuditStats.postValue(wingoEngine.getAuditStats(room))
        if (state.history.isNotEmpty()) {
            _latestWingoDraw.postValue(state.history.first())
        }

        // Trigger immediate eager fetch for the newly selected room with anti-cache headers
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val timestamp = System.currentTimeMillis()
                val url = "https://draw.ar-lottery06.com/WinGo/${room.roomCode}/GetHistoryIssuePage.json?_t=$timestamp"
                val req = okhttp3.Request.Builder()
                    .url(url)
                    .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36")
                    .header("Cache-Control", "no-cache, no-store, must-revalidate")
                    .header("Pragma", "no-cache")
                    .header("Expires", "0")
                    .build()
                val resp = httpClient.newCall(req).execute()
                val body = resp.body?.string()
                if (!body.isNullOrEmpty()) {
                    wingoEngine.processPayload("CDN", url, body)
                }
            } catch (e: Exception) {}
        }
    }

    /**
     * Complete cache purge & re-sync (app-restart behavior).
     * Clears all historical draw buffers, resets indicators, and re-queries live CDN feeds with fresh timestamps.
     */
    fun resetAndResyncAll() {
        viewModelScope.launch(Dispatchers.IO) {
            // 1. Purge all in-memory room draw histories
            for (state in wingoEngine.roomStates.values) {
                synchronized(state.history) {
                    state.history.clear()
                }
                state.trendSummary = WingoTrendAnalyzer.analyzeTrends(emptyList())
                state.transitions = WingoTrendAnalyzer.calculateTransitions(emptyList())
                state.prediction = WingoTrendAnalyzer.predictNextBet(emptyList())
            }

            val currentRoom = _activeWingoRoom.value ?: WingoProtocolEngine.WingoRoom.WINGO_30S
            val activeState = wingoEngine.getActiveRoomState()
            val issue = WingoProtocolEngine.WingoIssueInfo(activeState.currentPeriod, activeState.remainingSeconds, activeState.isLocked, currentRoom)
            _wingoIssue.postValue(issue)
            _wingoHistory.postValue(emptyList())
            _wingoTrendSummary.postValue(activeState.trendSummary)
            _wingoTransitions.postValue(activeState.transitions)
            _wingoPrediction.postValue(activeState.prediction)
            _latestWingoDraw.postValue(null)

            // 2. Fetch fresh, un-cached draws for all 4 rooms
            for (room in WingoProtocolEngine.WingoRoom.values()) {
                try {
                    val timestamp = System.currentTimeMillis()
                    val url = "https://draw.ar-lottery06.com/WinGo/${room.roomCode}/GetHistoryIssuePage.json?_t=$timestamp"
                    val req = okhttp3.Request.Builder()
                        .url(url)
                        .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36")
                        .header("Cache-Control", "no-cache, no-store, must-revalidate")
                        .header("Pragma", "no-cache")
                        .header("Expires", "0")
                        .build()
                    val resp = httpClient.newCall(req).execute()
                    val body = resp.body?.string()
                    if (!body.isNullOrEmpty()) {
                        wingoEngine.processPayload("CDN", url, body)
                    }
                } catch (e: Exception) {}
            }

            // 3. Re-calculate empirical dataset stats
            loadInitialStats()
        }
    }

    private val _latestWingoDraw = MutableLiveData<WingoProtocolEngine.WingoDrawResult?>()
    val latestWingoDraw: LiveData<WingoProtocolEngine.WingoDrawResult?> = _latestWingoDraw

    private val _wingoIssue = MutableLiveData(
        WingoProtocolEngine.WingoIssueInfo("--", 0, false, WingoProtocolEngine.WingoRoom.WINGO_30S)
    )
    val wingoIssue: LiveData<WingoProtocolEngine.WingoIssueInfo> = _wingoIssue

    private val _wingoHistory = MutableLiveData<List<WingoProtocolEngine.WingoDrawResult>>(emptyList())
    val wingoHistory: LiveData<List<WingoProtocolEngine.WingoDrawResult>> = _wingoHistory

    private val _wingoTrendSummary = MutableLiveData(WingoTrendAnalyzer.analyzeTrends(emptyList()))
    val wingoTrendSummary: LiveData<WingoTrendAnalyzer.TrendSummary> = _wingoTrendSummary

    private val _wingoTransitions = MutableLiveData(WingoTrendAnalyzer.calculateTransitions(emptyList()))
    val wingoTransitions: LiveData<WingoTrendAnalyzer.TransitionProbabilities> = _wingoTransitions

    private val _wingoAuditStats = MutableLiveData(WingoProtocolEngine.AuditStats())
    val wingoAuditStats: LiveData<WingoProtocolEngine.AuditStats> = _wingoAuditStats

    fun exportWingoAuditCsv(): String {
        val currentRoom = _activeWingoRoom.value ?: WingoProtocolEngine.WingoRoom.WINGO_30S
        return wingoEngine.exportDataAndAuditCsv(currentRoom)
    }

    private fun requestAiConsensusAsync(
        history: List<WingoProtocolEngine.WingoDrawResult>,
        currentPeriod: String,
        baselinePred: WingoTrendAnalyzer.BetPrediction
    ) {
        val apiKey = _aiApiKey.value ?: ""
        if (apiKey.isBlank() || history.isEmpty()) return

        val provider = _aiProvider.value ?: AiConsensusEngine.AiProvider.OPENAI
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val aiResult = AiConsensusEngine.analyzeRounds(provider, apiKey, history, currentPeriod)
                if (aiResult != null) {
                    val isConsensus = aiResult.recommendedSize.equals(baselinePred.recommendedSize, ignoreCase = true)
                    val blendedConfidence = if (isConsensus) {
                        (baselinePred.confidencePct + 8).coerceAtMost(88)
                    } else {
                        baselinePred.confidencePct
                    }
                    val blendedConsensus = if (isConsensus) {
                        "DUAL CONSENSUS: Math + ${provider.code}"
                    } else {
                        "DIVERGENCE: Math [${baselinePred.recommendedSize}] vs AI [${aiResult.recommendedSize}]"
                    }
                    val combinedNumbers = if (aiResult.recommendedNumbers.isNotEmpty()) {
                        (baselinePred.recommendedNumbers + aiResult.recommendedNumbers).distinct().take(3)
                    } else {
                        baselinePred.recommendedNumbers
                    }
                    val updated = baselinePred.copy(
                        confidencePct = blendedConfidence,
                        modelConsensus = blendedConsensus,
                        recommendedNumbers = combinedNumbers,
                        reasoning = "${baselinePred.reasoning} • AI: ${aiResult.reasoning}"
                    )
                    _wingoPrediction.postValue(updated)
                }
            } catch (e: Exception) {
                android.util.Log.e("ResearchViewModel", "AI Consensus failure: ${e.message}")
            }
        }
    }

    val wingoEngine: WingoProtocolEngine = WingoProtocolEngine(object : WingoProtocolEngine.WingoListener {
        override fun onAuditUpdated(stats: WingoProtocolEngine.AuditStats, latestAudit: WingoProtocolEngine.WingoPredictionAudit?) {
            _wingoAuditStats.postValue(stats)
        }
        override fun onNewDrawResult(result: WingoProtocolEngine.WingoDrawResult, history: List<WingoProtocolEngine.WingoDrawResult>) {
            val targetRoom = WingoProtocolEngine.WingoRoom.fromPeriodId(result.periodId)
            if (targetRoom == null || targetRoom == _activeWingoRoom.value) {
                _latestWingoDraw.postValue(result)
                _wingoHistory.postValue(history)
                val trend = WingoTrendAnalyzer.analyzeTrends(history)
                val trans = WingoTrendAnalyzer.calculateTransitions(history)
                val pred = WingoTrendAnalyzer.predictNextBet(history, _wingoIssue.value?.currentPeriod ?: "--")
                _wingoTrendSummary.postValue(trend)
                _wingoTransitions.postValue(trans)
                _wingoPrediction.postValue(pred)
                requestAiConsensusAsync(history, _wingoIssue.value?.currentPeriod ?: "--", pred)
            }
        }

        override fun onIssueUpdated(issue: WingoProtocolEngine.WingoIssueInfo) {
            if (issue.room == _activeWingoRoom.value) {
                _wingoIssue.postValue(issue)
            }
        }

        override fun onHistoryLoaded(results: List<WingoProtocolEngine.WingoDrawResult>) {
            _wingoHistory.postValue(results)
            val trend = WingoTrendAnalyzer.analyzeTrends(results)
            val trans = WingoTrendAnalyzer.calculateTransitions(results)
            val pred = WingoTrendAnalyzer.predictNextBet(results, _wingoIssue.value?.currentPeriod ?: "--")
            _wingoTrendSummary.postValue(trend)
            _wingoTransitions.postValue(trans)
            _wingoPrediction.postValue(pred)
            requestAiConsensusAsync(results, _wingoIssue.value?.currentPeriod ?: "--", pred)
            if (results.isNotEmpty()) {
                _latestWingoDraw.postValue(results.first())
            }
        }

        override fun onRoomUpdated(
            room: WingoProtocolEngine.WingoRoom,
            issue: WingoProtocolEngine.WingoIssueInfo,
            history: List<WingoProtocolEngine.WingoDrawResult>,
            trend: WingoTrendAnalyzer.TrendSummary,
            trans: WingoTrendAnalyzer.TransitionProbabilities,
            pred: WingoTrendAnalyzer.BetPrediction
        ) {
            if (room == _activeWingoRoom.value) {
                _wingoIssue.postValue(issue)
                _wingoHistory.postValue(history)
                _wingoTrendSummary.postValue(trend)
                _wingoTransitions.postValue(trans)
                _wingoPrediction.postValue(pred)
                if (history.isNotEmpty()) {
                    _latestWingoDraw.postValue(history.first())
                }
            }
        }
    })

    fun verifyProvablyFair(serverSeed: String, clientSeed: String, recordedMul: Double): ServerReverseEngine.ProvablyFairResult {
        return ServerReverseEngine.verifyProvablyFair(serverSeed, clientSeed, recordedMul)
    }

    // Traffic Inspector LiveData & buffer
    private val _trafficItems = MutableLiveData<List<TrafficItem>>(emptyList())
    val trafficItems: LiveData<List<TrafficItem>> = _trafficItems
    private val trafficBuffer = mutableListOf<TrafficItem>()

    private val logs = mutableListOf<String>()
    var isPaused: Boolean = false

    private val recentEventCounter = AtomicInteger(0)
    private var tickerJob: Job? = null
    private var gapWatcherJob: Job? = null
    private var riskMonitorJob: Job? = null

    // Fast-path crash dedup guard: prevents double-alerting when the normal pipeline
    // processes the same crash packet that the fast-path already handled.
    private val fastPathCrashFiredForRound = AtomicBoolean(false)
    private var fastPathLastRoundId: String = ""
    private var lastStutterAlertMult: Double = 0.0
    private var lastStutterAlertTime: Long = 0L

    // Stage 1 Prepare Alert guard: tracks if advisory alert fired for current round
    private val prepareAlertFiredForRound = AtomicBoolean(false)

    // WinGo autonomous engine jobs
    private var wingoTickerJob: Job? = null
    private var wingoCdnPollerJob: Job? = null
    private val httpClient = okhttp3.OkHttpClient.Builder()
        .connectTimeout(4, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(4, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    // Diagnostics counters
    var wsCount = 0
    var fetchCount = 0
    var xhrCount = 0
    var postMsgCount = 0
    var domCount = 0

    init {
        val savedProvCode = aiPrefs.getString("ai_provider", "OPENAI") ?: "OPENAI"
        val savedProv = AiConsensusEngine.AiProvider.fromCode(savedProvCode)
        val savedKey = aiPrefs.getString("ai_api_key", "") ?: ""
        if (savedKey.isNotBlank()) {
            _aiProvider.value = savedProv
            _aiApiKey.value = savedKey
            _aiStatus.value = "⚡ ${savedProv.displayName}: Connected (Dual-Engine Active)"
        }
        loadInitialStats()
        startTicker()
        startRiskMonitor()
        // WinGo engine completely deactivated to eliminate background network and CPU load
        // startWingoEngine()
    }

    private fun startWingoEngine() {
        // Deactivated completely - zero background polling
    }

    private fun loadInitialStats() {
        viewModelScope.launch(Dispatchers.IO) {
            val rCount = db.roundDao().getRoundsCount()
            val eCount = db.liveEventDao().getTotalEventsCount()
            totalEventsCount.set(eCount)
            _totalRounds.postValue(rCount)
            _totalEvents.postValue(eCount)

            val features = db.featureDao().getAllFeatures()
            val (summary, patterns) = analysisEngine.analyzeDataset(rCount, eCount, features)
            _researchSummary.postValue(summary)
            if (patterns.isNotEmpty()) {
                protocolEngine.updateValidatedPatterns(patterns.filter { it.isValidated }.map { it.descriptor })
            }

            // Calibrate empirical statistics from historical captured rounds
            val allRounds = db.roundDao().getAllRounds()
            val stats = ProbabilityEngine.analyzeEmpiricalDistribution(allRounds)
            _empiricalStats.postValue(stats)
        }
    }

    private fun startTicker() {
        tickerJob = viewModelScope.launch(Dispatchers.Default) {
            while (isActive) {
                delay(1000)
                val countInSecond = recentEventCounter.getAndSet(0)
                _liveEventRate.postValue("$countInSecond evt/s")
                _totalEvents.postValue(totalEventsCount.get())

                if (protocolEngine.currentState == GameState.LIVE) {
                    val elapsed = (System.currentTimeMillis() - protocolEngine.roundStartTime).toDouble() / 1000.0
                    _elapsedSeconds.postValue(elapsed)
                }
            }
        }
    }

    /**
     * REAL-TIME TICK CADENCE FREEZE MONITOR: Fast loop (12ms) evaluating the live
     * tick arrival intervals. If the Spribe server halts multiplier ticks (cadence freeze),
     * fires an immediate PRE-CRASH alert during the gap, 50-180ms BEFORE the game renders "Flew Away".
     */
    private fun startRiskMonitor() {
        riskMonitorJob = viewModelScope.launch(Dispatchers.Default) {
            while (isActive) {
                delay(20L)
                if (protocolEngine.currentState == GameState.LIVE) {
                    val currentMult = protocolEngine.currentMultiplier
                    val now = System.currentTimeMillis()

                    if (currentMult >= 1.25) {
                        val freezeResult = liveTickAnalyzer.checkForFreeze(now, currentMult)
                        if (freezeResult.freezeDetected) {
                            // Cadence gap logged for telemetry without firing fake vibrations
                            onDiagnosticsReceived("[CADENCE_TELEMETRY] Server cadence paused (${freezeResult.currentGapMs}ms @ ${"%.2f".format(currentMult)}x)")
                        }
                    }
                }
            }
        }
    }

    override fun onRawEventReceived(transport: String, direction: String, payload: String?, size: Int) {
        if (isPaused) return

        when (transport) {
            "WEBSOCKET" -> wsCount++
            "FETCH" -> fetchCount++
            "XHR" -> xhrCount++
            "POST_MESSAGE" -> postMsgCount++
            "DOM", "CANVAS" -> domCount++
        }
        recentEventCounter.incrementAndGet()

        val safePayload = payload ?: ""
        val item = TrafficItem(
            transport = transport,
            direction = direction,
            payload = safePayload,
            size = size.coerceAtLeast(safePayload.length)
        )

        val now = System.currentTimeMillis()
        synchronized(trafficBuffer) {
            if (trafficBuffer.size >= 300) {
                trafficBuffer.removeAt(0)
            }
            trafficBuffer.add(item)
            if (now - lastTrafficPostTime >= 250) {
                lastTrafficPostTime = now
                _trafficItems.postValue(trafficBuffer.toList())
            }
        }

        // Disassemble packet and process live player bets & cashouts
        val disassembled = ServerReverseEngine.disassemblePacket(transport, direction, safePayload)
        synchronized(packetDisassemblyBuffer) {
            if (packetDisassemblyBuffer.size >= 100) packetDisassemblyBuffer.removeAt(0)
            packetDisassemblyBuffer.add(disassembled)
            if (now - lastPacketPostTime >= 250) {
                lastPacketPostTime = now
                _recentDisassembledPackets.postValue(packetDisassemblyBuffer.toList())
            }
        }

        val telemetry = ServerReverseEngine.processPacket(disassembled.opcode, safePayload, protocolEngine.currentMultiplier)
        _betTelemetry.postValue(telemetry)

        // Process WinGo / BigSmall payloads if present
        var requestUrl = transport
        if (safePayload.contains("\"__url\"")) {
            try {
                val match = Regex("\"__url\"\\s*:\\s*\"([^\"]+)\"").find(safePayload)
                if (match != null) {
                    requestUrl = match.groupValues[1]
                }
            } catch (e: Exception) {}
        }
        if (wingoEngine.isWingoTraffic(requestUrl, safePayload) || transport == "WINGO_DOM") {
            wingoEngine.processPayload(transport, requestUrl, safePayload)
        }

        // Real-time tick feed: record live multiplier ticks into LiveTickCadenceAnalyzer
        val isMultiplierTick = transport == "WEBSOCKET" && (safePayload.contains("\"cmd\":85") || safePayload.contains("\"mul\""))
        if (isMultiplierTick) {
            liveTickAnalyzer.recordTick(now)
        }

        // ZERO-LATENCY FAST PATH: Process state transitions immediately on caller thread
        // Prevents coroutine queue delays and ensures instantaneous multiplier updates across rounds
        val event = protocolEngine.processRawEvent(transport, direction, payload)
        totalEventsCount.incrementAndGet()

        // Asynchronously persist event to database without blocking live event stream
        viewModelScope.launch(Dispatchers.IO) {
            try {
                db.liveEventDao().insertEvent(event)
            } catch (e: Exception) {}
        }
    }

    /**
     * FAST-PATH: Called directly from the JS bridge thread the instant a crash pattern
     * is detected via raw string matching in JavaScript. This fires ~10-20ms BEFORE
     * the normal processRawEvent pipeline even begins.
     *
     * STRICT: Deduplicated with cadence freeze so only ONE final signal ever fires.
     */
    override fun onCrashFastPath(multiplierStr: String) {
        // Log telemetry packet without firing useless at-crash alerts
        onDiagnosticsReceived("[CRASH_TELEMETRY] Fast packet parsed at ${multiplierStr}x")
    }

    /**
     * ANIMATION STUTTER & RENDER HITCH DETECTOR:
     * Called from JS when the requestAnimationFrame loop drops frames during flight.
     * Fires on the exact animation "blink / stutter" phenomenon immediately preceding the crash.
     */
    override fun onAnimationStutter(deltaMs: Double, multiplierStr: String) {
        // STRICT 1: Must be in active LIVE flight (NEVER after crash, NEVER during countdown/betting)
        if (protocolEngine.currentState != GameState.LIVE) return

        // STRICT 1b: Active round status must explicitly be LIVE
        if (protocolEngine.activeRound?.status != "LIVE") return

        // STRICT 1c: Post-Crash Lockout: Must be at least 3500ms after last crash to prevent post-crash scene hitches from alerting
        val now = System.currentTimeMillis()
        if (protocolEngine.lastCrashTimestamp > 0 && (now - protocolEngine.lastCrashTimestamp < 3500L)) return

        val currentRound = protocolEngine.currentRoundId
        val currentMult = protocolEngine.currentMultiplier
        val parsed = multiplierStr.toDoubleOrNull() ?: 0.0
        val liveMult = if (parsed >= 1.80) maxOf(parsed, currentMult) else currentMult

        // STRICT 1d: Never alert on a multiplier lower than or equal to the previous crash multiplier if within 8s
        if (protocolEngine.lastCrashTimestamp > 0 && (now - protocolEngine.lastCrashTimestamp < 8000L)) {
            val lastCrashMult = protocolEngine.activeRound?.finalMultiplier ?: 0.0
            if (lastCrashMult > 1.0 && liveMult <= lastCrashMult) {
                return // Discard stale/lagged multiplier from previous round
            }
        }

        // STRICT 2: Check Target Filter Mode (>10x vs Standard >=1.80x)
        val is10x = _isTarget10xOnly.value == true
        val minThreshold = if (is10x) 10.0 else 1.80
        if (liveMult < minThreshold) return

        val now = System.currentTimeMillis()

        // STRICT 3: Strictly ONE single alert per round!
        // Completely eliminates multiple alert spam on high multipliers (e.g. 248x round)
        synchronized(this) {
            if (fastPathLastRoundId == currentRound && fastPathCrashFiredForRound.get()) {
                return
            }
            fastPathLastRoundId = currentRound
            fastPathCrashFiredForRound.set(true)
            lastStutterAlertMult = liveMult
            lastStutterAlertTime = now
            liveTickAnalyzer.markAlertFired()
        }

        // Keep UI multiplier synchronously updated to eliminate lag
        _currentMultiplier.postValue(liveMult)

        // STRICT 4: Live authoritative flight multiplier for the alert (never past round values)
        val alertType = if (is10x) "TARGET_10X_SNIPER" else "ANIMATION_MICRO_BLINK"
        _preCrashAlert.postValue(PreCrashAlertState(true, currentRound, liveMult, "CRITICAL", alertType))
        onDiagnosticsReceived("[$alertType] ⚡ Cashout Alert @ ${"%.2f".format(liveMult)}x (${deltaMs.toInt()}ms drop)")
    }

    override fun onDiagnosticsReceived(message: String) {
        synchronized(logs) {
            if (logs.size > 200) logs.removeAt(0)
            logs.add("[${System.currentTimeMillis()}] $message")
            _diagnosticsLog.postValue(logs.toList())
        }

        // If diagnostic is console or internal probe, also add to Traffic Inspector
        val transport = when {
            message.startsWith("[CONSOLE_LOG]") -> "CONSOLE"
            message.startsWith("[CONSOLE_WARN]") -> "CONSOLE"
            message.startsWith("[CONSOLE_ERROR]") -> "CONSOLE"
            message.startsWith("[CONSOLE_INFO]") -> "CONSOLE"
            message.startsWith("[IFRAME]") -> "IFRAME"
            else -> "INTERNAL"
        }
        val direction = when {
            message.startsWith("[CONSOLE_WARN]") -> "WARN"
            message.startsWith("[CONSOLE_ERROR]") -> "ERROR"
            message.startsWith("[CONSOLE_INFO]") -> "INFO"
            else -> "LOG"
        }
        val cleanMsg = message.substringAfter("] ").trim()
        val item = TrafficItem(
            transport = transport,
            direction = direction,
            payload = cleanMsg,
            size = cleanMsg.length
        )
        synchronized(trafficBuffer) {
            if (trafficBuffer.size >= 300) {
                trafficBuffer.removeAt(0)
            }
            trafficBuffer.add(item)
            _trafficItems.postValue(trafficBuffer.toList())
        }
    }

    fun clearTraffic() {
        synchronized(trafficBuffer) {
            trafficBuffer.clear()
            _trafficItems.postValue(emptyList())
        }
    }

    fun getTrafficExportText(filter: String = "", category: String = "ALL"): String {
        val list = synchronized(trafficBuffer) { trafficBuffer.toList() }
        val filtered = list.filter { item ->
            val matchCategory = when (category) {
                "ALL" -> true
                "WEBSOCKET" -> item.transport.equals("WEBSOCKET", ignoreCase = true)
                "CONSOLE" -> item.transport.equals("CONSOLE", ignoreCase = true)
                "NETWORK" -> item.transport.equals("FETCH", ignoreCase = true) || item.transport.equals("XHR", ignoreCase = true)
                "DOM" -> item.transport.equals("DOM", ignoreCase = true) || item.transport.equals("CANVAS", ignoreCase = true)
                else -> true
            }

            val matchQuery = if (filter.isEmpty()) {
                true
            } else {
                item.payload.contains(filter, ignoreCase = true) ||
                        item.transport.contains(filter, ignoreCase = true) ||
                        item.direction.contains(filter, ignoreCase = true)
            }

            matchCategory && matchQuery
        }

        val sb = StringBuilder()
        sb.append("=== AVIATOR SIGNAL LAB TRAFFIC DUMP ===\n")
        sb.append("Total Packets: ${filtered.size}\n")
        sb.append("Filter: '$filter' | Category: '$category'\n\n")

        for (item in filtered) {
            sb.append("[${item.getFormattedTime()}] [${item.transport}] [${item.direction}] (${item.size} B)\n")
            sb.append(item.payload).append("\n\n")
        }
        return sb.toString()
    }

    override fun onStateChanged(previousState: GameState, newState: GameState, currentRoundId: String, multiplier: Double) {
        _currentRoundId.postValue(currentRoundId)
        _currentMultiplier.postValue(multiplier)
        updateProbabilityEstimates(multiplier)
        ServerReverseEngine.recalculateExposure(multiplier)
        _betTelemetry.postValue(ServerReverseEngine.currentTelemetry)

        if (newState == GameState.LIVE || protocolEngine.currentState == GameState.LIVE) {
            liveTickAnalyzer.recordTick(System.currentTimeMillis())
        }

        when (newState) {
            GameState.LIVE -> _connectionStatus.postValue("OBSERVING")
            GameState.CRASH -> _connectionStatus.postValue("CRASH DETECTED")
            GameState.ROUND_START -> _connectionStatus.postValue("ROUND START")
            GameState.ROUND_COMPLETE -> _connectionStatus.postValue("ROUND COMPLETE")
            else -> _connectionStatus.postValue("CONNECTED")
        }
    }

    override fun onRoundStarted(roundId: String, startTimestamp: Long) {
        _currentRoundId.postValue(roundId)
        _currentMultiplier.postValue(1.00)
        _elapsedSeconds.postValue(0.0)
        _preCrashAlert.postValue(null)
        updateProbabilityEstimates(1.00)
        ServerReverseEngine.onRoundStart(roundId)
        _betTelemetry.postValue(ServerReverseEngine.currentTelemetry)

        // Reset alert guards for the new round
        prepareAlertFiredForRound.set(false)
        fastPathCrashFiredForRound.set(false)
        fastPathLastRoundId = roundId
        lastStutterAlertMult = 0.0
        lastStutterAlertTime = 0L

        // Reset live tick cadence analyzer for new round
        liveTickAnalyzer.onNewRound(roundId)

        viewModelScope.launch(Dispatchers.IO) {
            protocolEngine.activeRound?.let {
                db.roundDao().insertRound(it)
                _totalRounds.postValue(db.roundDao().getRoundsCount())
            }
        }
    }

    override fun onPreCrashAlert(roundId: String, currentMultiplier: Double, confidence: String, reason: String) {
        // Discard any legacy post-crash alerts or alerts before multiplier start
        if (reason.startsWith("FLEW_AWAY")) return
        if (protocolEngine.currentState != GameState.LIVE || currentMultiplier < 1.25) return

        val is10x = _isTarget10xOnly.value == true
        if (is10x && currentMultiplier < 10.0) return

        // STRICT: Only one alert per round across all subsystems
        if (fastPathCrashFiredForRound.get()) return
        fastPathCrashFiredForRound.set(true)
        fastPathLastRoundId = roundId

        val alertReason = if (is10x) "TARGET_10X_PRE_CRASH" else reason
        _preCrashAlert.postValue(PreCrashAlertState(true, roundId, currentMultiplier, confidence, alertReason))
        onDiagnosticsReceived("[PRE_CRASH_SIGNAL] ⚡ Pre-crash signal alert at ${currentMultiplier}x ($alertReason)")
    }

    override fun onPreCrashAlertCleared() {
        // STRICT: Never clear mid-flight! Signal is final once issued for the round.
    }

    override fun onRoundCrashDetected(roundId: String, finalMultiplier: Double, crashTimestamp: Long) {
        _currentMultiplier.postValue(finalMultiplier)

        // Clear any pre-crash alert immediately upon crash so no delayed/post-crash alert stays or re-fires!
        _preCrashAlert.postValue(null)

        synchronized(this) {
            fastPathCrashFiredForRound.set(false)
            fastPathLastRoundId = ""
            lastStutterAlertMult = 0.0
            lastStutterAlertTime = 0L
        }

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val round = protocolEngine.completeRound() ?: return@launch
                db.roundDao().updateRound(round)

                // Extract Pre-Crash and Matched Control Features
                val allRoundEvents = db.liveEventDao().getEventsForRound(roundId)
                val preCrashFeatures = FeatureExtractor.extractPreCrashFeatures(round, allRoundEvents)
                val controlFeatures = FeatureExtractor.extractControlFeatures(round, allRoundEvents)

                val combinedFeatures = preCrashFeatures + controlFeatures
                db.featureDao().insertFeatures(combinedFeatures)

                // Offload CPU-bound dataset analysis to Dispatchers.Default so Dispatchers.IO is free for live events
                withContext(Dispatchers.Default) {
                    val allFeatures = db.featureDao().getAllFeatures()
                    val totalRCount = db.roundDao().getRoundsCount()
                    val totalECount = totalEventsCount.get()

                    val (summary, patterns) = analysisEngine.analyzeDataset(totalRCount, totalECount, allFeatures)
                    if (patterns.isNotEmpty()) {
                        db.featureDao().insertPatterns(patterns)
                        protocolEngine.updateValidatedPatterns(patterns.filter { it.isValidated }.map { it.descriptor })
                    }
                    _researchSummary.postValue(summary)
                    _totalRounds.postValue(totalRCount)

                    val allRounds = db.roundDao().getAllRounds()
                    val stats = ProbabilityEngine.analyzeEmpiricalDistribution(allRounds)
                    _empiricalStats.postValue(stats)
                }

                // Diagnostics for live round tick cadence
                val cadenceDiag = liveTickAnalyzer.getDiagnostics()
                onDiagnosticsReceived("[CADENCE] Round finished at ${"%.2f".format(round.finalMultiplier)}x. $cadenceDiag")

                // Trigger Automatic Webhook Telemetry Sync if enabled
                if (webhookSyncManager.isSyncEnabled) {
                    webhookSyncManager.sendRoundTelemetry(round, allRoundEvents).onSuccess {
                        onDiagnosticsReceived("Webhook sync: Sent round ${round.roundId} successfully")
                    }.onFailure { err ->
                        onDiagnosticsReceived("Webhook sync failed: ${err.message}")
                    }
                }
            } catch (e: Exception) {}
        }
    }

    fun setConnectionStatus(status: String) {
        _connectionStatus.postValue(status)
    }

    fun togglePause() {
        isPaused = !isPaused
        if (isPaused) {
            _connectionStatus.postValue("PAUSED")
        } else {
            _connectionStatus.postValue("CONNECTED")
        }
    }

    fun clearAllData(onComplete: () -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            db.roundDao().clearAll()
            db.liveEventDao().clearAll()
            db.featureDao().clearFeatures()
            db.featureDao().clearPatterns()

            // Reset live cadence analyzer
            liveTickAnalyzer.onNewRound("")

            _totalRounds.postValue(0)
            totalEventsCount.set(0)
            _totalEvents.postValue(0)
            _tickFreezeStatus.postValue("")
            _researchSummary.postValue(ResearchSummary())
            onComplete()
        }
    }

    suspend fun exportSingleCsv(type: String): File = kotlinx.coroutines.withContext(Dispatchers.IO) {
        val exportDir = File(getApplication<Application>().getExternalFilesDir(null), "exports").apply { mkdirs() }
        when (type.uppercase()) {
            "ROUNDS" -> {
                val f = File(exportDir, "rounds_${System.currentTimeMillis()}.csv")
                val list = db.roundDao().getAllRounds()
                com.example.aviatorsignallab.export.CsvExporter.exportRounds(list, f)
                f
            }
            "EVENTS" -> {
                val f = File(exportDir, "live_events_${System.currentTimeMillis()}.csv")
                val list = db.liveEventDao().getAllEvents()
                com.example.aviatorsignallab.export.CsvExporter.exportLiveEvents(list, f)
                f
            }
            "FEATURES" -> {
                val f = File(exportDir, "round_features_${System.currentTimeMillis()}.csv")
                val list = db.featureDao().getAllFeatures()
                com.example.aviatorsignallab.export.CsvExporter.exportFeatures(list, f)
                f
            }
            "WINGO", "WINGO_AUDIT" -> {
                val room = _activeWingoRoom.value ?: WingoProtocolEngine.WingoRoom.WINGO_30S
                val roomCode = room.roomCode
                val f = File(exportDir, "wingo_audit_${roomCode}_${System.currentTimeMillis()}.csv")
                val csvContent = exportWingoAuditCsv()
                f.writeText(csvContent, Charsets.UTF_8)
                f
            }
            else -> {
                val f = File(exportDir, "protocol_fields_${System.currentTimeMillis()}.csv")
                val list = db.liveEventDao().getAllEvents()
                com.example.aviatorsignallab.export.CsvExporter.exportProtocolFields(list, f)
                f
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        tickerJob?.cancel()
        riskMonitorJob?.cancel()
        wingoTickerJob?.cancel()
        wingoCdnPollerJob?.cancel()
    }
}
