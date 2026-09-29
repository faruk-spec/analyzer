package com.example.aviatorsignallab.sync

import android.content.Context
import android.content.SharedPreferences
import com.example.aviatorsignallab.model.GameRound
import com.example.aviatorsignallab.model.LiveEvent
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

class WebhookSyncManager(private val context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences("webhook_prefs", Context.MODE_PRIVATE)
    private val gson = Gson()
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    var webhookUrl: String
        get() = prefs.getString("webhook_url", "") ?: ""
        set(value) = prefs.edit().putString("webhook_url", value.trim()).apply()

    var isSyncEnabled: Boolean
        get() = prefs.getBoolean("sync_enabled", false)
        set(value) = prefs.edit().putBoolean("sync_enabled", value).apply()

    suspend fun sendRoundTelemetry(round: GameRound, events: List<LiveEvent>): Result<String> = withContext(Dispatchers.IO) {
        val url = webhookUrl
        if (!isSyncEnabled || url.isBlank() || !url.startsWith("http")) {
            return@withContext Result.failure(Exception("Webhook sync disabled or invalid URL"))
        }

        try {
            val payload = mapOf(
                "app" to "AviatorSignalLab",
                "roundId" to round.roundId,
                "startTime" to round.startTime,
                "endTime" to round.endTime,
                "durationMs" to round.durationMs,
                "finalMultiplier" to round.finalMultiplier,
                "crashDetected" to round.crashDetected,
                "totalEvents" to events.size,
                "transports" to round.protocolTypesSeen,
                "events" to events.takeLast(150).map { evt ->
                    mapOf(
                        "t" to evt.timestamp,
                        "relMs" to evt.relativeToCrashMs,
                        "dir" to evt.direction,
                        "transport" to evt.transport,
                        "type" to evt.eventType,
                        "cmd" to evt.command,
                        "mult" to evt.multiplier,
                        "size" to evt.messageSize,
                        "postCrash" to evt.isPostCrash,
                        "preview" to evt.rawPreview
                    )
                }
            )

            val jsonBody = gson.toJson(payload)
            val requestBody = jsonBody.toRequestBody("application/json; charset=utf-8".toMediaType())
            val request = Request.Builder()
                .url(url)
                .post(requestBody)
                .addHeader("User-Agent", "AviatorSignalLab/1.0.2")
                .build()

            client.newCall(request).execute().use { resp ->
                if (resp.isSuccessful) {
                    Result.success("Success: HTTP ${resp.code}")
                } else {
                    Result.failure(Exception("HTTP ${resp.code}: ${resp.message}"))
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
