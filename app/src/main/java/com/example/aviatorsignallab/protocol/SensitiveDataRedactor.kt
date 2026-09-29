package com.example.aviatorsignallab.protocol

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import java.util.regex.Pattern

object SensitiveDataRedactor {

    private val SENSITIVE_KEY_PATTERNS = listOf(
        Pattern.compile(".*(pass|pwd|secret).*", Pattern.CASE_INSENSITIVE),
        Pattern.compile(".*(token|jwt|bearer|auth|session|cookie|sid).*", Pattern.CASE_INSENSITIVE),
        Pattern.compile(".*(card|pan|cvv|cvc|bank|account_number|wallet_key|private_key).*", Pattern.CASE_INSENSITIVE),
        Pattern.compile(".*(otp|pin|ssn|identity|credential).*", Pattern.CASE_INSENSITIVE)
    )

    private val PRESERVE_GAME_PATTERNS = listOf(
        Pattern.compile(".*(multiplier|crash|round|odd|rate|speed|time|game|status|state|coefficient|fly|plane|x).*", Pattern.CASE_INSENSITIVE)
    )

    private val gson = Gson()

    fun isKeySensitive(key: String): Boolean {
        // If it's explicitly game-related, preserve it
        for (preserve in PRESERVE_GAME_PATTERNS) {
            if (preserve.matcher(key).matches()) {
                return false
            }
        }
        for (pattern in SENSITIVE_KEY_PATTERNS) {
            if (pattern.matcher(key).matches()) {
                return true
            }
        }
        return false
    }

    /**
     * Safely redacts raw payload strings (JSON or plain text) replacing sensitive fields with [REDACTED].
     */
    fun sanitizePayload(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        val trimmed = raw.trim()

        // Check if payload is JSON
        if ((trimmed.startsWith("{") && trimmed.endsWith("}")) ||
            (trimmed.startsWith("[") && trimmed.endsWith("]"))) {
            return try {
                val element = JsonParser.parseString(trimmed)
                val sanitizedElement = sanitizeJsonElement(element)
                gson.toJson(sanitizedElement)
            } catch (e: Exception) {
                sanitizePlainText(trimmed)
            }
        }

        return sanitizePlainText(trimmed)
    }

    private fun sanitizeJsonElement(element: JsonElement): JsonElement {
        return when {
            element.isJsonObject -> {
                val obj = element.asJsonObject
                val sanitizedObj = JsonObject()
                for (entry in obj.entrySet()) {
                    val key = entry.key
                    val value = entry.value
                    if (isKeySensitive(key)) {
                        sanitizedObj.add(key, JsonPrimitive("[REDACTED]"))
                    } else {
                        sanitizedObj.add(key, sanitizeJsonElement(value))
                    }
                }
                sanitizedObj
            }
            element.isJsonArray -> {
                val arr = element.asJsonArray
                val sanitizedArr = JsonArray()
                for (item in arr) {
                    sanitizedArr.add(sanitizeJsonElement(item))
                }
                sanitizedArr
            }
            else -> element
        }
    }

    private fun sanitizePlainText(text: String): String {
        var result = text
        // Redact standard Bearer tokens: Bearer xxx
        result = result.replace(Regex("Bearer\\s+[A-Za-z0-9-_=.]+", RegexOption.IGNORE_CASE), "Bearer [REDACTED]")
        // Redact session cookies
        result = result.replace(Regex("(sessionid|phpsessid|token|auth)=[^;\\s&]+", RegexOption.IGNORE_CASE), "$1=[REDACTED]")
        // Redact potential 16-digit card numbers
        result = result.replace(Regex("\\b\\d{4}[- ]?\\d{4}[- ]?\\d{4}[- ]?\\d{4}\\b"), "[REDACTED_CARD]")
        return result
    }

    /**
     * Flattens JSON object into dot-notation field paths and redacted values.
     */
    fun extractFieldPaths(rawJson: String?): Map<String, String> {
        val paths = mutableMapOf<String, String>()
        if (rawJson.isNullOrBlank()) return paths
        try {
            val element = JsonParser.parseString(rawJson)
            flattenElement("", element, paths)
        } catch (_: Exception) {}
        return paths
    }

    private fun flattenElement(currentPath: String, element: JsonElement, map: MutableMap<String, String>) {
        when {
            element.isJsonObject -> {
                for (entry in element.asJsonObject.entrySet()) {
                    val nextPath = if (currentPath.isEmpty()) entry.key else "$currentPath.${entry.key}"
                    if (isKeySensitive(entry.key)) {
                        map[nextPath] = "[REDACTED]"
                    } else {
                        flattenElement(nextPath, entry.value, map)
                    }
                }
            }
            element.isJsonArray -> {
                val arr = element.asJsonArray
                for (i in 0 until arr.size()) {
                    val nextPath = "$currentPath[$i]"
                    flattenElement(nextPath, arr[i], map)
                }
            }
            element.isJsonPrimitive -> {
                val prim = element.asJsonPrimitive
                if (!map.containsKey(currentPath)) {
                    map[currentPath] = prim.asString
                }
            }
        }
    }
}
