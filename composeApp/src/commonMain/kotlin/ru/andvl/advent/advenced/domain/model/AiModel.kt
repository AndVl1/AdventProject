package ru.andvl.advent.advenced.domain.model

data class AiModel(
    val id: String,
    val name: String,
    val contextLength: Long?,
    val description: String?,
)
