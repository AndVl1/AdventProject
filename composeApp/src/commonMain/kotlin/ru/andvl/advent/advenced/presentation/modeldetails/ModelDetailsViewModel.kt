package ru.andvl.advent.advenced.presentation.modeldetails

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import ru.andvl.advent.advenced.domain.usecase.GetModelDetailsUseCase

class ModelDetailsViewModel(
    private val modelId: String,
    private val getModelDetails: GetModelDetailsUseCase,
) : ViewModel() {

    private val _state = MutableStateFlow<ModelDetailsUiState>(ModelDetailsUiState.Loading)
    val state = _state.asStateFlow()

    init {
        load()
    }

    fun load() {
        _state.value = ModelDetailsUiState.Loading
        viewModelScope.launch {
            _state.value = try {
                val model = getModelDetails(modelId)
                if (model == null) ModelDetailsUiState.NotFound
                else ModelDetailsUiState.Success(model)
            } catch (t: Throwable) {
                ModelDetailsUiState.Error(t.message ?: "Неизвестная ошибка")
            }
        }
    }
}
