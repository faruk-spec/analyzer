package com.example.aviatorsignallab.webview

import android.webkit.JavascriptInterface

interface NetworkEventListener {
    fun onRawEventReceived(transport: String, direction: String, payload: String?, size: Int)
    fun onDiagnosticsReceived(message: String)
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
}
