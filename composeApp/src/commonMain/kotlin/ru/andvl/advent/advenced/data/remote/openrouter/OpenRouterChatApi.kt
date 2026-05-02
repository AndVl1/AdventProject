package ru.andvl.advent.advenced.data.remote.openrouter

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType

internal class OpenRouterChatApi(private val client: HttpClient) {

    suspend fun chat(request: ChatRequestDto): ChatResponseDto {
        return try {
            client.post("https://openrouter.ai/api/v1/chat/completions") {
                contentType(ContentType.Application.Json)
                setBody(request)
            }.body()
        } catch (e: Exception) {
            throw ChatApiException("Ошибка сети: ${e.message}", e)
        }
    }
}

class ChatApiException(message: String, cause: Throwable? = null) : Exception(message, cause)
