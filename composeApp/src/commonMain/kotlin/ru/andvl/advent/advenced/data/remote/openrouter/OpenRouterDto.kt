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
)
