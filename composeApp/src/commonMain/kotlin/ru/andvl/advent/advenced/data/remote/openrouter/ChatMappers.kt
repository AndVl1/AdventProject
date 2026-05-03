package ru.andvl.advent.advenced.data.remote.openrouter

import ru.andvl.advent.advenced.domain.model.chat.ChatMessage
import ru.andvl.advent.advenced.domain.model.chat.ChatRole

internal fun ChatMessage.toDto(): ChatMessageDto = ChatMessageDto(
    role = when (role) {
        ChatRole.System -> "system"
        ChatRole.User -> "user"
        ChatRole.Assistant -> "assistant"
    },
    content = content,
)

internal fun ChatMessageDto.toDomain(): ChatMessage = ChatMessage(
    role = when (role) {
        "system" -> ChatRole.System
        "user" -> ChatRole.User
        "assistant" -> ChatRole.Assistant
        else -> ChatRole.Assistant
    },
    content = content,
)
