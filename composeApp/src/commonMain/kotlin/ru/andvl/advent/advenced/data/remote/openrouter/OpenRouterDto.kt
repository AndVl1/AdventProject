package ru.andvl.advent.advenced.data.remote.openrouter

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
internal data class OpenRouterModelsResponse(
    val data: List<OpenRouterModelDto> = emptyList(),
)

@Serializable
internal data class OpenRouterModelDto(
    val id: String,
    val name: String? = null,
    val description: String? = null,
    @SerialName("context_length") val contextLength: Long? = null,
    val pricing: OpenRouterPricingDto? = null,
    val architecture: OpenRouterArchitectureDto? = null,
    @SerialName("top_provider") val topProvider: OpenRouterTopProviderDto? = null,
)

@Serializable
internal data class OpenRouterPricingDto(
    val prompt: String? = null,
    val completion: String? = null,
    val request: String? = null,
    val image: String? = null,
)

@Serializable
internal data class OpenRouterArchitectureDto(
    val modality: String? = null,
)

@Serializable
internal data class OpenRouterTopProviderDto(
    @SerialName("max_completion_tokens") val maxCompletionTokens: Long? = null,
)
