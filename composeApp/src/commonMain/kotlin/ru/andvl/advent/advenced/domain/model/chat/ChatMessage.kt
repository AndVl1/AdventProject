package ru.andvl.advent.advenced.domain.model.chat

data class ChatMessage(
    val role: ChatRole,
    val content: String,
)

enum class ChatRole {
    System,
    User,
    Assistant,
}
