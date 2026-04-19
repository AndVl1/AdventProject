package ru.andvl.advent.advenced.presentation.model_detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import ru.andvl.advent.advenced.domain.usecase.GetModelByIdUseCase

class ModelDetailViewModel(
    private val getModelById: GetModelByIdUseCase,
    private val modelId: String,
) : ViewModel() {

    private val _state = MutableStateFlow<ModelDetailUiState>(ModelDetailUiState.Loading)
    val state = _state.asStateFlow()

    init {
        load()
    }

    fun load() {
        _state.value = ModelDetailUiState.Loading
        viewModelScope.launch {
            _state.value = try {
                ModelDetailUiState.Success(getModelById(modelId))
            } catch (t: Throwable) {
                ModelDetailUiState.Error(t.message ?: "Неизвестная ошибка")
            }
        }
    }

}
