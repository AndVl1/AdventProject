package ru.andvl.gateway.persistence

data class AuditLog(
    val id: Long? = null,
    val ts: Long = System.currentTimeMillis(),
    val conversationId: String?,
    val clientIp: String?,
    val model: String?,
    val requestText: String?,
    val redactedText: String?,
    val responseText: String?,
    val status: String,                 // OK | BLOCKED | ERROR | RATE_LIMITED
    val blockReason: String? = null,
    val inputFindings: String? = null,
    val outputFindings: String? = null,
    val latencyMs: Long? = null,
)

data class RedactionEvent(
    val id: Long? = null,
    val ts: Long = System.currentTimeMillis(),
    val conversationId: String?,
    val direction: String,              // INPUT | OUTPUT
    val ruleName: String,
    val placeholder: String,
    val originalHash: String,
)

data class CostRecord(
    val id: Long? = null,
    val ts: Long = System.currentTimeMillis(),
    val conversationId: String?,
    val model: String,
    val promptTokens: Int,
    val completionTokens: Int,
    val totalTokens: Int,
    val costUsd: Double,
)
