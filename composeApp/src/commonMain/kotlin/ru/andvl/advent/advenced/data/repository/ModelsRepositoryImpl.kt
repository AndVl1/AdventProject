package ru.andvl.advent.advenced.data.repository

import ru.andvl.advent.advenced.data.remote.openrouter.OpenRouterApi
import ru.andvl.advent.advenced.data.remote.openrouter.toDomain
import ru.andvl.advent.advenced.domain.model.AiModel
import ru.andvl.advent.advenced.domain.repository.ModelsRepository

internal class ModelsRepositoryImpl(
    private val api: OpenRouterApi,
) : ModelsRepository {
    override suspend fun getModels(): List<AiModel> =
        api.getModels().data.map { it.toDomain() }

    override suspend fun getModelById(modelId: String): AiModel =
        api.getModel(modelId).toDomain()
}
