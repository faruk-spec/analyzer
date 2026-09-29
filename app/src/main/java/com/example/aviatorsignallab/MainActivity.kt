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
import androidx.activity.OnBackPressedCallback
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.aviatorsignallab.databinding.ActivityMainBinding
import com.example.aviatorsignallab.ui.DiagnosticsDialog
import com.example.aviatorsignallab.ui.ResearchViewModel
import com.example.aviatorsignallab.update.ApkInstaller
import com.example.aviatorsignallab.update.ReleaseMetadata
import com.example.aviatorsignallab.update.UpdateCheckResult
import com.example.aviatorsignallab.update.UpdateManager
import com.example.aviatorsignallab.webview.GameProtocolBridge
import com.example.aviatorsignallab.webview.InstrumentedWebChromeClient
import com.example.aviatorsignallab.webview.InstrumentedWebViewClient
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
        bottomSheetBehavior.state = BottomSheetBehavior.STATE_COLLAPSED

        binding.fabResearchPanel.setOnClickListener {
            if (bottomSheetBehavior.state == BottomSheetBehavior.STATE_EXPANDED) {
                bottomSheetBehavior.state = BottomSheetBehavior.STATE_COLLAPSED
            } else {
                bottomSheetBehavior.state = BottomSheetBehavior.STATE_EXPANDED
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        val webView = binding.webView
        val settings = webView.settings

        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.mediaPlaybackRequiresUserGesture = false
        settings.allowFileAccess = false
        settings.allowContentAccess = true
        settings.useWideViewPort = true
        settings.loadWithOverviewMode = true
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        settings.cacheMode = WebSettings.LOAD_DEFAULT

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
            when (status) {
                "OBSERVING" -> {
                    binding.tvConnectionStatus.setTextColor(ContextCompat.getColor(this, R.color.accent_emerald))
                }
                "CRASH DETECTED" -> {
                    binding.tvConnectionStatus.setTextColor(ContextCompat.getColor(this, R.color.accent_rose))
                }
                "ROUND START" -> {
                    binding.tvConnectionStatus.setTextColor(ContextCompat.getColor(this, R.color.accent_cyan))
                }
                "PAUSED" -> {
                    binding.tvConnectionStatus.setTextColor(ContextCompat.getColor(this, R.color.accent_amber))
                }
                else -> {
                    binding.tvConnectionStatus.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
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
        }

        viewModel.currentMultiplier.observe(this) { mult ->
            binding.tvMetricMultiplier.text = "%.2fx".format(mult)
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
    }

    private fun setupListeners() {
        val sheet = binding.bottomSheetResearch

        binding.btnOpenDiagnostics.setOnClickListener {
            DiagnosticsDialog(this, viewModel).show()
        }

        binding.btnCheckUpdate.setOnClickListener {
            checkForAppUpdates(showToastIfCurrent = true)
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

    private fun setupBackNavigation() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (bottomSheetBehavior.state == BottomSheetBehavior.STATE_EXPANDED) {
                    bottomSheetBehavior.state = BottomSheetBehavior.STATE_COLLAPSED
                } else if (binding.webView.canGoBack()) {
                    binding.webView.goBack()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
    }

    private fun exportCsvAndShare(type: String) {
        lifecycleScope.launch {
            try {
                Toast.makeText(this@MainActivity, "Generating $type CSV...", Toast.LENGTH_SHORT).show()
                val file = viewModel.exportSingleCsv(type)
                viewModel.zipExportManager.shareExportFile(file)
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
                viewModel.zipExportManager.shareExportFile(zipFile)
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
                        Toast.makeText(this@MainActivity, "App is up to date (v${BuildConfig.VERSION_NAME})", Toast.LENGTH_SHORT).show()
                    }
                }
                is UpdateCheckResult.Error -> {
                    if (showToastIfCurrent) {
                        Toast.makeText(this@MainActivity, "Update check note: ${result.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
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
}
