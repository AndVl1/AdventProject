package ru.andvl.gateway.api

import java.security.MessageDigest

/**
 * Binds conversation isolation to a specific api-key.
 *
 * SEC-001: Cross-conversation secret hijack prevention.
 * RedactionMap must be keyed by (apiKeyHash + clientConversationId) to prevent
 * an attacker who knows a victim's X-Conversation-Id header from accessing the
 * victim's RedactionMap and reversing placeholders back to real secrets.
 *
 * The apiKeyHash (first 16 hex chars of SHA-256) is never exposed to the client.
 * The client-facing conversation id (returned in X-Conversation-Id response header)
 * is only the normalized clientId portion, so multi-turn conversations still work.
 */
object ConversationKey {

    private val SAFE_ID_REGEX = Regex("[^A-Za-z0-9_\\-]")

    /**
     * Normalizes a client-supplied conversation id.
     * Strips characters outside [A-Za-z0-9_-] and truncates to 64 chars.
     * Returns null if the result is blank.
     */
    fun normalize(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val cleaned = SAFE_ID_REGEX.replace(raw, "").take(64)
        return cleaned.ifBlank { null }
    }

    /**
     * Computes a short (16 hex chars) SHA-256 hash of the api key.
     * Used as a namespace prefix so two callers with different keys
     * but the same client conversation id get separate RedactionMaps.
     */
    fun apiKeyHash(apiKey: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(apiKey.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }.take(16)
    }

    /**
     * Returns the internal registry key (never returned to client).
     * Format: "<apiKeyHash>:<normalizedClientId>"
     */
    fun registryKey(apiKey: String, clientId: String): String {
        return "${apiKeyHash(apiKey)}:$clientId"
    }
}
