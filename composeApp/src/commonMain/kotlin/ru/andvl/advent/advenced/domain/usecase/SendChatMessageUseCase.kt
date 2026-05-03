package ru.andvl.advent.advenced.domain.usecase

import ru.andvl.advent.advenced.domain.model.chat.ChatMessage
import ru.andvl.advent.advenced.domain.repository.ChatRepository

class SendChatMessageUseCase(
    private val repository: ChatRepository,
) {
    suspend operator fun invoke(model: String, messages: List<ChatMessage>): ChatMessage =
        repository.sendChat(model, messages)
}
