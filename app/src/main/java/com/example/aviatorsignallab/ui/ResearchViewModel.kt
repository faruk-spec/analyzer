package com.example.aviatorsignallab.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.example.aviatorsignallab.AviatorLabApplication
import com.example.aviatorsignallab.analysis.FeatureExtractor
import com.example.aviatorsignallab.analysis.ScientificAnalysisEngine
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

    // Traffic Inspector LiveData & buffer
    private val _trafficItems = MutableLiveData<List<TrafficItem>>(emptyList())
    val trafficItems: LiveData<List<TrafficItem>> = _trafficItems
    private val trafficBuffer = mutableListOf<TrafficItem>()

    private val logs = mutableListOf<String>()
    var isPaused: Boolean = false

    private val recentEventCounter = AtomicInteger(0)
    private var tickerJob: Job? = null
    private var gapWatcherJob: Job? = null

    // Diagnostics counters
    var wsCount = 0
    var fetchCount = 0
    var xhrCount = 0
    var postMsgCount = 0
    var domCount = 0

    init {
        loadInitialStats()
        startTicker()
        startGapWatcher()
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
                protocolEngine.updateValidatedPatterns(patterns.filter { it.isValidated }.map { it.patternDescriptor })
            }
        }
    }

    private fun startGapWatcher() {
        gapWatcherJob = viewModelScope.launch(Dispatchers.Default) {
            while (isActive) {
                delay(35)
                if (protocolEngine.currentState == GameState.LIVE) {
                    protocolEngine.checkInFlightGap(System.currentTimeMillis())
                }
            }
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

        viewModelScope.launch(Dispatchers.IO) {
            val event = protocolEngine.processRawEvent(transport, direction, payload)
            db.liveEventDao().insertEvent(event)

            val total = db.liveEventDao().getTotalEventsCount()
            _totalEvents.postValue(total)
        }
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

        viewModelScope.launch(Dispatchers.IO) {
            protocolEngine.activeRound?.let {
                db.roundDao().insertRound(it)
                _totalRounds.postValue(db.roundDao().getRoundsCount())
            }
        }
    }

    override fun onPreCrashAlert(roundId: String, currentMultiplier: Double, confidence: String, reason: String) {
        _preCrashAlert.postValue(PreCrashAlertState(true, roundId, currentMultiplier, confidence, reason))
        onDiagnosticsReceived("[PRE_CRASH_SIGNAL] Immediate alert at ${currentMultiplier}x ($reason)")
    }

    override fun onRoundCrashDetected(roundId: String, finalMultiplier: Double, crashTimestamp: Long) {
        _currentMultiplier.postValue(finalMultiplier)
        _preCrashAlert.postValue(null)

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
                protocolEngine.updateValidatedPatterns(patterns.filter { it.isValidated }.map { it.patternDescriptor })
            }
            _researchSummary.postValue(summary)
            _totalRounds.postValue(totalRCount)

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

            _totalRounds.postValue(0)
            _totalEvents.postValue(0)
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
    }
}
