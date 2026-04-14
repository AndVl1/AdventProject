package ru.andvl.advent.advenced.data.remote.openrouter

import ru.andvl.advent.advenced.domain.model.AiModel

internal fun OpenRouterModelDto.toDomain(): AiModel = AiModel(
    id = id,
    name = name ?: id,
    contextLength = contextLength,
    description = description,
)
