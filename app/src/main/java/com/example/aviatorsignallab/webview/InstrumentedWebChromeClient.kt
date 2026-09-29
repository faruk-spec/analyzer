package com.example.aviatorsignallab.webview

import android.os.Message
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebView

interface WebChromeStatusListener {
    fun onProgressChanged(progress: Int)
    fun onConsoleLog(level: String, message: String, sourceId: String, lineNumber: Int)
}

class InstrumentedWebChromeClient(
    private val listener: WebChromeStatusListener
) : WebChromeClient() {

    override fun onProgressChanged(view: WebView?, newProgress: Int) {
        super.onProgressChanged(view, newProgress)
        listener.onProgressChanged(newProgress)
    }

    override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
        consoleMessage?.let {
            listener.onConsoleLog(
                level = it.messageLevel().name,
                message = it.message(),
                sourceId = it.sourceId() ?: "",
                lineNumber = it.lineNumber()
            )
        }
        return super.onConsoleMessage(consoleMessage)
    }

    override fun onCreateWindow(
        view: WebView?,
        isDialog: Boolean,
        isUserGesture: Boolean,
        resultMsg: Message?
    ): Boolean {
        // Allows popup redirects to load in the current webview
        val transport = resultMsg?.obj as? WebView.WebViewTransport
        transport?.webView = view
        resultMsg?.sendToTarget()
        return true
    }
}
