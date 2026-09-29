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
import com.example.aviatorsignallab.probability.ProbabilityEngine
import com.example.aviatorsignallab.ui.DiagnosticsDialog
import com.example.aviatorsignallab.ui.ResearchViewModel
import com.example.aviatorsignallab.ui.TrafficInspectorDialog
import com.example.aviatorsignallab.wingo.WingoProtocolEngine
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Handle safe system window insets so top controls are never hidden under camera notch or status bar
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val statusBarHeight = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.statusBars()).top
            binding.topAppBar.setPadding(
                binding.topAppBar.paddingLeft,
                statusBarHeight + 12,
                binding.topAppBar.paddingRight,
                binding.topAppBar.paddingBottom
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

            if (status == "STANDBY" || viewModel.currentRoundId.value == "--") {
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
            if (roundId == "--" || viewModel.connectionStatus.value == "STANDBY") {
                binding.tvMetricMultiplier.text = "--"
                binding.tvBubbleMultiplier.text = "--"
            }
        }

        viewModel.currentMultiplier.observe(this) { mult ->
            if (viewModel.connectionStatus.value == "STANDBY" || viewModel.currentRoundId.value == "--") {
                binding.tvMetricMultiplier.text = "--"
                binding.tvBubbleMultiplier.text = "--"
                binding.tvMetricMultiplier.setTextColor(ContextCompat.getColor(this, R.color.text_muted))
            } else {
                val formatted = "%.2fx".format(mult)
                binding.tvMetricMultiplier.text = formatted
                binding.tvBubbleMultiplier.text = formatted

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

        // Real-Time Instant Pre-Crash Signal Alert (Zero-Lag)
        viewModel.preCrashAlert.observe(this) { alert ->
            if (alert != null && alert.active) {
                binding.bannerPreCrashAlert.visibility = View.VISIBLE

                if (alert.confidence == "PREPARE") {
                    // STAGE 1: PREPARE ADVISORY (Amber Glow)
                    binding.bannerPreCrashAlert.setBackgroundResource(R.drawable.bg_prepare_alert)
                    binding.ivAlertIcon.setColorFilter(ContextCompat.getColor(this, R.color.accent_amber))
                    binding.tvAlertTitle.text = "⚡ PREPARE TO CASH OUT (%.2fx)".format(alert.multiplier)
                    binding.tvAlertSubtitle.text = "High Multiplier Zone • Hover Finger Over Cash Out"
                    binding.tvBubbleStatus.text = "⚡READY"
                    binding.tvBubbleStatus.setTextColor(ContextCompat.getColor(this, R.color.accent_amber))
                    binding.tvBubbleMultiplier.setTextColor(ContextCompat.getColor(this, R.color.accent_amber))
                    binding.tvMetricMultiplier.setTextColor(ContextCompat.getColor(this, R.color.accent_amber))

                    triggerPrepareVibration()
                } else {
                    // STAGE 2: FINAL CASH OUT (Crimson / Rose — Priority 1)
                    binding.bannerPreCrashAlert.setBackgroundResource(R.drawable.bg_pre_crash_alert)
                    binding.ivAlertIcon.setColorFilter(ContextCompat.getColor(this, R.color.accent_rose))
                    binding.tvAlertTitle.text = "⚡ FINAL CASH OUT NOW @ %.2fx".format(alert.multiplier)
                    binding.tvAlertSubtitle.text = "WebSocket Crash Intercepted • Tap Cash Out!"
                    binding.tvBubbleStatus.text = "⚡EXIT!"
                    binding.tvBubbleStatus.setTextColor(ContextCompat.getColor(this, R.color.accent_rose))
                    binding.tvBubbleMultiplier.setTextColor(ContextCompat.getColor(this, R.color.accent_rose))
                    binding.tvMetricMultiplier.setTextColor(ContextCompat.getColor(this, R.color.accent_rose))

                    // Urgent double haptic vibration
                    triggerImmediateVibration()
                }
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

                binding.wingoTopHudStrip.visibility = View.VISIBLE
                binding.metricsStrip.visibility = View.GONE

                binding.bottomSheetResearch.btnSheetTabWingo.performClick()
            } else {
                binding.btnModeAviator.setBackgroundResource(R.drawable.bg_toggle_selected)
                binding.btnModeAviator.setTextColor(Color.WHITE)
                binding.btnModeWingo.setBackgroundResource(0)
                binding.btnModeWingo.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))

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
            val sheet = binding.bottomSheetResearch
            if (trend.currentStreakLength >= 3) {
                binding.tvWingoStreakBadge.visibility = View.VISIBLE
                binding.tvWingoStreakBadge.text = "🐉 ${trend.currentStreakLength}x ${trend.currentStreakType}"

                if (trend.isDragonActive) {
                    binding.bannerWingoAlert.visibility = View.VISIBLE
                    binding.tvWingoAlertTitle.text = "🐉 DRAGON DETECTED: ${trend.currentStreakLength}x ${trend.currentStreakType}"
                    binding.tvWingoAlertSubtitle.text = "Extreme parity run on ${viewModel.activeWingoRoom.value?.displayName ?: "WinGo"} • Reversal Expected"
                } else {
                    binding.bannerWingoAlert.visibility = View.GONE
                }
            } else {
                binding.tvWingoStreakBadge.visibility = View.GONE
                binding.bannerWingoAlert.visibility = View.GONE
            }

            val bigPct = kotlin.math.round(trend.bigRatioPct).toInt()
            val smallPct = kotlin.math.round(trend.smallRatioPct).toInt()
            sheet.tvSheetBigPercent.text = "BIG $bigPct%"
            sheet.tvSheetSmallPercent.text = "SMALL $smallPct%"
            sheet.progressSheetBigSmall.progress = bigPct

            val greenCount = trend.colorCounts["GREEN"] ?: trend.colorCounts["green"] ?: 0
            val redCount = trend.colorCounts["RED"] ?: trend.colorCounts["red"] ?: 0
            val violetCount = (trend.colorCounts["VIOLET"] ?: trend.colorCounts["violet"] ?: 0) +
                (trend.colorCounts["RED_VIOLET"] ?: 0) + (trend.colorCounts["GREEN_VIOLET"] ?: 0)

            sheet.tvSheetStatCounts.text = "Big: ${trend.bigCount} | Small: ${trend.smallCount} | Green: $greenCount | Red: $redCount | Violet: $violetCount"

            if (trend.isDragonActive) {
                sheet.tvSheetDragonTitle.text = "🐉 ACTIVE DRAGON: ${trend.currentStreakLength}x ${trend.currentStreakType}"
                sheet.tvSheetDragonDetails.text = "Statistically extreme streak (${trend.currentStreakLength} consecutive rounds). Historical reversion probability is high."
            } else {
                sheet.tvSheetDragonTitle.text = "🐉 DRAGON STREAK RADAR"
                sheet.tvSheetDragonDetails.text = "Current: ${trend.currentStreakLength}x ${trend.currentStreakType}. Threshold for dragon alert is 5 consecutive rounds."
            }
        }

        // WinGo Markov Parity Transition Matrix Observer
        viewModel.wingoTransitions.observe(this) { trans ->
            val sheet = binding.bottomSheetResearch
            sheet.tvSheetTransBB.text = "%.1f%%".format(trans.afterBigNextBigPct)
            sheet.tvSheetTransBS.text = "%.1f%%".format(trans.afterBigNextSmallPct)
            sheet.tvSheetTransSS.text = "%.1f%%".format(trans.afterSmallNextSmallPct)
            sheet.tvSheetTransSB.text = "%.1f%%".format(trans.afterSmallNextBigPct)

            val prediction = when {
                trans.afterBigNextSmallPct > 55.0 -> "Trend: Alternating Small Expected (P: ${trans.afterBigNextSmallPct}%)"
                trans.afterSmallNextBigPct > 55.0 -> "Trend: Alternating Big Expected (P: ${trans.afterSmallNextBigPct}%)"
                else -> "Parity Transition: Balanced Distribution"
            }
            sheet.tvSheetWingoPrediction.text = prediction
        }

        // WinGo History Draws Table Observer
        viewModel.wingoHistory.observe(this) { history ->
            renderWingoHistoryTable(history)
        }
    }

    private fun triggerPrepareVibration() {
        try {
            val timings = longArrayOf(0L, 90L)
            val amplitudes = intArrayOf(0, 160)
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
                    vibrator?.vibrate(90L)
                }
            }
        } catch (e: Exception) {}
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

        binding.bannerWingoAlert.setOnClickListener {
            bottomSheetBehavior.state = BottomSheetBehavior.STATE_EXPANDED
            sheet.btnSheetTabWingo.performClick()
        }

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
                setTextColor(ContextCompat.getColor(context, R.color.text_primary))
                textSize = 11f
                typeface = android.graphics.Typeface.MONOSPACE
            }

            val ballColor = when (item.color.uppercase()) {
                "RED", "RED_VIOLET" -> ContextCompat.getColor(context, R.color.wingo_red)
                "GREEN", "GREEN_VIOLET" -> ContextCompat.getColor(context, R.color.wingo_green)
                else -> ContextCompat.getColor(context, R.color.wingo_violet)
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
                setTextColor(if (item.size == "BIG") ContextCompat.getColor(context, R.color.wingo_big) else ContextCompat.getColor(context, R.color.wingo_small))
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
}
