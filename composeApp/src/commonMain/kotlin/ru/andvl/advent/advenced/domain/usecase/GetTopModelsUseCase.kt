package ru.andvl.advent.advenced.domain.usecase

import ru.andvl.advent.advenced.domain.model.AiModel
import ru.andvl.advent.advenced.domain.repository.ModelsRepository

class GetTopModelsUseCase(
    private val repository: ModelsRepository,
    private val limit: Int = 20,
) {
    suspend operator fun invoke(): List<AiModel> =
        repository.getModels().take(limit)
}
