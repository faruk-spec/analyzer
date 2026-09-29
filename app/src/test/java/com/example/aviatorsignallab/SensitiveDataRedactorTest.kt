package com.example.aviatorsignallab

import com.example.aviatorsignallab.protocol.SensitiveDataRedactor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SensitiveDataRedactorTest {

    @Test
    fun testSensitiveKeysIdentification() {
        assertTrue(SensitiveDataRedactor.isKeySensitive("password"))
        assertTrue(SensitiveDataRedactor.isKeySensitive("authToken"))
        assertTrue(SensitiveDataRedactor.isKeySensitive("bearer_token"))
        assertTrue(SensitiveDataRedactor.isKeySensitive("credit_card"))
        assertTrue(SensitiveDataRedactor.isKeySensitive("session_cookie"))
        assertTrue(SensitiveDataRedactor.isKeySensitive("user_pin"))

        assertFalse(SensitiveDataRedactor.isKeySensitive("multiplier"))
        assertFalse(SensitiveDataRedactor.isKeySensitive("crash_time"))
        assertFalse(SensitiveDataRedactor.isKeySensitive("round_id"))
        assertFalse(SensitiveDataRedactor.isKeySensitive("fly_speed"))
    }

    @Test
    fun testPayloadRedaction() {
        val jsonPayload = """{"token":"abc123secret","multiplier":1.75,"password":"mypassword","round_id":"rnd_99"}"""
        val sanitized = SensitiveDataRedactor.sanitizePayload(jsonPayload)

        assertTrue(sanitized.contains("\"token\":\"[REDACTED]\""))
        assertTrue(sanitized.contains("\"password\":\"[REDACTED]\""))
        assertTrue(sanitized.contains("\"multiplier\":1.75"))
        assertTrue(sanitized.contains("\"round_id\":\"rnd_99\""))
    }

    @Test
    fun testFieldPathExtraction() {
        val jsonPayload = """{"game":{"multiplier":2.10,"auth_token":"secret999"}}"""
        val fields = SensitiveDataRedactor.extractFieldPaths(jsonPayload)

        assertEquals("2.1", fields["game.multiplier"])
        assertEquals("[REDACTED]", fields["game.auth_token"])
    }
}
