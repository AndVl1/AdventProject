package ru.andvl.advent.advenced.domain.repository

import ru.andvl.advent.advenced.domain.model.chat.ChatMessage

interface ChatRepository {
    suspend fun sendChat(model: String, messages: List<ChatMessage>): ChatMessage
}
