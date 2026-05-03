package ru.andvl.gateway.persistence

data class AuditLog(
    val id: Long? = null,
    val ts: Long = System.currentTimeMillis(),
    val conversationId: String?,
    val clientIp: String?,
    val model: String?,
    // Безопасно для логирования: уже после InputGuard, секреты заменены на REDACTED_N плейсхолдеры.
    // Колонка redacted_text в schema.sql осталась как deprecated и больше не пишется.
    val requestText: String?,
    val responseText: String?,
    val status: String,                 // OK | BLOCKED | ERROR | RATE_LIMITED
    val blockReason: String? = null,
    val inputFindings: String? = null,
    val outputFindings: String? = null,
    val latencyMs: Long? = null,
    // raw JSON, что улетает в upstream (post-guard, c инжектом system-note про REDACTED)
    val upstreamRequestJson: String? = null,
    // raw JSON ответа upstream, с message.content = loggableText (БЕЗ reverse оригиналов)
    val upstreamResponseJson: String? = null,
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
