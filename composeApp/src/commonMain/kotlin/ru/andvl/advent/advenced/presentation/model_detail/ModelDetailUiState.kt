package ru.andvl.advent.advenced.presentation.model_detail

import ru.andvl.advent.advenced.domain.model.AiModel

sealed interface ModelDetailUiState {
    data object Loading : ModelDetailUiState
    data class Success(val model: AiModel) : ModelDetailUiState
    data class Error(val message: String) : ModelDetailUiState
}
