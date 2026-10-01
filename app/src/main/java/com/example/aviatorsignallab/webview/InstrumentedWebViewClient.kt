package com.example.aviatorsignallab.webview

import android.graphics.Bitmap
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient

interface WebViewStatusListener {
    fun onPageLoadStarted(url: String)
    fun onPageLoadFinished(url: String)
    fun onRendererCrashed()
    fun onWebError(description: String)
}

class InstrumentedWebViewClient(
    private val statusListener: WebViewStatusListener,
    private val deviceFrameIntervalMs: Double = 16.6
) : WebViewClient() {

    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
        super.onPageStarted(view, url, favicon)
        url?.let { statusListener.onPageLoadStarted(it) }
        injectObservabilityScript(view)
    }

    override fun onPageFinished(view: WebView?, url: String?) {
        super.onPageFinished(view, url)
        url?.let { statusListener.onPageLoadFinished(it) }
        injectObservabilityScript(view)
    }

    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
        // Allow legitimate website navigation, authentication, and redirects
        return false
    }

    override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
        super.onReceivedError(view, request, error)
        if (request?.isForMainFrame == true) {
            statusListener.onWebError(error?.description?.toString() ?: "Unknown WebView Error")
        }
    }

    override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
        // Gracefully handle Chromium renderer process terminations without app crash
        statusListener.onRendererCrashed()
        return true // Indicate that host application handled the renderer exit
    }

    private fun injectObservabilityScript(view: WebView?) {
        view?.evaluateJavascript(ScriptInjector.buildInjectionScript(deviceFrameIntervalMs), null)
    }
}
