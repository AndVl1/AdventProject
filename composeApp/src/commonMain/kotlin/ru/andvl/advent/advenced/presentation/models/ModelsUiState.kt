package ru.andvl.advent.advenced.presentation.models

import ru.andvl.advent.advenced.domain.model.AiModel

sealed interface ModelsUiState {
    data object Loading : ModelsUiState
    data class Success(val models: List<AiModel>) : ModelsUiState
    data class Error(val message: String) : ModelsUiState
}
