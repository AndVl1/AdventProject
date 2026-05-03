package ru.andvl.advent.advenced.data.remote.openrouter

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
internal data class ChatRequestDto(
    val model: String,
    val messages: List<ChatMessageDto>,
)

@Serializable
internal data class ChatMessageDto(
    val role: String,
    val content: String,
)

@Serializable
internal data class ChatResponseDto(
    val choices: List<ChoiceDto> = emptyList(),
    val error: ChatErrorDto? = null,
)

@Serializable
internal data class ChatErrorDto(
    val code: Int? = null,
    val message: String? = null,
)

@Serializable
internal data class ChoiceDto(
    val message: ChatMessageDto? = null,
    @SerialName("finish_reason") val finishReason: String? = null,
)
