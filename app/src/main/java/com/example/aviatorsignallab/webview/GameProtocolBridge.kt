package com.example.aviatorsignallab.webview

import android.webkit.JavascriptInterface

interface NetworkEventListener {
    fun onRawEventReceived(transport: String, direction: String, payload: String?, size: Int)
    fun onDiagnosticsReceived(message: String)
    fun onCrashFastPath(multiplierStr: String) {}
}

class GameProtocolBridge(
    private val listener: NetworkEventListener
) {
    @JavascriptInterface
    fun onNetworkEvent(transport: String, direction: String, payload: String?, size: Int) {
        listener.onRawEventReceived(transport, direction, payload, size)
    }

    @JavascriptInterface
    fun onDiagnostics(message: String) {
        listener.onDiagnosticsReceived(message)
    }

    /**
     * Ultra-fast crash detection path. Called from JS the instant a crash pattern
     * is detected via simple string matching — BEFORE the full payload is even
     * JSON.stringify'd. This fires ~10-20ms ahead of the normal pipeline.
     */
    @JavascriptInterface
    fun onCrashFastPath(multiplierStr: String) {
        listener.onCrashFastPath(multiplierStr)
    }
}
