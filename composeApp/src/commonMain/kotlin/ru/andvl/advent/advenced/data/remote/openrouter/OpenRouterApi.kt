package ru.andvl.advent.advenced.data.remote.openrouter

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get

internal class OpenRouterApi(private val client: HttpClient) {
    suspend fun getModels(): OpenRouterModelsResponse =
        client.get("https://openrouter.ai/api/v1/models").body()

    suspend fun getModel(modelId: String): OpenRouterModelDto =
        client.get("https://openrouter.ai/api/v1/models/$modelId").body()
}
