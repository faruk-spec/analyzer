package com.example.aviatorsignallab.ui

import android.app.Dialog
import android.content.Context
import android.os.Bundle
import android.view.Window
import android.widget.Button
import android.widget.TextView
import com.example.aviatorsignallab.R

class DiagnosticsDialog(
    context: Context,
    private val viewModel: ResearchViewModel
) : Dialog(context) {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        setContentView(R.layout.dialog_diagnostics)

        val tvText = findViewById<TextView>(R.id.tvDiagnosticsText)
        val btnClose = findViewById<Button>(R.id.btnCloseDiagnostics)

        btnClose.setOnClickListener { dismiss() }

        val sb = StringBuilder()
        sb.append("=== AVIATOR SIGNAL LAB DIAGNOSTICS ===\n")
        sb.append("Target: damansuperstar1.com\n")
        sb.append("Status: ${viewModel.connectionStatus.value}\n")
        sb.append("Current Round: ${viewModel.currentRoundId.value}\n")
        sb.append("Live Multiplier: ${viewModel.currentMultiplier.value}x\n\n")

        sb.append("--- HOOK TELEMETRY ---\n")
        sb.append("WebSocket Messages : ${viewModel.wsCount}\n")
        sb.append("Fetch Invocations  : ${viewModel.fetchCount}\n")
        sb.append("XHR Invocations    : ${viewModel.xhrCount}\n")
        sb.append("postMessage Frames : ${viewModel.postMsgCount}\n")
        sb.append("DOM Stage Events   : ${viewModel.domCount}\n\n")

        val totalR = viewModel.totalRounds.value ?: 0
        val totalE = viewModel.totalEvents.value ?: 0
        sb.append("--- DATABASE RECORDINGS ---\n")
        sb.append("Total Game Rounds  : $totalR\n")
        sb.append("Total Live Events  : $totalE\n\n")

        if (totalE == 0) {
            sb.append("NOTICE: Game is visible, but no relevant application-level activity has been captured yet.\n\n")
        }

        sb.append("--- RECENT TELEMETRY LOGS ---\n")
        val logs = viewModel.diagnosticsLog.value ?: emptyList()
        if (logs.isEmpty()) {
            sb.append("No errors or bridge messages logged.\n")
        } else {
            for (l in logs.takeLast(25)) {
                sb.append(l).append("\n")
            }
        }

        tvText.text = sb.toString()
    }
}
