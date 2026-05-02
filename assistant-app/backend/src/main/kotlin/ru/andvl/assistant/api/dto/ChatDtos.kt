package ru.andvl.assistant.api.dto

import java.util.UUID

data class ChatRequest(
    val message: String,
    val sessionId: UUID? = null,
    val model: String = "openai/gpt-4o-mini",
    val enabledTools: List<String> = emptyList(),
    val systemPrompt: String? = null,
)

data class ChatResponse(
    val sessionId: UUID,
    val reply: String,
    val toolCalls: List<ToolCallRecord> = emptyList(),
    val model: String,
)

data class ToolCallRecord(
    val name: String,
    val args: Any?,
    val result: String?,
    val ok: Boolean,
)
