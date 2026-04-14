package ru.andvl.advent.advenced.presentation.modeldetails

import ru.andvl.advent.advenced.domain.model.AiModel

sealed interface ModelDetailsUiState {
    data object Loading : ModelDetailsUiState
    data class Success(val model: AiModel) : ModelDetailsUiState
    data object NotFound : ModelDetailsUiState
    data class Error(val message: String) : ModelDetailsUiState
}
