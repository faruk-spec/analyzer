package com.example.aviatorsignallab.ui

import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.ViewGroup
import android.view.Window
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.widget.NestedScrollView
import androidx.lifecycle.LifecycleOwner
import com.example.aviatorsignallab.R
import com.example.aviatorsignallab.model.TrafficItem
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText

class TrafficInspectorDialog(
    context: Context,
    private val viewModel: ResearchViewModel
) : Dialog(context) {

    private lateinit var tvTrafficStats: TextView
    private lateinit var etTrafficFilter: TextInputEditText
    private lateinit var tvTrafficLogs: TextView
    private lateinit var scrollTraffic: NestedScrollView

    private lateinit var btnFilterAll: MaterialButton
    private lateinit var btnFilterWebSocket: MaterialButton
    private lateinit var btnFilterConsole: MaterialButton
    private lateinit var btnFilterNetwork: MaterialButton
    private lateinit var btnFilterDOM: MaterialButton

    private lateinit var btnClearTraffic: Button
    private lateinit var btnCloseTraffic: Button
    private lateinit var btnCopyTraffic: Button

    private var currentCategory: String = "ALL"
    private var currentFilterQuery: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        setContentView(R.layout.dialog_traffic_inspector)

        window?.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )

        initViews()
        setupCategoryTabs()
        setupSearchFilter()
        setupActionButtons()

        if (context is LifecycleOwner) {
            viewModel.trafficItems.observe(context as LifecycleOwner) { items ->
                renderLogs(items)
            }
        } else {
            renderLogs(viewModel.trafficItems.value ?: emptyList())
        }
    }

    private fun initViews() {
        tvTrafficStats = findViewById(R.id.tvTrafficStats)
        etTrafficFilter = findViewById(R.id.etTrafficFilter)
        tvTrafficLogs = findViewById(R.id.tvTrafficLogs)
        scrollTraffic = findViewById(R.id.scrollTraffic)

        btnFilterAll = findViewById(R.id.btnFilterAll)
        btnFilterWebSocket = findViewById(R.id.btnFilterWebSocket)
        btnFilterConsole = findViewById(R.id.btnFilterConsole)
        btnFilterNetwork = findViewById(R.id.btnFilterNetwork)
        btnFilterDOM = findViewById(R.id.btnFilterDOM)

        btnClearTraffic = findViewById(R.id.btnClearTraffic)
        btnCloseTraffic = findViewById(R.id.btnCloseTraffic)
        btnCopyTraffic = findViewById(R.id.btnCopyTraffic)
    }

    private fun setupCategoryTabs() {
        val allTabs = listOf(
            btnFilterAll to "ALL",
            btnFilterWebSocket to "WEBSOCKET",
            btnFilterConsole to "CONSOLE",
            btnFilterNetwork to "NETWORK",
            btnFilterDOM to "DOM"
        )

        for ((btn, cat) in allTabs) {
            btn.setOnClickListener {
                currentCategory = cat
                updateTabStyles(allTabs)
                renderLogs(viewModel.trafficItems.value ?: emptyList())
            }
        }
    }

    private fun updateTabStyles(tabs: List<Pair<MaterialButton, String>>) {
        val activeColor = ContextCompat.getColor(context, R.color.accent_cyan)
        val inactiveColor = ContextCompat.getColor(context, R.color.text_secondary)
        val inactiveBorder = ContextCompat.getColor(context, R.color.bg_card_border)

        for ((btn, cat) in tabs) {
            if (cat == currentCategory) {
                btn.setTextColor(activeColor)
                btn.strokeColor = android.content.res.ColorStateList.valueOf(activeColor)
            } else {
                btn.setTextColor(inactiveColor)
                btn.strokeColor = android.content.res.ColorStateList.valueOf(inactiveBorder)
            }
        }
    }

    private fun setupSearchFilter() {
        etTrafficFilter.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                currentFilterQuery = s?.toString()?.trim() ?: ""
                renderLogs(viewModel.trafficItems.value ?: emptyList())
            }
            override fun afterTextChanged(s: Editable?) {}
        })
    }

    private fun setupActionButtons() {
        btnClearTraffic.setOnClickListener {
            viewModel.clearTraffic()
            tvTrafficLogs.text = "Logs cleared. Awaiting new traffic..."
            tvTrafficStats.text = "0 packets"
        }

        btnCloseTraffic.setOnClickListener {
            dismiss()
        }

        btnCopyTraffic.setOnClickListener {
            val textToCopy = viewModel.getTrafficExportText(currentFilterQuery, currentCategory)
            if (textToCopy.isBlank()) {
                Toast.makeText(context, "No traffic matches current filter to copy", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("AviatorTrafficInspector", textToCopy)
            clipboard.setPrimaryClip(clip)

            Toast.makeText(context, "Copied traffic logs to clipboard! Paste into chat.", Toast.LENGTH_LONG).show()
        }
    }

    private fun renderLogs(items: List<TrafficItem>) {
        val filtered = items.filter { item ->
            val matchCategory = when (currentCategory) {
                "ALL" -> true
                "WEBSOCKET" -> item.transport.equals("WEBSOCKET", ignoreCase = true)
                "CONSOLE" -> item.transport.equals("CONSOLE", ignoreCase = true)
                "NETWORK" -> item.transport.equals("FETCH", ignoreCase = true) || item.transport.equals("XHR", ignoreCase = true)
                "DOM" -> item.transport.equals("DOM", ignoreCase = true) || item.transport.equals("CANVAS", ignoreCase = true)
                else -> true
            }

            val matchQuery = if (currentFilterQuery.isEmpty()) {
                true
            } else {
                item.payload.contains(currentFilterQuery, ignoreCase = true) ||
                        item.transport.contains(currentFilterQuery, ignoreCase = true) ||
                        item.direction.contains(currentFilterQuery, ignoreCase = true)
            }

            matchCategory && matchQuery
        }

        tvTrafficStats.text = "${filtered.size} / ${items.size} packets"

        if (filtered.isEmpty()) {
            tvTrafficLogs.text = if (items.isEmpty()) {
                "Listening for live traffic (WebSockets, console, network requests, iframes)...\n\nTips:\n- Navigate or launch Aviator game to capture packets.\n- Captured packets can be copied and pasted to assist engine calibration."
            } else {
                "No packets match category: '$currentCategory' and query: '$currentFilterQuery'"
            }
            return
        }

        val sb = StringBuilder()
        for (item in filtered.takeLast(150)) {
            val time = item.getFormattedTime()
            sb.append("[$time] [${item.transport}] [${item.direction}] (${item.size} B)\n")
            sb.append(item.payload).append("\n\n")
        }

        tvTrafficLogs.text = sb.toString()
        scrollTraffic.post {
            scrollTraffic.fullScroll(android.view.View.FOCUS_DOWN)
        }
    }
}
