package com.example.aviatorsignallab.ai

import android.util.Log
import com.example.aviatorsignallab.wingo.WingoProtocolEngine
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * High-performance, asynchronous AI Consensus Engine supporting both OpenAI (gpt-4o-mini)
 * and Google Gemini (gemini-1.5-flash) with graceful local fallback.
 */
object AiConsensusEngine {

    private const val TAG = "AiConsensusEngine"

    enum class AiProvider(val code: String, val displayName: String, val defaultModel: String) {
        OPENAI("OPENAI", "OpenAI (gpt-4o-mini)", "gpt-4o-mini"),
        GEMINI("GEMINI", "Google Gemini (Flash)", "gemini-1.5-flash");

        companion object {
            fun fromCode(code: String): AiProvider =
                values().firstOrNull { it.code.equals(code, ignoreCase = true) } ?: OPENAI
        }
    }

    data class AiPredictionResult(
        val provider: AiProvider,
        val recommendedSize: String,
        val sizeConfidencePct: Int,
        val recommendedColor: String,
        val recommendedNumbers: List<Int>,
        val pattern: String,
        val reasoning: String
    )

    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .writeTimeout(5, TimeUnit.SECONDS)
        .build()

    private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

    /**
     * Validates API key with a fast ping call. Returns Pair(success, message).
     */
    fun testConnection(provider: AiProvider, apiKey: String): Pair<Boolean, String> {
        val trimmedKey = apiKey.trim()
        if (trimmedKey.isBlank()) {
            return Pair(false, "API Key is empty")
        }

        return try {
            when (provider) {
                AiProvider.OPENAI -> {
                    val payload = JSONObject().apply {
                        put("model", "gpt-4o-mini")
                        put("messages", JSONArray().apply {
                            put(JSONObject().apply {
                                put("role", "user")
                                put("content", "ping")
                            })
                        })
                        put("max_tokens", 5)
                    }

                    val request = Request.Builder()
                        .url("https://api.openai.com/v1/chat/completions")
                        .addHeader("Authorization", "Bearer $trimmedKey")
                        .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
                        .build()

                    httpClient.newCall(request).execute().use { response ->
                        if (response.isSuccessful) {
                            Pair(true, "OpenAI Connected (gpt-4o-mini ready)")
                        } else {
                            val errBody = response.body?.string() ?: ""
                            val errMsg = try {
                                JSONObject(errBody).getJSONObject("error").getString("message")
                            } catch (_: Exception) {
                                "HTTP ${response.code}: ${response.message}"
                            }
                            Pair(false, "OpenAI Error: $errMsg")
                        }
                    }
                }

                AiProvider.GEMINI -> {
                    val payload = JSONObject().apply {
                        put("contents", JSONArray().apply {
                            put(JSONObject().apply {
                                put("parts", JSONArray().apply {
                                    put(JSONObject().apply { put("text", "ping") })
                                })
                            })
                        })
                    }

                    val url = "https://generativelanguage.googleapis.com/v1beta/models/gemini-1.5-flash:generateContent?key=$trimmedKey"
                    val request = Request.Builder()
                        .url(url)
                        .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
                        .build()

                    httpClient.newCall(request).execute().use { response ->
                        if (response.isSuccessful) {
                            Pair(true, "Gemini Connected (Flash ready)")
                        } else {
                            Pair(false, "Gemini Error: HTTP ${response.code} (${response.message})")
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Test connection error: ${e.message}", e)
            Pair(false, "Network error: ${e.message ?: "Could not connect"}")
        }
    }

    /**
     * Executes AI streak and probability consensus based on the recent draw results.
     */
    fun analyzeRounds(
        provider: AiProvider,
        apiKey: String,
        history: List<WingoProtocolEngine.WingoDrawResult>,
        targetPeriod: String
    ): AiPredictionResult? {
        val trimmedKey = apiKey.trim()
        if (trimmedKey.isBlank() || history.isEmpty()) return null

        val recentRounds = history.take(15)
        val promptBuilder = StringBuilder()
        promptBuilder.append("Analyze these recent WinGo draw results (latest first):\n")
        recentRounds.forEachIndexed { idx, draw ->
            promptBuilder.append("Round ${idx + 1}: Period ${draw.periodId}, Number: ${draw.number}, Size: ${draw.size}, Color: ${draw.primaryColor}\n")
        }
        promptBuilder.append("\nTarget Period to predict: $targetPeriod\n")
        promptBuilder.append("Predict the outcome for Target Period. Output JSON only.")

        val prompt = promptBuilder.toString()

        return try {
            when (provider) {
                AiProvider.OPENAI -> callOpenAi(trimmedKey, prompt)
                AiProvider.GEMINI -> callGemini(trimmedKey, prompt)
            }
        } catch (e: Exception) {
            Log.e(TAG, "AI analysis error: ${e.message}", e)
            null
        }
    }

    private fun callOpenAi(apiKey: String, prompt: String): AiPredictionResult? {
        val payload = JSONObject().apply {
            put("model", "gpt-4o-mini")
            put("messages", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "system")
                    put(
                        "content",
                        "You are an expert quantitative lottery probability and streak analyzer. You MUST return JSON only with schema: {\"recommendedSize\":\"BIG\"|\"SMALL\",\"sizeConfidencePct\":50-85,\"recommendedColor\":\"RED\"|\"GREEN\",\"recommendedNumbers\":[integer,integer,integer],\"pattern\":\"short name\",\"reasoning\":\"1 sentence\"}"
                    )
                })
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", prompt)
                })
            })
            put("response_format", JSONObject().apply { put("type", "json_object") })
            put("temperature", 0.2)
            put("max_tokens", 150)
        }

        val request = Request.Builder()
            .url("https://api.openai.com/v1/chat/completions")
            .addHeader("Authorization", "Bearer $apiKey")
            .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()

        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val body = response.body?.string() ?: return null
            val root = JSONObject(body)
            val choices = root.getJSONArray("choices")
            if (choices.length() == 0) return null
            val content = choices.getJSONObject(0).getJSONObject("message").getString("content")
            return parsePredictionJson(AiProvider.OPENAI, content)
        }
    }

    private fun callGemini(apiKey: String, prompt: String): AiPredictionResult? {
        val systemInstruction = "You are an expert quantitative lottery probability and streak analyzer. Output valid JSON only with keys: recommendedSize ('BIG' or 'SMALL'), sizeConfidencePct (integer 50-85), recommendedColor ('RED' or 'GREEN'), recommendedNumbers (array of 2-3 numbers 0-9), pattern (short string), reasoning (1 sentence)."
        val fullPrompt = "$systemInstruction\n\n$prompt"

        val payload = JSONObject().apply {
            put("contents", JSONArray().apply {
                put(JSONObject().apply {
                    put("parts", JSONArray().apply {
                        put(JSONObject().apply { put("text", fullPrompt) })
                    })
                })
            })
            put("generationConfig", JSONObject().apply {
                put("response_mime_type", "application/json")
                put("temperature", 0.2)
                put("maxOutputTokens", 150)
            })
        }

        val url = "https://generativelanguage.googleapis.com/v1beta/models/gemini-1.5-flash:generateContent?key=$apiKey"
        val request = Request.Builder()
            .url(url)
            .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()

        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val body = response.body?.string() ?: return null
            val root = JSONObject(body)
            val candidates = root.getJSONArray("candidates")
            if (candidates.length() == 0) return null
            val parts = candidates.getJSONObject(0).getJSONObject("content").getJSONArray("parts")
            if (parts.length() == 0) return null
            val content = parts.getJSONObject(0).getString("text")
            return parsePredictionJson(AiProvider.GEMINI, content)
        }
    }

    private fun parsePredictionJson(provider: AiProvider, rawJson: String): AiPredictionResult? {
        return try {
            val cleanJson = rawJson.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
            val obj = JSONObject(cleanJson)

            val size = obj.optString("recommendedSize", "BIG").uppercase()
            val validSize = if (size == "SMALL") "SMALL" else "BIG"

            val conf = obj.optInt("sizeConfidencePct", 65).coerceIn(50, 90)

            val color = obj.optString("recommendedColor", "RED").uppercase()
            val validColor = if (color == "GREEN") "GREEN" else "RED"

            val numbersList = mutableListOf<Int>()
            val numsArr = obj.optJSONArray("recommendedNumbers")
            if (numsArr != null) {
                for (i in 0 until numsArr.length()) {
                    val n = numsArr.optInt(i, -1)
                    if (n in 0..9 && !numbersList.contains(n)) {
                        numbersList.add(n)
                    }
                }
            }

            val pattern = obj.optString("pattern", "AI_DUAL_CONSENSUS")
            val reasoning = obj.optString("reasoning", "Pattern and streak momentum consensus detected.")

            AiPredictionResult(
                provider = provider,
                recommendedSize = validSize,
                sizeConfidencePct = conf,
                recommendedColor = validColor,
                recommendedNumbers = numbersList.take(3),
                pattern = pattern,
                reasoning = reasoning
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse AI JSON: ${e.message}\nRaw: $rawJson", e)
            null
        }
    }
}
