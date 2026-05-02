package ru.andvl.advent.advenced.data.repository

import ru.andvl.advent.advenced.data.remote.openrouter.ChatRequestDto
import ru.andvl.advent.advenced.data.remote.openrouter.OpenRouterChatApi
import ru.andvl.advent.advenced.data.remote.openrouter.toDomain
import ru.andvl.advent.advenced.data.remote.openrouter.toDto
import ru.andvl.advent.advenced.domain.model.chat.ChatMessage
import ru.andvl.advent.advenced.domain.model.chat.ChatRole
import ru.andvl.advent.advenced.domain.repository.ChatRepository

internal class ChatRepositoryImpl(
    private val api: OpenRouterChatApi,
) : ChatRepository {

    override suspend fun sendChat(model: String, messages: List<ChatMessage>): ChatMessage {
        val request = ChatRequestDto(
            model = model,
            messages = messages.map { it.toDto() },
        )
        val response = api.chat(request)
        response.error?.let { err ->
            val code = err.code?.let { " ($it)" } ?: ""
            throw IllegalStateException("OpenRouter ошибка$code: ${err.message ?: "без описания"}")
        }
        val choice = response.choices.firstOrNull()
            ?: throw IllegalStateException("Пустой ответ от модели (choices=[])")
        val messageDto = choice.message
            ?: throw IllegalStateException("Сообщение отсутствует в ответе")
        return messageDto.toDomain().let { msg ->
            // Гарантируем роль Assistant независимо от того, что вернул API
            if (msg.role == ChatRole.Assistant) msg
            else ChatMessage(role = ChatRole.Assistant, content = msg.content)
        }
    }
}
