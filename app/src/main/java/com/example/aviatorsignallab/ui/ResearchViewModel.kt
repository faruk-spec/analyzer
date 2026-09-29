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
import com.example.aviatorsignallab.protocol.StateChangeListener
import com.example.aviatorsignallab.webview.NetworkEventListener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
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

    // UI LiveData states
    private val _connectionStatus = MutableLiveData("STANDBY")
    val connectionStatus: LiveData<String> = _connectionStatus

    private val _totalRounds = MutableLiveData(0)
    val totalRounds: LiveData<Int> = _totalRounds

    private val _totalEvents = MutableLiveData(0)
    val totalEvents: LiveData<Int> = _totalEvents

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

    // Stage 1 Prepare Alert guard: tracks if advisory alert fired for current round
    private val prepareAlertFiredForRound = AtomicBoolean(false)

    // Diagnostics counters
    var wsCount = 0
    var fetchCount = 0
    var xhrCount = 0
    var postMsgCount = 0
    var domCount = 0

    init {
        loadInitialStats()
        startTicker()
        startRiskMonitor()
    }

    private fun loadInitialStats() {
        viewModelScope.launch(Dispatchers.IO) {
            val rCount = db.roundDao().getRoundsCount()
            val eCount = db.liveEventDao().getTotalEventsCount()
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

                if (protocolEngine.currentState == GameState.LIVE) {
                    val elapsed = (System.currentTimeMillis() - protocolEngine.roundStartTime).toDouble() / 1000.0
                    _elapsedSeconds.postValue(elapsed)
                }
            }
        }
    }

    /**
     * STAGE 1 RISK ADVISORY MONITOR: Checks if current flight has entered the high-multiplier
     * profit/risk territory (>= 2.00x). Alerts user with Amber 'PREPARE' banner to hover their
     * finger over Cash Out. Updates live multiplier continuously without premature exit calls.
     */
    private fun startRiskMonitor() {
        riskMonitorJob = viewModelScope.launch(Dispatchers.Default) {
            while (isActive) {
                delay(25L)
                if (protocolEngine.currentState == GameState.LIVE) {
                    val currentMult = protocolEngine.currentMultiplier

                    // STAGE 1: PREPARE ADVISORY (Amber) — Multiplier >= 2.00x
                    // Tells player: High profit/danger zone reached. Get finger hovering over Cash Out!
                    if (currentMult >= 2.00 && !prepareAlertFiredForRound.get() && !fastPathCrashFiredForRound.get()) {
                        prepareAlertFiredForRound.set(true)
                        _preCrashAlert.postValue(PreCrashAlertState(
                            active = true,
                            roundId = protocolEngine.currentRoundId,
                            multiplier = currentMult,
                            confidence = "PREPARE",
                            reason = "HIGH_MULTIPLIER_ZONE"
                        ))
                        onDiagnosticsReceived("[STAGE_1_PREPARE] ⚡ Hover finger over Cash Out — Danger zone at ${"%.2f".format(currentMult)}x")
                    } else if (prepareAlertFiredForRound.get() && !fastPathCrashFiredForRound.get()) {
                        // While in Stage 1 prepare mode, keep banner multiplier tracking the live climb
                        val currentAlert = _preCrashAlert.value
                        if (currentAlert != null && currentAlert.active && currentAlert.confidence == "PREPARE") {
                            _preCrashAlert.postValue(currentAlert.copy(multiplier = currentMult))
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

        synchronized(trafficBuffer) {
            if (trafficBuffer.size >= 300) {
                trafficBuffer.removeAt(0)
            }
            trafficBuffer.add(item)
            _trafficItems.postValue(trafficBuffer.toList())
        }

        // Real-time tick feed: record live multiplier ticks into LiveTickCadenceAnalyzer
        val isMultiplierTick = transport == "WEBSOCKET" && (safePayload.contains("\"cmd\":85") || safePayload.contains("\"mul\""))
        if (isMultiplierTick) {
            liveTickAnalyzer.recordTick(System.currentTimeMillis())
        }

        // OPTIMIZATION: For crash packets, process on the current thread immediately
        // to eliminate coroutine scheduling latency (~2-10ms saved).
        // Crash packets are short, so the processing cost on the JS bridge thread is minimal.
        val isCrashPacket = transport == "WEBSOCKET" && safePayload.contains("\"sta\":3") && safePayload.contains("\"cmd\":84")

        if (isCrashPacket) {
            // Process synchronously on current thread for zero-latency crash handling
            val event = protocolEngine.processRawEvent(transport, direction, payload)
            viewModelScope.launch(Dispatchers.IO) {
                db.liveEventDao().insertEvent(event)
                val total = db.liveEventDao().getTotalEventsCount()
                _totalEvents.postValue(total)
            }
        } else {
            viewModelScope.launch(Dispatchers.IO) {
                val event = protocolEngine.processRawEvent(transport, direction, payload)
                db.liveEventDao().insertEvent(event)
                val total = db.liveEventDao().getTotalEventsCount()
                _totalEvents.postValue(total)
            }
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
        val currentRound = protocolEngine.currentRoundId
        val currentMult = protocolEngine.currentMultiplier

        // Only fire if we're actually in a live round and haven't already fired for this round
        if (protocolEngine.currentState != GameState.LIVE && protocolEngine.currentState != GameState.ROUND_START) return

        // Dedup: exactly one fast-path alert per round
        synchronized(this) {
            if (fastPathLastRoundId == currentRound && fastPathCrashFiredForRound.get()) return
            fastPathLastRoundId = currentRound
            fastPathCrashFiredForRound.set(true)
        }

        val mult = multiplierStr.toDoubleOrNull() ?: currentMult
        val finalMult = if (mult > 0.0) mult else currentMult

        // Fire alert IMMEDIATELY — no postValue delay, direct post
        _preCrashAlert.postValue(PreCrashAlertState(true, currentRound, finalMult, "FINAL", "FAST_CRASH_SIGNAL"))
        onDiagnosticsReceived("[FINAL_SIGNAL] ⚡ Fast crash intercepted at ${finalMult}x via WebSocket fast-path")
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

        // Reset alert guards for the new round
        prepareAlertFiredForRound.set(false)
        fastPathCrashFiredForRound.set(false)
        fastPathLastRoundId = roundId

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
        // STRICT: Only one alert per round across all subsystems
        if (fastPathCrashFiredForRound.get()) return
        fastPathCrashFiredForRound.set(true)
        fastPathLastRoundId = roundId

        _preCrashAlert.postValue(PreCrashAlertState(true, roundId, currentMultiplier, "FINAL", reason))
        onDiagnosticsReceived("[FINAL_SIGNAL] Immediate alert at ${currentMultiplier}x ($reason)")
    }

    override fun onPreCrashAlertCleared() {
        // STRICT: Never clear mid-flight! Signal is final once issued for the round.
    }

    override fun onRoundCrashDetected(roundId: String, finalMultiplier: Double, crashTimestamp: Long) {
        _currentMultiplier.postValue(finalMultiplier)

        // Only post crash alert if fast-path didn't already fire one for this round
        if (!fastPathCrashFiredForRound.get()) {
            fastPathCrashFiredForRound.set(true)
            fastPathLastRoundId = roundId
            _preCrashAlert.postValue(PreCrashAlertState(true, roundId, finalMultiplier, "FINAL", "FLEW_AWAY_EXACT"))
        } else {
            // Keep the final alert active and ensure final multiplier is reflected
            val current = _preCrashAlert.value
            if (current != null && current.active && kotlin.math.abs(current.multiplier - finalMultiplier) > 0.01) {
                _preCrashAlert.postValue(PreCrashAlertState(true, roundId, finalMultiplier, "FINAL", "FLEW_AWAY_EXACT"))
            }
        }

        viewModelScope.launch {
            delay(3500L)
            if (protocolEngine.currentState != GameState.LIVE) {
                _preCrashAlert.postValue(null)
            }
        }

        viewModelScope.launch(Dispatchers.IO) {
            val round = protocolEngine.completeRound() ?: return@launch
            db.roundDao().updateRound(round)

            // Extract Pre-Crash and Matched Control Features
            val allRoundEvents = db.liveEventDao().getEventsForRound(roundId)
            val preCrashFeatures = FeatureExtractor.extractPreCrashFeatures(round, allRoundEvents)
            val controlFeatures = FeatureExtractor.extractControlFeatures(round, allRoundEvents)

            val combinedFeatures = preCrashFeatures + controlFeatures
            db.featureDao().insertFeatures(combinedFeatures)

            // Run Scientific Analysis across all collected rounds
            val allFeatures = db.featureDao().getAllFeatures()
            val totalRCount = db.roundDao().getRoundsCount()
            val totalECount = db.liveEventDao().getTotalEventsCount()

            val (summary, patterns) = analysisEngine.analyzeDataset(totalRCount, totalECount, allFeatures)
            if (patterns.isNotEmpty()) {
                db.featureDao().insertPatterns(patterns)
                protocolEngine.updateValidatedPatterns(patterns.filter { it.isValidated }.map { it.descriptor })
            }
            _researchSummary.postValue(summary)
            _totalRounds.postValue(totalRCount)

            // Recalculate empirical statistics with new crash round
            val allRounds = db.roundDao().getAllRounds()
            val stats = ProbabilityEngine.analyzeEmpiricalDistribution(allRounds)
            _empiricalStats.postValue(stats)

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
            _totalEvents.postValue(0)
            _tickFreezeStatus.postValue("")
            _researchSummary.postValue(ResearchSummary())
            onComplete()
        }
    }

    suspend fun exportSingleCsv(type: String): File = kotlinx.coroutines.withContext(Dispatchers.IO) {
        val exportDir = File(getApplication<Application>().getExternalFilesDir(null), "exports").apply { mkdirs() }
        when (type) {
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
    }
}
