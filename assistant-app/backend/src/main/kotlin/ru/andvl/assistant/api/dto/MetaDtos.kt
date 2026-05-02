package ru.andvl.assistant.api.dto

data class ModelInfo(
    val id: String,
    val label: String,
    val provider: String,
)

data class ModelsResponse(
    val models: List<ModelInfo>,
)

data class ToolInfo(
    val id: String,
    val label: String,
    val description: String,
)

data class ToolsResponse(
    val tools: List<ToolInfo>,
)
