package ru.andvl.advent.advenced.domain.repository

import ru.andvl.advent.advenced.domain.model.AiModel

interface ModelsRepository {
    suspend fun getModels(): List<AiModel>
    suspend fun getModelById(id: String): AiModel?
}
