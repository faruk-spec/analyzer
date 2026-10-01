package com.example.aviatorsignallab

import android.annotation.SuppressLint
import android.app.Dialog
import android.os.Bundle
import android.view.View
import android.view.Window
import android.webkit.WebSettings
import android.webkit.WebView
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import android.graphics.Color
import androidx.activity.OnBackPressedCallback
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.aviatorsignallab.databinding.ActivityMainBinding
import android.widget.RadioButton
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.example.aviatorsignallab.ai.AiConsensusEngine
import com.example.aviatorsignallab.probability.ProbabilityEngine
import com.example.aviatorsignallab.ui.DiagnosticsDialog
import com.example.aviatorsignallab.ui.ResearchViewModel
import com.example.aviatorsignallab.ui.TrafficInspectorDialog
import com.example.aviatorsignallab.wingo.WingoProtocolEngine
import com.example.aviatorsignallab.wingo.WingoTrendAnalyzer
import kotlin.math.roundToInt
import com.example.aviatorsignallab.update.ApkInstaller
import com.example.aviatorsignallab.update.ReleaseMetadata
import com.example.aviatorsignallab.update.UpdateCheckResult
import com.example.aviatorsignallab.update.UpdateManager
import com.example.aviatorsignallab.webview.GameProtocolBridge
import com.example.aviatorsignallab.webview.InstrumentedWebChromeClient
import com.example.aviatorsignallab.webview.InstrumentedWebViewClient
import com.example.aviatorsignallab.webview.ScriptInjector
import com.example.aviatorsignallab.webview.WebChromeStatusListener
import com.example.aviatorsignallab.webview.WebViewStatusListener
import com.google.android.material.bottomsheet.BottomSheetBehavior
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity(), WebViewStatusListener, WebChromeStatusListener {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: ResearchViewModel by viewModels()
    private lateinit var bottomSheetBehavior: BottomSheetBehavior<View>
    private lateinit var updateManager: UpdateManager
    private var isBubbleModeActive: Boolean = false
    private var selectedBaseUnit: Int = 100

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Handle safe system window insets so top controls are never hidden under camera notch or status bar
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val statusBarHeight = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.statusBars()).top
            binding.topAppBar.setPadding(
                binding.topAppBar.paddingLeft,
                statusBarHeight + 4,
                binding.topAppBar.paddingRight,
                4
            )
            insets
        }

        updateManager = UpdateManager(this)

        setupBottomSheet()
        setupWebView()
        observeViewModel()
        setupListeners()
        setupBackNavigation()

        // Load target URL
        binding.webView.loadUrl(BuildConfig.TARGET_URL)
    }

    private fun setupBottomSheet() {
        val sheetView = binding.bottomSheetResearch.root
        bottomSheetBehavior = BottomSheetBehavior.from(sheetView)
        bottomSheetBehavior.state = BottomSheetBehavior.STATE_HIDDEN

        bottomSheetBehavior.addBottomSheetCallback(object : BottomSheetBehavior.BottomSheetCallback() {
            override fun onStateChanged(bottomSheet: View, newState: Int) {
                if (newState == BottomSheetBehavior.STATE_HIDDEN || newState == BottomSheetBehavior.STATE_COLLAPSED) {
                    binding.bottomNav.menu.findItem(R.id.nav_game)?.isChecked = true
                } else if (newState == BottomSheetBehavior.STATE_EXPANDED) {
                    binding.bottomNav.menu.findItem(R.id.nav_signals)?.isChecked = true
                }
            }

            override fun onSlide(bottomSheet: View, slideOffset: Float) {}
        })

        binding.bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_game -> {
                    bottomSheetBehavior.state = BottomSheetBehavior.STATE_HIDDEN
                    true
                }
                R.id.nav_signals -> {
                    bottomSheetBehavior.state = BottomSheetBehavior.STATE_EXPANDED
                    true
                }
                R.id.nav_traffic -> {
                    bottomSheetBehavior.state = BottomSheetBehavior.STATE_HIDDEN
                    TrafficInspectorDialog(this, viewModel).show()
                    true
                }
                R.id.nav_export -> {
                    bottomSheetBehavior.state = BottomSheetBehavior.STATE_EXPANDED
                    binding.bottomSheetResearch.root.post {
                        binding.bottomSheetResearch.btnExportAllZip.requestFocus()
                    }
                    true
                }
                else -> false
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        val webView = binding.webView
        val settings = webView.settings

        // Enable third-party cookies for cross-origin game iframes (Spribe / Aviator servers)
        val cookieManager = android.webkit.CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        cookieManager.setAcceptThirdPartyCookies(webView, true)

        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.databaseEnabled = true
        settings.mediaPlaybackRequiresUserGesture = false
        settings.allowFileAccess = false
        settings.allowContentAccess = true
        settings.useWideViewPort = true
        settings.loadWithOverviewMode = true
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        settings.cacheMode = WebSettings.LOAD_DEFAULT
        settings.setSupportMultipleWindows(true)
        settings.javaScriptCanOpenWindowsAutomatically = true

        // Mask WebView User-Agent: strip '; wv' and 'Version/4.0' to prevent anti-bot server connection blocks
        val defaultUa = settings.userAgentString
        settings.userAgentString = defaultUa.replace("; wv", "").replace("Version/4.0 ", "")

        webView.addJavascriptInterface(GameProtocolBridge(viewModel), "AndroidBridge")
        webView.webViewClient = InstrumentedWebViewClient(this)
        webView.webChromeClient = InstrumentedWebChromeClient(this)

        if (androidx.webkit.WebViewFeature.isFeatureSupported(androidx.webkit.WebViewFeature.DOCUMENT_START_SCRIPT)) {
            androidx.webkit.WebViewCompat.addDocumentStartJavaScript(
                webView,
                ScriptInjector.INJECTION_SCRIPT,
                setOf("*")
            )
        }
    }

    private fun observeViewModel() {
        viewModel.connectionStatus.observe(this) { status ->
            binding.tvConnectionStatus.text = status
            binding.tvBubbleStatus.text = status

            // Dismiss pre-crash alert immediately when round crashes, starts, or pauses
            if (status == "CRASH DETECTED" || status == "ROUND START" || status == "ROUND COMPLETE" || status == "STANDBY" || status == "PAUSED") {
                binding.bannerPreCrashAlert.visibility = View.GONE
                binding.webView.evaluateJavascript("if (typeof window.__syncFlight === 'function') window.__syncFlight(0.0, false);", null)
            }

            if (status == "STANDBY" && (viewModel.currentMultiplier.value ?: 1.0) <= 1.0) {
                binding.tvMetricMultiplier.text = "--"
                binding.tvBubbleMultiplier.text = "--"
                binding.tvMetricMultiplier.setTextColor(ContextCompat.getColor(this, R.color.text_muted))
            }
            when (status) {
                "OBSERVING" -> {
                    binding.tvConnectionStatus.setTextColor(ContextCompat.getColor(this, R.color.accent_emerald))
                    binding.tvBubbleStatus.setTextColor(ContextCompat.getColor(this, R.color.accent_emerald))
                }
                "CRASH DETECTED" -> {
                    binding.tvConnectionStatus.setTextColor(ContextCompat.getColor(this, R.color.accent_rose))
                    binding.tvBubbleStatus.setTextColor(ContextCompat.getColor(this, R.color.accent_rose))
                    binding.tvMetricMultiplier.setTextColor(ContextCompat.getColor(this, R.color.accent_rose))
                }
                "ROUND START" -> {
                    binding.tvConnectionStatus.setTextColor(ContextCompat.getColor(this, R.color.accent_cyan))
                    binding.tvBubbleStatus.setTextColor(ContextCompat.getColor(this, R.color.accent_cyan))
                }
                "PAUSED" -> {
                    binding.tvConnectionStatus.setTextColor(ContextCompat.getColor(this, R.color.accent_amber))
                    binding.tvBubbleStatus.setTextColor(ContextCompat.getColor(this, R.color.accent_amber))
                }
                else -> {
                    binding.tvConnectionStatus.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
                    binding.tvBubbleStatus.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
                }
            }
        }

        viewModel.totalRounds.observe(this) { count ->
            binding.tvMetricRounds.text = count.toString()
        }

        viewModel.totalEvents.observe(this) { count ->
            binding.tvMetricEvents.text = count.toString()
        }

        viewModel.currentRoundId.observe(this) { roundId ->
            binding.tvMetricCurRound.text = roundId
            if (roundId == "--" && viewModel.connectionStatus.value == "STANDBY" && (viewModel.currentMultiplier.value ?: 1.0) <= 1.0) {
                binding.tvMetricMultiplier.text = "--"
                binding.tvBubbleMultiplier.text = "--"
            }
        }

        viewModel.currentMultiplier.observe(this) { mult ->
            if (mult > 1.0) {
                val formatted = "%.2fx".format(mult)
                binding.tvMetricMultiplier.text = formatted
                binding.tvBubbleMultiplier.text = formatted

                if (mult >= 1.10) {
                    binding.webView.evaluateJavascript("if (typeof window.__syncFlight === 'function') window.__syncFlight($mult, true);", null)
                }

                // Color adaptive shift
                when {
                    viewModel.connectionStatus.value == "CRASH DETECTED" -> {
                        binding.tvMetricMultiplier.setTextColor(ContextCompat.getColor(this, R.color.accent_rose))
                    }
                    mult >= 10.0 -> {
                        binding.tvMetricMultiplier.setTextColor(ContextCompat.getColor(this, R.color.accent_purple))
                    }
                    mult >= 2.0 -> {
                        binding.tvMetricMultiplier.setTextColor(ContextCompat.getColor(this, R.color.accent_emerald))
                    }
                    else -> {
                        binding.tvMetricMultiplier.setTextColor(ContextCompat.getColor(this, R.color.accent_cyan))
                    }
                }
            } else if (viewModel.connectionStatus.value == "STANDBY" && mult <= 1.0) {
                binding.tvMetricMultiplier.text = "--"
                binding.tvBubbleMultiplier.text = "--"
                binding.tvMetricMultiplier.setTextColor(ContextCompat.getColor(this, R.color.text_muted))
                binding.webView.evaluateJavascript("if (typeof window.__syncFlight === 'function') window.__syncFlight(0.0, false);", null)
            }
        }

        viewModel.elapsedSeconds.observe(this) { sec ->
            binding.tvMetricElapsed.text = "%.1fs".format(sec)
        }

        viewModel.liveEventRate.observe(this) { rate ->
            binding.tvMetricEventRate.text = rate
        }

        viewModel.researchSummary.observe(this) { summary ->
            val sheet = binding.bottomSheetResearch
            sheet.tvSheetRoundsAnalyzed.text = "${summary.crashRoundsAnalyzed} crash rounds"
            sheet.tvSignalConclusion.text = summary.conclusion

            if (summary.conclusion.contains("NO RELIABLE", ignoreCase = true)) {
                sheet.tvSignalConclusion.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
            } else {
                sheet.tvSignalConclusion.setTextColor(ContextCompat.getColor(this, R.color.accent_emerald))
            }

            sheet.tvOutOfSamplePrec.text = "%.2f".format(summary.bestPrecision)
            sheet.tvFalsePositiveRate.text = "%.1f%%".format(summary.bestFalsePositiveRate * 100.0)
            sheet.tvConfidenceLevel.text = summary.confidence

            if (summary.activeCandidateDescriptor != null) {
                sheet.tvSignalDetails.text = "Discovered pattern: ${summary.activeCandidateDescriptor}"
            } else {
                sheet.tvSignalDetails.text = "Scientific verification: Testing pre-crash network activity vs random live control periods."
            }
        }

        // Real-Time Instant Pre-Crash Signal Alert (Zero-Lag BEFORE Crash)
        viewModel.preCrashAlert.observe(this) { alert ->
            if (alert != null && alert.active && alert.multiplier >= 1.80) {
                val liveM = maxOf(alert.multiplier, viewModel.currentMultiplier.value ?: 1.0)
                binding.bannerPreCrashAlert.visibility = View.VISIBLE
                binding.bannerPreCrashAlert.setBackgroundResource(R.drawable.bg_pre_crash_alert)
                binding.ivAlertIcon.setColorFilter(ContextCompat.getColor(this, R.color.accent_rose))

                val titleText = "⚡ MICRO-BLINK DETECTED @ %.2fx — CASH OUT NOW!".format(liveM)
                val subText = "Crash animation hitch detected BEFORE crash • Tap Cash Out!"

                binding.tvAlertTitle.text = titleText
                binding.tvAlertSubtitle.text = subText
                binding.tvBubbleStatus.text = "⚡CASH OUT"
                binding.tvBubbleStatus.setTextColor(ContextCompat.getColor(this, R.color.accent_rose))
                binding.tvBubbleMultiplier.setTextColor(ContextCompat.getColor(this, R.color.accent_rose))
                binding.tvMetricMultiplier.setTextColor(ContextCompat.getColor(this, R.color.accent_rose))

                // Urgent double haptic vibration ONLY when multiplier >= 1.80x in active flight
                triggerImmediateVibration()
            } else {
                binding.bannerPreCrashAlert.visibility = View.GONE
                binding.tvMetricMultiplier.setTextColor(ContextCompat.getColor(this, R.color.accent_blue))
                binding.tvBubbleMultiplier.setTextColor(ContextCompat.getColor(this, R.color.accent_blue))
                binding.tvBubbleStatus.text = "LIVE"
                binding.tvBubbleStatus.setTextColor(ContextCompat.getColor(this, R.color.accent_emerald))
            }
        }

        // Real-Time Mathematical Survival Probability Observer
        viewModel.survivalEstimate.observe(this) { est ->
            if (est != null && viewModel.connectionStatus.value != "STANDBY" && viewModel.currentRoundId.value != "--") {
                val probText = "${est.milestoneSurvivalPct.toInt()}% → ${"%.2f".format(est.nextMilestone)}x"
                binding.tvMetricProb.text = probText
                binding.tvBubbleProb.text = "${est.milestoneSurvivalPct.toInt()}%"

                val targetText = "${"%.2f".format(est.targetMultiplier)}x (${est.targetHitPct.toInt()}%)"
                binding.tvMetricTarget.text = targetText

                val colorRes = when (est.riskLevel) {
                    ProbabilityEngine.RiskLevel.SAFE -> R.color.accent_emerald
                    ProbabilityEngine.RiskLevel.MODERATE -> R.color.accent_amber
                    ProbabilityEngine.RiskLevel.HIGH -> R.color.accent_rose
                    ProbabilityEngine.RiskLevel.EXTREME -> R.color.accent_purple
                }
                binding.tvMetricProb.setTextColor(ContextCompat.getColor(this, colorRes))
                binding.tvBubbleProb.setTextColor(ContextCompat.getColor(this, colorRes))
            } else {
                binding.tvMetricProb.text = "--%"
                binding.tvBubbleProb.text = "--%"
                val curTarget = viewModel.userTargetMultiplier.value ?: 2.00
                binding.tvMetricTarget.text = "${"%.2f".format(curTarget)}x (--%)"
                binding.tvMetricProb.setTextColor(ContextCompat.getColor(this, R.color.text_muted))
                binding.tvBubbleProb.setTextColor(ContextCompat.getColor(this, R.color.text_muted))
            }
        }

        // App Mode Switcher Observer
        viewModel.appMode.observe(this) { mode ->
            if (mode == ResearchViewModel.AppMode.WINGO) {
                binding.btnModeWingo.setBackgroundResource(R.drawable.bg_toggle_selected)
                binding.btnModeWingo.setTextColor(Color.WHITE)
                binding.btnModeAviator.setBackgroundResource(0)
                binding.btnModeAviator.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))

                binding.layoutTopPrediction.visibility = View.VISIBLE
                binding.wingoTopHudStrip.visibility = View.VISIBLE
                binding.metricsStrip.visibility = View.GONE

                binding.bottomSheetResearch.btnSheetTabWingo.performClick()
            } else {
                binding.btnModeAviator.setBackgroundResource(R.drawable.bg_toggle_selected)
                binding.btnModeAviator.setTextColor(Color.WHITE)
                binding.btnModeWingo.setBackgroundResource(0)
                binding.btnModeWingo.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))

                binding.layoutTopPrediction.visibility = View.GONE
                binding.metricsStrip.visibility = View.VISIBLE
                binding.wingoTopHudStrip.visibility = View.GONE

                binding.bottomSheetResearch.btnSheetTabAviator.performClick()
            }
        }

        // Active WinGo Room Observer
        viewModel.activeWingoRoom.observe(this) { activeRoom ->
            fun updateChip(chip: TextView, room: WingoProtocolEngine.WingoRoom) {
                if (room == activeRoom) {
                    chip.setBackgroundResource(R.drawable.bg_toggle_selected)
                    chip.setTextColor(Color.WHITE)
                } else {
                    chip.setBackgroundResource(R.drawable.bg_toggle_unselected)
                    chip.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
                }
            }
            updateChip(binding.chipWingo30s, WingoProtocolEngine.WingoRoom.WINGO_30S)
            updateChip(binding.chipWingo1m, WingoProtocolEngine.WingoRoom.WINGO_1M)
            updateChip(binding.chipWingo3m, WingoProtocolEngine.WingoRoom.WINGO_3M)
            updateChip(binding.chipWingo5m, WingoProtocolEngine.WingoRoom.WINGO_5M)

            updateChip(binding.bottomSheetResearch.sheetChipWingo30s, WingoProtocolEngine.WingoRoom.WINGO_30S)
            updateChip(binding.bottomSheetResearch.sheetChipWingo1m, WingoProtocolEngine.WingoRoom.WINGO_1M)
            updateChip(binding.bottomSheetResearch.sheetChipWingo3m, WingoProtocolEngine.WingoRoom.WINGO_3M)
            updateChip(binding.bottomSheetResearch.sheetChipWingo5m, WingoProtocolEngine.WingoRoom.WINGO_5M)

            binding.bottomSheetResearch.tvSheetWingoRoomName.text = "WinGo ${activeRoom.displayName}"
        }

        // WinGo Period & Live Countdown Observer
        viewModel.wingoIssue.observe(this) { issue ->
            binding.tvWingoPeriod.text = issue.currentPeriod
            binding.bottomSheetResearch.tvSheetWingoPeriod.text = "Period: ${issue.currentPeriod}"

            val mins = issue.remainingSeconds / 60
            val secs = issue.remainingSeconds % 60
            val timeFormatted = String.format(java.util.Locale.US, "%02d:%02d", mins, secs)

            binding.tvWingoCountdown.text = timeFormatted
            binding.bottomSheetResearch.tvSheetWingoCountdown.text = timeFormatted

            if (issue.isLocked) {
                binding.tvWingoLockBadge.text = "LOCKED"
                binding.tvWingoLockBadge.setTextColor(ContextCompat.getColor(this, R.color.accent_rose))
                binding.tvWingoCountdown.setTextColor(ContextCompat.getColor(this, R.color.accent_rose))

                binding.bottomSheetResearch.tvSheetWingoLock.text = "LOCKED"
                binding.bottomSheetResearch.tvSheetWingoLock.setTextColor(ContextCompat.getColor(this, R.color.accent_rose))
                binding.bottomSheetResearch.tvSheetWingoCountdown.setTextColor(ContextCompat.getColor(this, R.color.accent_rose))
            } else {
                binding.tvWingoLockBadge.text = "OPEN"
                binding.tvWingoLockBadge.setTextColor(ContextCompat.getColor(this, R.color.wingo_green))
                binding.tvWingoCountdown.setTextColor(Color.WHITE)

                binding.bottomSheetResearch.tvSheetWingoLock.text = "OPEN"
                binding.bottomSheetResearch.tvSheetWingoLock.setTextColor(ContextCompat.getColor(this, R.color.wingo_green))
                binding.bottomSheetResearch.tvSheetWingoCountdown.setTextColor(Color.WHITE)
            }
        }

        // Latest WinGo Drawn Ball & Size Observer
        viewModel.latestWingoDraw.observe(this) { draw ->
            if (draw != null) {
                binding.tvWingoLastBall.text = draw.number.toString()
                binding.tvWingoLastSize.text = draw.size

                val ballColor = when (draw.color.uppercase()) {
                    "RED", "RED_VIOLET" -> ContextCompat.getColor(this, R.color.wingo_red)
                    "GREEN", "GREEN_VIOLET" -> ContextCompat.getColor(this, R.color.wingo_green)
                    else -> ContextCompat.getColor(this, R.color.wingo_violet)
                }
                val ballDrawable = ContextCompat.getDrawable(this, R.drawable.bg_wingo_ball)?.mutate()
                ballDrawable?.setTint(ballColor)
                binding.tvWingoLastBall.background = ballDrawable

                val sizeColor = if (draw.size == "BIG") {
                    ContextCompat.getColor(this, R.color.wingo_big)
                } else {
                    ContextCompat.getColor(this, R.color.wingo_small)
                }
                binding.tvWingoLastSize.setTextColor(sizeColor)

                // Bottom sheet
                binding.bottomSheetResearch.tvSheetWingoBall.text = draw.number.toString()
                val sheetBallDrawable = ContextCompat.getDrawable(this, R.drawable.bg_wingo_ball)?.mutate()
                sheetBallDrawable?.setTint(ballColor)
                binding.bottomSheetResearch.tvSheetWingoBall.background = sheetBallDrawable

                binding.bottomSheetResearch.tvSheetWingoSize.text = draw.size
                binding.bottomSheetResearch.tvSheetWingoSize.setTextColor(sizeColor)
                binding.bottomSheetResearch.tvSheetWingoColor.text = draw.color.replace("_", "/")
                binding.bottomSheetResearch.tvSheetWingoColor.setTextColor(ballColor)
            }
        }

        // WinGo Trend & Dragon Streak Observer
        viewModel.wingoTrendSummary.observe(this) { trend ->
            if (trend.currentStreakLength >= 3) {
                binding.tvWingoStreakBadge.visibility = View.VISIBLE
                binding.tvWingoStreakBadge.text = "🐉 ${trend.currentStreakLength}x ${trend.currentStreakType}"
            } else {
                binding.tvWingoStreakBadge.visibility = View.GONE
            }
        }

        // WinGo AI Next Bet Prediction Observer
        viewModel.wingoPrediction.observe(this) { pred ->
            val sizeColor = if (pred.recommendedSize == "BIG") {
                ContextCompat.getColor(this, R.color.wingo_big)
            } else {
                ContextCompat.getColor(this, R.color.wingo_small)
            }
            val colorRes = if (pred.recommendedColor.contains("GREEN")) {
                ContextCompat.getColor(this, R.color.wingo_green)
            } else {
                ContextCompat.getColor(this, R.color.wingo_red)
            }

            // Top HUD Bar Prediction Badge
            if (!pred.isActionableBet || pred.primaryBetType == "SKIP") {
                binding.tvWingoTopPrediction.text = "⏸️ PASS ROUND [0u]"
                binding.tvWingoTopPrediction.setTextColor(ContextCompat.getColor(this, R.color.accent_amber))
            } else {
                val prefix = if (pred.safetyTier.contains("STRONG")) "⚡ BET" else "🎯 BET"
                binding.tvWingoTopPrediction.text = "$prefix ${pred.recommendedSize} (${pred.confidencePct}%)"
                binding.tvWingoTopPrediction.setTextColor(sizeColor)
            }

            // Bottom Sheet AI Prediction Card
            val sheet = binding.bottomSheetResearch
            sheet.tvSheetPredTargetPeriod.text = "FOR ROUND: #${pred.targetPeriod}"
            sheet.tvSheetPredLastResult.text = pred.lastResultSummary

            if (!pred.isActionableBet || pred.primaryBetType == "SKIP") {
                sheet.tvSheetPredSize.text = "⏸️ PASS THIS ROUND"
                sheet.tvSheetPredSize.setTextColor(ContextCompat.getColor(this, R.color.accent_amber))
                sheet.tvSheetPredColor.text = "COLOR: --"
                sheet.tvSheetPredColor.setTextColor(ContextCompat.getColor(this, R.color.text_muted))
                sheet.tvSheetPredPayout.text = "0x (WAIT FOR SETUP)"
                sheet.tvSheetPredPayout.setTextColor(ContextCompat.getColor(this, R.color.text_muted))
                sheet.tvSheetPredEV.text = "EDGE: NEUTRAL"
                sheet.tvSheetPredEV.setTextColor(ContextCompat.getColor(this, R.color.text_muted))
                sheet.tvSheetPredSafety.text = "PASS"
                sheet.tvSheetPredSafety.setTextColor(ContextCompat.getColor(this, R.color.accent_amber))
            } else {
                sheet.tvSheetPredSize.text = "🎯 BET: ${pred.recommendedSize}"
                sheet.tvSheetPredSize.setTextColor(sizeColor)

                sheet.tvSheetPredColor.text = "COLOR: ${pred.recommendedColor}"
                sheet.tvSheetPredColor.setTextColor(colorRes)

                sheet.tvSheetPredPayout.text = pred.payoutLabel
                sheet.tvSheetPredPayout.setTextColor(ContextCompat.getColor(this, R.color.wingo_big))

                sheet.tvSheetPredEV.text = "EV: %+.2f / Unit".format(pred.expectedValue)
                sheet.tvSheetPredEV.setTextColor(ContextCompat.getColor(this, R.color.accent_emerald))

                sheet.tvSheetPredSafety.text = "${pred.safetyTier} • ${pred.kellyUnitSize}"
                val safetyColor = when {
                    pred.safetyTier.contains("STRONG") -> ContextCompat.getColor(this, R.color.accent_emerald)
                    pred.safetyTier.contains("CLEAR") -> ContextCompat.getColor(this, R.color.accent_cyan)
                    else -> ContextCompat.getColor(this, R.color.accent_amber)
                }
                sheet.tvSheetPredSafety.setTextColor(safetyColor)
            }

            sheet.tvSheetPredConfidence.text = "${pred.confidencePct}%"
            sheet.progressSheetPredConfidence.progress = pred.confidencePct

            updateStakingNote(pred, selectedBaseUnit)

            sheet.tvSheetPredReason.text = "${pred.reasoning} • Sizing: ${pred.kellyUnitSize}"
            sheet.tvSheetPredPattern.text = "Signal: ${pred.patternName.replace("_", " ")} • ${pred.modelConsensus}"
            if (pred.recommendedNumbers.isNotEmpty()) {
                sheet.tvSheetPredNumbers.visibility = View.VISIBLE
                sheet.tvSheetPredNumbers.text = "Cover Digits: ${pred.recommendedNumbers.joinToString(", ")} (9.0x Payout)"
            } else {
                sheet.tvSheetPredNumbers.visibility = View.GONE
            }
        }


        // Gemini AI Hybrid Status Observer & Config Listener
        viewModel.geminiStatus.observe(this) { status ->
            val geminiSheet = binding.bottomSheetResearch
            geminiSheet.tvGeminiStatus.text = status
            if (status.contains("Connected")) {
                geminiSheet.tvGeminiStatus.setTextColor(ContextCompat.getColor(this, R.color.accent_emerald))
            } else {
                geminiSheet.tvGeminiStatus.setTextColor(ContextCompat.getColor(this, R.color.accent_amber))
            }
        }

        binding.bottomSheetResearch.btnConfigureGeminiKey.setOnClickListener {
            showGeminiApiKeyDialog()
        }

        // Live Prediction Accuracy Audit Observer
        viewModel.wingoAuditStats.observe(this) { stats ->
            val sheet = binding.bottomSheetResearch
            if (stats.totalAudited > 0) {
                sheet.tvSheetAuditSessionCount.text = "${stats.totalAudited} Audited"
                sheet.tvSheetAuditSizeAccuracy.text = "${"%.0f".format(stats.sizeWinPct)}% (${stats.sizeWins}/${stats.totalAudited})"
                sheet.tvSheetAuditColorAccuracy.text = "${"%.0f".format(stats.colorWinPct)}% (${stats.colorWins}/${stats.totalAudited})"
                sheet.tvSheetAuditNumberAccuracy.text = "${"%.0f".format(stats.numberWinPct)}% (${stats.numberWins}/${stats.totalAudited})"

                val sign = if (stats.netUnitsProfit >= 0) "+" else ""
                sheet.tvSheetAuditNetProfit.text = "Net: $sign${"%.2f".format(stats.netUnitsProfit)} Units (ROI: ${"%.1f".format(stats.roiPct)}%)"
                val profitColor = if (stats.netUnitsProfit >= 0) R.color.accent_emerald else R.color.accent_rose
                sheet.tvSheetAuditNetProfit.setTextColor(ContextCompat.getColor(this, profitColor))

                sheet.tvSheetAuditPrimaryWinRate.text = "Size Win Rate: ${"%.0f".format(stats.sizeWinPct)}% (${stats.sizeWins}/${stats.totalAudited}) • ${stats.skipsCount} Skips"
            } else {
                sheet.tvSheetAuditSessionCount.text = "0 Audited"
                sheet.tvSheetAuditSizeAccuracy.text = "--%"
                sheet.tvSheetAuditColorAccuracy.text = "--%"
                sheet.tvSheetAuditNumberAccuracy.text = "--%"
                sheet.tvSheetAuditNetProfit.text = "Net: +0.00 Units (ROI: 0.0%)"
                sheet.tvSheetAuditPrimaryWinRate.text = "Size Win Rate: --%"
            }
        }

        // WinGo History Draws Table Observer
        viewModel.wingoHistory.observe(this) { history ->
            renderWingoHistoryTable(history)
        }
    }



    private fun triggerImmediateVibration() {
        try {
            val timings = longArrayOf(0L, 180L, 80L, 180L)
            val amplitudes = intArrayOf(0, 255, 0, 255)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                val vibratorManager = getSystemService(android.content.Context.VIBRATOR_MANAGER_SERVICE) as? android.os.VibratorManager
                vibratorManager?.defaultVibrator?.vibrate(android.os.VibrationEffect.createWaveform(timings, amplitudes, -1))
            } else {
                @Suppress("DEPRECATION")
                val vibrator = getSystemService(android.content.Context.VIBRATOR_SERVICE) as? android.os.Vibrator
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    vibrator?.vibrate(android.os.VibrationEffect.createWaveform(timings, amplitudes, -1))
                } else {
                    @Suppress("DEPRECATION")
                    vibrator?.vibrate(timings, -1)
                }
            }
        } catch (e: Exception) {}
    }

    private fun setupListeners() {
        val sheet = binding.bottomSheetResearch

        binding.btnModeAviator.setOnClickListener {
            viewModel.setAppMode(ResearchViewModel.AppMode.AVIATOR)
        }

        binding.btnModeWingo.setOnClickListener {
            viewModel.setAppMode(ResearchViewModel.AppMode.WINGO)
        }

        binding.layoutTopPrediction.setOnClickListener {
            bottomSheetBehavior.state = BottomSheetBehavior.STATE_EXPANDED
            sheet.btnSheetTabWingo.performClick()
        }

        sheet.btnSheetTabWingo.setOnClickListener {
            sheet.layoutSheetWingo.visibility = View.VISIBLE
            sheet.layoutSheetAviator.visibility = View.GONE
            sheet.btnSheetTabWingo.setBackgroundResource(R.drawable.bg_toggle_selected)
            sheet.btnSheetTabWingo.setTextColor(Color.WHITE)
            sheet.btnSheetTabAviator.setBackgroundResource(0)
            sheet.btnSheetTabAviator.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
        }

        sheet.btnSheetTabAviator.setOnClickListener {
            sheet.layoutSheetAviator.visibility = View.VISIBLE
            sheet.layoutSheetWingo.visibility = View.GONE
            sheet.btnSheetTabAviator.setBackgroundResource(R.drawable.bg_toggle_selected)
            sheet.btnSheetTabAviator.setTextColor(Color.WHITE)
            sheet.btnSheetTabWingo.setBackgroundResource(0)
            sheet.btnSheetTabWingo.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
        }

        // Full Re-sync & Engine Restart Button (Purge Cache & Fetch Fresh Data)
        sheet.btnSheetRefreshAll.setOnClickListener {
            val rotate = android.view.animation.RotateAnimation(
                0f, 360f,
                android.view.animation.Animation.RELATIVE_TO_SELF, 0.5f,
                android.view.animation.Animation.RELATIVE_TO_SELF, 0.5f
            ).apply {
                duration = 600
                interpolator = android.view.animation.AccelerateDecelerateInterpolator()
            }
            sheet.btnSheetRefreshAll.startAnimation(rotate)

            Toast.makeText(this, "🔄 Purging cache & restarting engine...", Toast.LENGTH_SHORT).show()
            viewModel.resetAndResyncAll()
        }

        // WinGo Room Selection Chips (Top HUD & Bottom Sheet)
        val roomChips = listOf(
            Pair(binding.chipWingo30s, WingoProtocolEngine.WingoRoom.WINGO_30S),
            Pair(binding.chipWingo1m, WingoProtocolEngine.WingoRoom.WINGO_1M),
            Pair(binding.chipWingo3m, WingoProtocolEngine.WingoRoom.WINGO_3M),
            Pair(binding.chipWingo5m, WingoProtocolEngine.WingoRoom.WINGO_5M),
            Pair(sheet.sheetChipWingo30s, WingoProtocolEngine.WingoRoom.WINGO_30S),
            Pair(sheet.sheetChipWingo1m, WingoProtocolEngine.WingoRoom.WINGO_1M),
            Pair(sheet.sheetChipWingo3m, WingoProtocolEngine.WingoRoom.WINGO_3M),
            Pair(sheet.sheetChipWingo5m, WingoProtocolEngine.WingoRoom.WINGO_5M)
        )
        for ((chip, room) in roomChips) {
            chip.setOnClickListener {
                viewModel.setWingoRoom(room)
            }
        }

        val exportAction = {
            exportCsvAndShare("WINGO_AUDIT")
        }

        sheet.btnSheetExportData.setOnClickListener { exportAction() }
        sheet.btnSheetCardExport.setOnClickListener { exportAction() }

        // Stake Unit Quick Selection Buttons
        fun updateStakeButtons(unit: Int) {
            selectedBaseUnit = unit
            val unsel = R.drawable.bg_toggle_unselected
            val sel = R.drawable.bg_toggle_selected
            val unselColor = ContextCompat.getColor(this, R.color.text_secondary)
            sheet.btnStake10.setBackgroundResource(if (unit == 10) sel else unsel)
            sheet.btnStake10.setTextColor(if (unit == 10) Color.WHITE else unselColor)
            sheet.btnStake50.setBackgroundResource(if (unit == 50) sel else unsel)
            sheet.btnStake50.setTextColor(if (unit == 50) Color.WHITE else unselColor)
            sheet.btnStake100.setBackgroundResource(if (unit == 100) sel else unsel)
            sheet.btnStake100.setTextColor(if (unit == 100) Color.WHITE else unselColor)
            sheet.btnStake500.setBackgroundResource(if (unit == 500) sel else unsel)
            sheet.btnStake500.setTextColor(if (unit == 500) Color.WHITE else unselColor)

            viewModel.wingoPrediction.value?.let { updateStakingNote(it, selectedBaseUnit) }
        }

        sheet.btnStake10.setOnClickListener { updateStakeButtons(10) }
        sheet.btnStake50.setOnClickListener { updateStakeButtons(50) }
        sheet.btnStake100.setOnClickListener { updateStakeButtons(100) }
        sheet.btnStake500.setOnClickListener { updateStakeButtons(500) }

        binding.bannerPreCrashAlert.setOnClickListener {
            binding.bannerPreCrashAlert.visibility = View.GONE
        }

        binding.btnToggleBubble.setOnClickListener {
            toggleBubbleOverlayMode()
        }

        binding.floatingBubbleWidget.setOnClickListener {
            toggleBubbleOverlayMode()
        }

        binding.chipCurRound.setOnClickListener {
            val rid = viewModel.currentRoundId.value ?: "--"
            if (rid != "--") {
                val clipboard = getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Round ID", rid))
                Toast.makeText(this, "Copied Round ID: $rid", Toast.LENGTH_SHORT).show()
            }
        }

        binding.chipSurvivalProb.setOnClickListener {
            showProbabilityDetailsDialog()
        }

        binding.chipTargetSelector.setOnClickListener {
            showTargetMultiplierPicker()
        }

        binding.btnOpenDiagnostics.setOnClickListener {
            TrafficInspectorDialog(this, viewModel).show()
        }

        binding.btnCheckUpdate.setOnClickListener {
            checkForAppUpdates(showToastIfCurrent = true)
        }

        sheet.btnSheetBetExposure.setOnClickListener {
            showBetVolumeExposureDialog()
        }

        sheet.btnSheetProvablyFair.setOnClickListener {
            showProvablyFairDialog()
        }

        sheet.btnSheetReload.setOnClickListener {
            binding.webView.reload()
            Toast.makeText(this, "Reloading target page...", Toast.LENGTH_SHORT).show()
        }

        sheet.btnSheetPauseResume.setOnClickListener {
            viewModel.togglePause()
            if (viewModel.isPaused) {
                sheet.btnSheetPauseResume.text = getString(R.string.btn_resume)
            } else {
                sheet.btnSheetPauseResume.text = getString(R.string.btn_pause)
            }
        }

        binding.btnWebhookSync.setOnClickListener {
            showWebhookConfigDialog()
        }

        sheet.btnSheetWebhookConfig.setOnClickListener {
            showWebhookConfigDialog()
        }

        sheet.btnSheetCheckUpdate.setOnClickListener {
            checkForAppUpdates(showToastIfCurrent = true)
        }

        sheet.btnExportRoundsCsv.setOnClickListener {
            exportCsvAndShare("ROUNDS")
        }

        sheet.btnExportEventsCsv.setOnClickListener {
            exportCsvAndShare("EVENTS")
        }

        sheet.btnExportFeaturesCsv.setOnClickListener {
            exportCsvAndShare("FEATURES")
        }

        sheet.btnExportProtocolCsv.setOnClickListener {
            exportCsvAndShare("PROTOCOL")
        }

        sheet.btnExportWingoCsv.setOnClickListener {
            exportCsvAndShare("WINGO_AUDIT")
        }

        sheet.btnExportAllZip.setOnClickListener {
            exportAllZipAndShare()
        }

        sheet.btnClearData.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Clear Research Database")
                .setMessage("Are you sure you want to delete all recorded rounds, events, and extracted features?")
                .setPositiveButton("Clear") { _, _ ->
                    viewModel.clearAllData {
                        runOnUiThread {
                            Toast.makeText(this, "All research data cleared", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    private fun toggleBubbleOverlayMode() {
        isBubbleModeActive = !isBubbleModeActive
        if (isBubbleModeActive) {
            binding.metricsStrip.visibility = View.GONE
            binding.floatingBubbleWidget.visibility = View.VISIBLE
            binding.btnToggleBubble.imageTintList = android.content.res.ColorStateList.valueOf(ContextCompat.getColor(this, R.color.accent_cyan))
            Toast.makeText(this, "Bubble Overlay Mode Enabled", Toast.LENGTH_SHORT).show()
        } else {
            binding.metricsStrip.visibility = View.VISIBLE
            binding.floatingBubbleWidget.visibility = View.GONE
            binding.btnToggleBubble.imageTintList = android.content.res.ColorStateList.valueOf(ContextCompat.getColor(this, R.color.accent_purple))
            Toast.makeText(this, "Restored Full Cockpit HUD", Toast.LENGTH_SHORT).show()
        }
    }

    private fun setupBackNavigation() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (bottomSheetBehavior.state == BottomSheetBehavior.STATE_EXPANDED) {
                    bottomSheetBehavior.state = BottomSheetBehavior.STATE_HIDDEN
                    binding.bottomNav.menu.findItem(R.id.nav_game)?.isChecked = true
                } else if (binding.webView.canGoBack()) {
                    binding.webView.goBack()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
    }

    private fun showExportSuccessDialog(file: java.io.File) {
        val saved = viewModel.zipExportManager.saveToDownloads(file)
        val msg = if (saved) {
            "File successfully saved to phone storage:\nDownloads/AviatorSignalLab/${file.name}"
        } else {
            "File generated: ${file.name}"
        }

        AlertDialog.Builder(this)
            .setTitle("Dataset Exported")
            .setMessage(msg)
            .setPositiveButton("Open File") { _, _ ->
                viewModel.zipExportManager.openExportFile(file)
            }
            .setNeutralButton("Share") { _, _ ->
                viewModel.zipExportManager.shareExportFile(file)
            }
            .setNegativeButton("Done", null)
            .show()
    }

    private fun exportCsvAndShare(type: String) {
        lifecycleScope.launch {
            try {
                Toast.makeText(this@MainActivity, "Generating $type CSV...", Toast.LENGTH_SHORT).show()
                val file = viewModel.exportSingleCsv(type)
                if (type.contains("WINGO", ignoreCase = true)) {
                    val clipboard = getSystemService(android.content.Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
                    val clip = android.content.ClipData.newPlainText("WinGo Audit CSV", file.readText())
                    clipboard?.setPrimaryClip(clip)
                }
                showExportSuccessDialog(file)
            } catch (e: Exception) {
                Toast.makeText(this@MainActivity, "Export failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun exportAllZipAndShare() {
        lifecycleScope.launch {
            try {
                Toast.makeText(this@MainActivity, "Packaging complete dataset into ZIP...", Toast.LENGTH_SHORT).show()
                val zipFile = viewModel.zipExportManager.exportAllAsZip()
                showExportSuccessDialog(zipFile)
            } catch (e: Exception) {
                Toast.makeText(this@MainActivity, "Export failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun checkForAppUpdates(showToastIfCurrent: Boolean) {
        lifecycleScope.launch {
            Toast.makeText(this@MainActivity, "Checking for updates...", Toast.LENGTH_SHORT).show()
            when (val result = updateManager.checkForUpdates()) {
                is UpdateCheckResult.UpdateAvailable -> {
                    showUpdateDialog(result.metadata)
                }
                is UpdateCheckResult.UpToDate -> {
                    if (showToastIfCurrent) {
                        AlertDialog.Builder(this@MainActivity)
                            .setTitle("App is Up to Date")
                            .setMessage("Current Version: v${BuildConfig.VERSION_NAME} (Build ${BuildConfig.VERSION_CODE})\n\nNo newer version was found on update server.")
                            .setPositiveButton("OK", null)
                            .setNeutralButton("Settings") { _, _ ->
                                showUpdateConfigDialog()
                            }
                            .show()
                    }
                }
                is UpdateCheckResult.Error -> {
                    if (showToastIfCurrent) {
                        AlertDialog.Builder(this@MainActivity)
                            .setTitle("Update Check Notice")
                            .setMessage(result.message)
                            .setPositiveButton("OK", null)
                            .setNeutralButton("Settings") { _, _ ->
                                showUpdateConfigDialog()
                            }
                            .show()
                    }
                }
            }
        }
    }

    private fun showUpdateConfigDialog() {
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(R.layout.dialog_update_config)

        val tvInstalled = dialog.findViewById<TextView>(R.id.tvCurrentInstalledVersion)
        val etUrl = dialog.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.etUpdateUrl)
        val etToken = dialog.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.etGitHubToken)
        val btnReset = dialog.findViewById<Button>(R.id.btnResetDefaultUpdate)
        val btnCancel = dialog.findViewById<Button>(R.id.btnCancelUpdateConfig)
        val btnSave = dialog.findViewById<Button>(R.id.btnSaveUpdateConfig)

        tvInstalled.text = "Installed: v${BuildConfig.VERSION_NAME} (Build ${BuildConfig.VERSION_CODE})"
        etUrl.setText(updateManager.getEffectiveUpdateUrl())
        etToken.setText(updateManager.getGitHubToken() ?: "")

        btnReset.setOnClickListener {
            updateManager.setCustomUpdateUrl(null)
            updateManager.setGitHubToken(null)
            etUrl.setText(updateManager.getEffectiveUpdateUrl())
            etToken.setText("")
            Toast.makeText(this, "Reset to default GitHub Releases URL", Toast.LENGTH_SHORT).show()
        }

        btnCancel.setOnClickListener { dialog.dismiss() }

        btnSave.setOnClickListener {
            val url = etUrl.text?.toString()?.trim()
            val token = etToken.text?.toString()?.trim()
            updateManager.setCustomUpdateUrl(if (!url.isNullOrBlank()) url else null)
            updateManager.setGitHubToken(if (!token.isNullOrBlank()) token else null)
            Toast.makeText(this, "Update settings saved", Toast.LENGTH_SHORT).show()
            dialog.dismiss()
            checkForAppUpdates(showToastIfCurrent = true)
        }

        dialog.show()
    }

    private fun showUpdateDialog(metadata: ReleaseMetadata) {
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(R.layout.dialog_update)

        val tvComparison = dialog.findViewById<TextView>(R.id.tvVersionComparison)
        val tvNotes = dialog.findViewById<TextView>(R.id.tvReleaseNotes)
        val pbDownload = dialog.findViewById<ProgressBar>(R.id.pbDownloadProgress)
        val tvStatus = dialog.findViewById<TextView>(R.id.tvDownloadStatus)
        val btnNow = dialog.findViewById<Button>(R.id.btnUpdateNow)
        val btnLater = dialog.findViewById<Button>(R.id.btnUpdateLater)

        tvComparison.text = "Current: v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) → New: v${metadata.versionName} (${metadata.versionCode})"
        tvNotes.text = metadata.releaseNotes ?: "Bug fixes and enhancements."

        btnLater.setOnClickListener { dialog.dismiss() }

        btnNow.setOnClickListener {
            btnNow.isEnabled = false
            btnLater.isEnabled = false
            pbDownload.visibility = View.VISIBLE
            tvStatus.visibility = View.VISIBLE

            lifecycleScope.launch {
                val result = updateManager.downloadAndInstallApk(metadata) { progress ->
                    runOnUiThread {
                        pbDownload.progress = progress
                        tvStatus.text = "Downloading update: $progress%"
                    }
                }

                result.onSuccess { apkFile ->
                    dialog.dismiss()
                    ApkInstaller.installApk(this@MainActivity, apkFile)
                }.onFailure { err ->
                    btnNow.isEnabled = true
                    btnLater.isEnabled = true
                    tvStatus.text = "Download failed: ${err.message}"
                }
            }
        }

        dialog.show()
    }

    private fun showWebhookConfigDialog() {
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(R.layout.dialog_webhook_config)

        val switchSync = dialog.findViewById<com.google.android.material.switchmaterial.SwitchMaterial>(R.id.switchEnableWebhook)
        val etUrl = dialog.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.etWebhookUrl)
        val btnSave = dialog.findViewById<Button>(R.id.btnSaveWebhook)
        val btnCancel = dialog.findViewById<Button>(R.id.btnCancelWebhook)

        switchSync.isChecked = viewModel.webhookSyncManager.isSyncEnabled
        etUrl.setText(viewModel.webhookSyncManager.webhookUrl)

        btnCancel.setOnClickListener { dialog.dismiss() }

        btnSave.setOnClickListener {
            val url = etUrl.text?.toString()?.trim() ?: ""
            viewModel.webhookSyncManager.webhookUrl = url
            viewModel.webhookSyncManager.isSyncEnabled = switchSync.isChecked

            val msg = if (switchSync.isChecked) "Auto Webhook Stream enabled" else "Webhook Stream disabled"
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
            dialog.dismiss()
        }

        dialog.show()
    }

    // WebViewStatusListener Callbacks
    override fun onPageLoadStarted(url: String) {
        viewModel.setConnectionStatus("CONNECTING")
        binding.webProgressBar.visibility = View.VISIBLE
    }

    override fun onPageLoadFinished(url: String) {
        viewModel.setConnectionStatus("CONNECTED")
        binding.webProgressBar.visibility = View.GONE
    }

    override fun onRendererCrashed() {
        viewModel.onDiagnosticsReceived("CRITICAL: WebView renderer crashed. Rebuilding WebView...")
        binding.webViewContainer.removeAllViews()
        val newWebView = WebView(this)
        binding.webViewContainer.addView(newWebView)
        setupWebView()
        binding.webView.loadUrl(BuildConfig.TARGET_URL)
    }

    override fun onWebError(description: String) {
        viewModel.onDiagnosticsReceived("WebView Error: $description")
    }

    // WebChromeStatusListener Callbacks
    override fun onProgressChanged(progress: Int) {
        binding.webProgressBar.progress = progress
        if (progress >= 100) {
            binding.webProgressBar.visibility = View.GONE
        }
    }

    override fun onConsoleLog(level: String, message: String, sourceId: String, lineNumber: Int) {
        viewModel.onDiagnosticsReceived("[$level] $sourceId:$lineNumber - $message")
    }

    private fun showTargetMultiplierPicker() {
        val targets = arrayOf("1.50x", "1.80x", "2.00x (Standard)", "2.50x", "3.00x", "5.00x", "10.00x", "20.00x (Moonshot)")
        val values = doubleArrayOf(1.50, 1.80, 2.00, 2.50, 3.00, 5.00, 10.00, 20.00)

        AlertDialog.Builder(this)
            .setTitle("🎯 Target Cashout Multiplier")
            .setItems(targets) { _, which ->
                val selected = values[which]
                viewModel.setTargetMultiplier(selected)
                Toast.makeText(this, "Target set to ${"%.2f".format(selected)}x", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showProbabilityDetailsDialog() {
        val est = viewModel.survivalEstimate.value
        val zones = viewModel.zoneProbabilities.value
        val stats = viewModel.empiricalStats.value
        val currentMult = viewModel.currentMultiplier.value ?: 1.00

        val sb = StringBuilder()
        sb.append("✈️ FLIGHT TELEMETRY\n")
        sb.append("Current Multiplier: ${"%.2f".format(currentMult)}x\n")
        if (est != null) {
            sb.append("Next Milestone: ${"%.2f".format(est.nextMilestone)}x (Survival: ${est.milestoneSurvivalPct}%)\n")
            sb.append("Target Multiplier: ${"%.2f".format(est.targetMultiplier)}x (Hit Probability: ${est.targetHitPct}%)\n")
            sb.append("Current Risk Tier: ${est.riskLevel.name}\n\n")
        }

        if (zones != null) {
            sb.append("🎯 4-ZONE RESIDUAL PROBABILITY\n")
            sb.append("🛡️ Safe Zone [1.00x - 2.00x]: ${zones.safePct}%\n")
            sb.append("⚡ Boost Zone [2.01x - 5.00x]: ${zones.boostPct}%\n")
            sb.append("🔥 Rocket Zone [5.01x - 20.00x]: ${zones.rocketPct}%\n")
            sb.append("👑 Moonshot Zone [20.01x+]: ${zones.moonshotPct}%\n\n")
        }

        if (stats != null) {
            sb.append("📊 CAPTURED ROUNDS EMPIRICAL STATS\n")
            sb.append("Total Historical Rounds: ${stats.sampleSize}\n")
            sb.append("Median Crash Multiplier: ${stats.medianMultiplier}x\n")
            sb.append("Average Multiplier: ${stats.averageMultiplier}x\n")
            sb.append("Historical Safe Zone: ${stats.safeZonePct}%\n")
            sb.append("Historical Boost Zone: ${stats.boostZonePct}%\n")
            sb.append("Historical Rocket Zone: ${stats.rocketZonePct}%\n")
            sb.append("Historical Moonshot: ${stats.moonshotZonePct}%\n")
        } else {
            sb.append("📊 Historical rounds: Collecting live round data...\n")
        }

        AlertDialog.Builder(this)
            .setTitle("🔬 Live Probability & Zone Matrix")
            .setMessage(sb.toString())
            .setPositiveButton("OK", null)
            .setNeutralButton("Change Target") { _, _ ->
                showTargetMultiplierPicker()
            }
            .show()
    }

    private fun showBetVolumeExposureDialog() {
        val telem = viewModel.betTelemetry.value ?: com.example.aviatorsignallab.protocol.ServerReverseEngine.currentTelemetry
        val mult = viewModel.currentMultiplier.value ?: 1.00

        val sb = StringBuilder()
        sb.append("📊 REAL-TIME CASINO EXPOSURE TELEMETRY\n")
        sb.append("Current Round: ${telem.roundId}\n")
        sb.append("Current Multiplier: ${"%.2f".format(mult)}x\n\n")

        sb.append("💰 WAGER VOLUME BREAKDOWN:\n")
        sb.append("• Total Wager Pool: $${"%.2f".format(telem.totalWagerPool)}\n")
        sb.append("• Total Bets In Round: ${telem.totalBetsCount} bets\n")
        sb.append("• Cashed Out to Players: $${"%.2f".format(telem.cashedOutAmount)}\n")
        sb.append("• Money Remaining in Flight: $${"%.2f".format(telem.activeWagerRemaining)}\n\n")

        sb.append("⚠️ CASINO NET MARGIN / PROFIT:\n")
        val plSign = if (telem.casinoNetProfitLoss >= 0) "+$" else "-$"
        sb.append("• Net Operator Margin: $plSign${"%.2f".format(kotlin.math.abs(telem.casinoNetProfitLoss))}\n")
        sb.append("• Active Whales Threatening Pool: ${telem.whaleThreatCount} high-rollers\n\n")

        if (telem.whaleBets.isNotEmpty()) {
            sb.append("🐋 ACTIVE WHALE RADAR (Wagers > $500):\n")
            for (w in telem.whaleBets.take(5)) {
                val state = if (w.isCashedOut) "CASHED @ ${w.cashedMultiplier}x" else "IN FLIGHT (Target ${w.autoCashout}x)"
                sb.append("• ${w.userId}: $${"%.2f".format(w.amount)} -> $state\n")
            }
        } else {
            sb.append("• No whales (> $500) detected in current flight.\n")
        }

        AlertDialog.Builder(this)
            .setTitle("📊 Live Player Bets & Casino Exposure")
            .setMessage(sb.toString())
            .setPositiveButton("OK", null)
            .show()
    }

    private fun showProvablyFairDialog() {
        val view = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(40, 20, 40, 10)
        }

        val etServerSeed = android.widget.EditText(this).apply {
            hint = "Server Seed (64 hex characters)"
            setText("4f9c1b82e307521a084bc5e67923485f2693892019ab763c8ef78201a036495b")
            textSize = 12f
        }
        val etClientSeed = android.widget.EditText(this).apply {
            hint = "Client Seed (3 player seeds combined)"
            setText("0987654321_1234567890_5555555555")
            textSize = 12f
        }
        val etRecordedMul = android.widget.EditText(this).apply {
            hint = "Recorded Crash Multiplier (e.g. 2.45)"
            setText("%.2f".format(viewModel.currentMultiplier.value ?: 2.00))
            textSize = 12f
        }

        view.addView(android.widget.TextView(this).apply { text = "Server Seed (Hex):"; textSize = 11f; setTextColor(ContextCompat.getColor(context, R.color.text_muted)) })
        view.addView(etServerSeed)
        view.addView(android.widget.TextView(this).apply { text = "Combined Client Seed:"; textSize = 11f; setTextColor(ContextCompat.getColor(context, R.color.text_muted)) })
        view.addView(etClientSeed)
        view.addView(android.widget.TextView(this).apply { text = "Recorded Crash Multiplier:"; textSize = 11f; setTextColor(ContextCompat.getColor(context, R.color.text_muted)) })
        view.addView(etRecordedMul)

        AlertDialog.Builder(this)
            .setTitle("🔐 Provably Fair SHA-512 Verifier")
            .setView(view)
            .setPositiveButton("Verify") { _, _ ->
                val sSeed = etServerSeed.text.toString().trim()
                val cSeed = etClientSeed.text.toString().trim()
                val recMul = etRecordedMul.text.toString().toDoubleOrNull() ?: 1.00

                val res = viewModel.verifyProvablyFair(sSeed, cSeed, recMul)
                val statusText = if (res.isValid) "✅ 100% PROVABLY FAIR VERIFIED" else "❌ DISCREPANCY DETECTED"

                val detailMsg = """
Status: $statusText
Calculated Multiplier: ${res.calculatedMultiplier}x
Recorded Multiplier: ${res.recordedMultiplier}x
Instant 1.00x Crash: ${res.instantCrash}
First 13 Hex (52 bits): ${res.first13Hex}
Decimal (h): ${res.decimalValue}
HMAC-SHA512: ${res.hmacSha512Hex.take(24)}...
                """.trimIndent()

                AlertDialog.Builder(this)
                    .setTitle("Cryptographic Audit Result")
                    .setMessage(detailMsg)
                    .setPositiveButton("Done", null)
                    .show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showWingoRadarDialog() {
        val issue = viewModel.wingoIssue.value
        val history = viewModel.wingoHistory.value ?: emptyList()
        val trend = viewModel.wingoTrendSummary.value
        val trans = viewModel.wingoTransitions.value

        val sb = StringBuilder()
        sb.append("🎯 WINGO / BIG-SMALL PROTOCOL TELEMETRY\n")
        if (issue != null) {
            val lockStatus = if (issue.isLocked) "🔒 LOCKED (T-5s)" else "🟢 BETTING OPEN"
            sb.append("Current Period: ${issue.currentPeriod} (${issue.room.displayName})\n")
            sb.append("Server Countdown: ${issue.remainingSeconds}s ($lockStatus)\n\n")
        } else {
            sb.append("Current Period: Waiting for WinGo traffic...\n\n")
        }

        if (trend != null && trend.totalRoundsAnalyzed > 0) {
            sb.append("📊 ROLLING PARITY & STREAK ANALYSIS:\n")
            sb.append("• Total Draws Recorded: ${trend.totalRoundsAnalyzed}\n")
            sb.append("• Big Ratio: ${trend.bigRatioPct}% (${trend.bigCount} draws)\n")
            sb.append("• Small Ratio: ${trend.smallRatioPct}% (${trend.smallCount} draws)\n")
            sb.append("• Current Streak: ${trend.currentStreakLength}x ${trend.currentStreakType}\n")
            sb.append("• Max Big Streak: ${trend.maxBigStreak} | Max Small Streak: ${trend.maxSmallStreak}\n")

            if (trend.isDragonActive) {
                sb.append("⚡ ALERT: 🐉 DRAGON STREAK DETECTED (>= 5x ${trend.currentStreakType})\n")
            }
            sb.append("\n")
        }

        if (trans != null) {
            sb.append("🔄 EMPIRICAL TRANSITION MATRIX:\n")
            sb.append("• After Big -> Next Big: ${trans.afterBigNextBigPct}%\n")
            sb.append("• After Big -> Next Small: ${trans.afterBigNextSmallPct}%\n")
            sb.append("• After Small -> Next Small: ${trans.afterSmallNextSmallPct}%\n")
            sb.append("• After Small -> Next Big: ${trans.afterSmallNextBigPct}%\n\n")
        }

        if (history.isNotEmpty()) {
            sb.append("📜 RECENT DRAWS (LATEST 8):\n")
            for (draw in history.take(8)) {
                sb.append("• #${draw.periodId.takeLast(4)}: ${draw.number} [${draw.size}] (${draw.color})\n")
            }
        } else {
            sb.append("📜 Draw History: Open WinGo / Lottery tab inside WebView to capture live issue streams.\n")
        }

        AlertDialog.Builder(this)
            .setTitle("🎯 WinGo Trend Radar")
            .setMessage(sb.toString())
            .setPositiveButton("OK", null)
            .show()
    }

    private fun renderWingoHistoryTable(history: List<WingoProtocolEngine.WingoDrawResult>) {
        val container = binding.bottomSheetResearch.llSheetWingoHistoryTable
        container.removeAllViews()

        val topItems = history.take(12)
        if (topItems.isEmpty()) {
            val tv = TextView(this).apply {
                text = "Connecting to room draw results..."
                setTextColor(ContextCompat.getColor(context, R.color.text_muted))
                textSize = 12f
                setPadding(0, 16, 0, 16)
            }
            container.addView(tv)
            return
        }

        for (item in topItems) {
            val row = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
                setPadding(0, 8, 0, 8)
                gravity = android.view.Gravity.CENTER_VERTICAL
            }

            val shortPeriod = if (item.periodId.length > 5) item.periodId.takeLast(5) else item.periodId
            val tvPeriod = TextView(this).apply {
                layoutParams = android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1.2f)
                text = shortPeriod
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_primary))
                textSize = 11f
                typeface = android.graphics.Typeface.MONOSPACE
            }

            val ballColor = when (item.color.uppercase()) {
                "RED", "RED_VIOLET" -> ContextCompat.getColor(this@MainActivity, R.color.wingo_red)
                "GREEN", "GREEN_VIOLET" -> ContextCompat.getColor(this@MainActivity, R.color.wingo_green)
                else -> ContextCompat.getColor(this@MainActivity, R.color.wingo_violet)
            }

            val tvBall = TextView(this).apply {
                layoutParams = android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 0.8f)
                text = "  ${item.number}"
                setTextColor(ballColor)
                textSize = 12f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
            }

            val tvSize = TextView(this).apply {
                layoutParams = android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 0.8f)
                text = item.size
                setTextColor(if (item.size == "BIG") ContextCompat.getColor(this@MainActivity, R.color.wingo_big) else ContextCompat.getColor(this@MainActivity, R.color.wingo_small))
                textSize = 11f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
            }

            val tvColor = TextView(this).apply {
                layoutParams = android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 0.8f)
                text = item.color.replace("_", "/")
                setTextColor(ballColor)
                textSize = 10f
            }

            row.addView(tvPeriod)
            row.addView(tvBall)
            row.addView(tvSize)
            row.addView(tvColor)
            container.addView(row)
        }
    }

    private fun updateStakingNote(pred: WingoTrendAnalyzer.BetPrediction, baseUnit: Int) {
        val sheet = binding.bottomSheetResearch
        if (!pred.isActionableBet || pred.primaryBetType == "SKIP") {
            sheet.tvSheetPredStakingNote.text = "⛔ CAPITAL SHIELD: 0 UNITS (SKIP ROUND). No edge detected. Preserve bankroll for high-confidence rounds."
            sheet.tvSheetPredStakingNote.setTextColor(ContextCompat.getColor(this, R.color.accent_amber))
            return
        }

        val note = when (pred.primaryBetType) {
            "SIZE" -> {
                val profit = (baseUnit * 0.90).roundToInt()
                val snipeNum = pred.recommendedNumbers.firstOrNull() ?: 7
                val snipeStake = (baseUnit * 0.20).roundToInt().coerceAtLeast(1)
                val snipeProfit = (snipeStake * 8.0).roundToInt()
                "🎯 MAIN: ₹$baseUnit on ${pred.recommendedSize} (Payout ₹${baseUnit + profit}, Profit +₹$profit) • Cover: ₹$snipeStake on #$snipeNum (9.0x, +₹$snipeProfit)"
            }
            "COLOR" -> {
                val profit = (baseUnit * 0.90).roundToInt()
                "🎨 MAIN: ₹$baseUnit on ${pred.recommendedColor} (1.9x Payout ₹${baseUnit + profit}, Profit +₹$profit) • Trend Follower"
            }
            "NUMBER_SNIPE" -> {
                val subStake = (baseUnit * 0.33).roundToInt().coerceAtLeast(1)
                val totalStake = subStake * pred.recommendedNumbers.size
                val winReturn = subStake * 9
                val netProfit = winReturn - totalStake
                "🔢 SNIPER: ₹$subStake each on [${pred.recommendedNumbers.joinToString(",")}] (Cost ₹$totalStake • Return ₹$winReturn, Net +₹$netProfit)"
            }
            else -> pred.stakingStrategyNote
        }
        sheet.tvSheetPredStakingNote.text = note
        sheet.tvSheetPredStakingNote.setTextColor(ContextCompat.getColor(this, R.color.accent_cyan))
    }

    private fun showGeminiApiKeyDialog() {
        showAiConfigDialog()
    }

    private fun showAiConfigDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_ai_config, null)
        val rbOpenAi = dialogView.findViewById<RadioButton>(R.id.rbProviderOpenAi)
        val rbGemini = dialogView.findViewById<RadioButton>(R.id.rbProviderGemini)
        val tilKey = dialogView.findViewById<TextInputLayout>(R.id.tilAiApiKey)
        val etKey = dialogView.findViewById<TextInputEditText>(R.id.etAiApiKey)
        val tvTestResult = dialogView.findViewById<TextView>(R.id.tvAiTestResult)
        val btnTest = dialogView.findViewById<Button>(R.id.btnAiTestConnection)
        val btnLocal = dialogView.findViewById<Button>(R.id.btnAiUseLocalMode)
        val btnSave = dialogView.findViewById<Button>(R.id.btnAiSave)

        val currentProvider = viewModel.aiProvider.value ?: AiConsensusEngine.AiProvider.OPENAI
        if (currentProvider == AiConsensusEngine.AiProvider.OPENAI) {
            rbOpenAi.isChecked = true
            tilKey.hint = "OpenAI API Key (sk-...)"
        } else {
            rbGemini.isChecked = true
            tilKey.hint = "Gemini API Key (aistudio.google.com)"
        }

        etKey.setText(viewModel.aiApiKey.value ?: "")

        rbOpenAi.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                tilKey.hint = "OpenAI API Key (sk-...)"
                tvTestResult.visibility = View.GONE
            }
        }
        rbGemini.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                tilKey.hint = "Gemini API Key (aistudio.google.com)"
                tvTestResult.visibility = View.GONE
            }
        }

        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .create()

        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        btnTest.setOnClickListener {
            val key = etKey.text?.toString()?.trim() ?: ""
            if (key.isBlank()) {
                tvTestResult.visibility = View.VISIBLE
                tvTestResult.text = "Please enter an API Key first."
                tvTestResult.setTextColor(ContextCompat.getColor(this, R.color.accent_rose))
                return@setOnClickListener
            }
            val provider = if (rbOpenAi.isChecked) AiConsensusEngine.AiProvider.OPENAI else AiConsensusEngine.AiProvider.GEMINI
            tvTestResult.visibility = View.VISIBLE
            tvTestResult.text = "Testing connection to ${provider.displayName}..."
            tvTestResult.setTextColor(ContextCompat.getColor(this, R.color.accent_cyan))

            lifecycleScope.launch(Dispatchers.IO) {
                val (success, msg) = AiConsensusEngine.testConnection(provider, key)
                withContext(Dispatchers.Main) {
                    tvTestResult.text = msg
                    if (success) {
                        tvTestResult.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.accent_emerald))
                    } else {
                        tvTestResult.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.accent_rose))
                    }
                }
            }
        }

        btnLocal.setOnClickListener {
            viewModel.saveAiConfig(AiConsensusEngine.AiProvider.OPENAI, "")
            dialog.dismiss()
        }

        btnSave.setOnClickListener {
            val key = etKey.text?.toString()?.trim() ?: ""
            val provider = if (rbOpenAi.isChecked) AiConsensusEngine.AiProvider.OPENAI else AiConsensusEngine.AiProvider.GEMINI
            viewModel.saveAiConfig(provider, key)
            dialog.dismiss()
        }

        dialog.show()
    }
}
