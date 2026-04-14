package ru.andvl.advent.advenced.domain.model

data class AiModel(
    val id: String,
    val name: String,
    val contextLength: Long?,
    val description: String?,
    val pricing: ModelPricing?,
    val modality: String?,
    val maxCompletionTokens: Long?,
)

data class ModelPricing(
    val prompt: String?,
    val completion: String?,
    val request: String?,
    val image: String?,
)
