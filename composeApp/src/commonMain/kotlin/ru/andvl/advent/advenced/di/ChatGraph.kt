package ru.andvl.advent.advenced.di

import ru.andvl.advent.advenced.data.remote.createHttpClientWithAuth
import ru.andvl.advent.advenced.data.remote.openrouter.OpenRouterChatApi
import ru.andvl.advent.advenced.data.repository.ChatRepositoryImpl
import ru.andvl.advent.advenced.domain.usecase.SendChatMessageUseCase
import ru.andvl.advent.advenced.presentation.chat.ChatViewModel
import ru.andvl.advent.advenced.secrets.OPENROUTER_API_KEY

// Ключ OpenRouter берётся из local.properties (openrouter.api.key=sk-or-v1-...)
// и подставляется при сборке через generateBuildSecrets → BuildSecrets.kt.
// local.properties НЕ коммитится. Без ключа чат вернёт ошибку авторизации.

object ChatGraph {
    private val chatApi by lazy {
        val client = createHttpClientWithAuth(OPENROUTER_API_KEY)
        OpenRouterChatApi(client)
    }

    private val chatRepository by lazy {
        ChatRepositoryImpl(chatApi)
    }

    private val sendChatMessageUseCase by lazy {
        SendChatMessageUseCase(chatRepository)
    }

    val chatViewModel: ChatViewModel
        get() = ChatViewModel(sendChatMessageUseCase)
}
