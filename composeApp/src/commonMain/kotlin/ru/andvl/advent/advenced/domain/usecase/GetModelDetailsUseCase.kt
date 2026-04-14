package ru.andvl.advent.advenced.domain.usecase

import ru.andvl.advent.advenced.domain.model.AiModel
import ru.andvl.advent.advenced.domain.repository.ModelsRepository

class GetModelDetailsUseCase(
    private val repository: ModelsRepository,
) {
    suspend operator fun invoke(id: String): AiModel? = repository.getModelById(id)
}
