package com.example.aviatorsignallab.model

data class TrafficItem(
    val id: Long = System.currentTimeMillis(),
    val timestamp: Long = System.currentTimeMillis(),
    val transport: String, // WEBSOCKET, CONSOLE, FETCH, XHR, IFRAME, DOM
    val direction: String, // INCOMING, OUTGOING, INTERNAL, LOG, WARN, ERROR
    val payload: String,
    val size: Int = payload.length
) {
    fun getFormattedTime(): String {
        val sdf = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US)
        return sdf.format(java.util.Date(timestamp))
    }
}
