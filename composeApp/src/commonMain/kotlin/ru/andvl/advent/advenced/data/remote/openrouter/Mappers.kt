package ru.andvl.advent.advenced.data.remote.openrouter

import ru.andvl.advent.advenced.domain.model.AiModel
import ru.andvl.advent.advenced.domain.model.ModelPricing

internal fun OpenRouterModelDto.toDomain(): AiModel = AiModel(
    id = id,
    name = name ?: id,
    contextLength = contextLength,
    description = description,
    pricing = pricing?.toDomain(),
    modality = architecture?.modality,
    maxCompletionTokens = topProvider?.maxCompletionTokens,
)

internal fun OpenRouterPricingDto.toDomain(): ModelPricing = ModelPricing(
    prompt = prompt,
    completion = completion,
    request = request,
    image = image,
)
