package ru.andvl.advent.advenced.domain.usecase

import ru.andvl.advent.advenced.domain.model.AiModel
import ru.andvl.advent.advenced.domain.repository.ModelsRepository

class GetModelByIdUseCase(
    private val repository: ModelsRepository,
) {
    suspend operator fun invoke(modelId: String): AiModel =
        repository.getModelById(modelId)
}
