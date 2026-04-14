package ru.andvl.advent.advenced.presentation.models

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import ru.andvl.advent.advenced.domain.usecase.GetTopModelsUseCase

class ModelsViewModel(
    private val getTopModels: GetTopModelsUseCase,
) : ViewModel() {

    private val _state = MutableStateFlow<ModelsUiState>(ModelsUiState.Loading)
    val state = _state.asStateFlow()

    init {
        load()
    }

    fun load() {
        _state.value = ModelsUiState.Loading
        viewModelScope.launch {
            _state.value = try {
                ModelsUiState.Success(getTopModels())
            } catch (t: Throwable) {
                ModelsUiState.Error(t.message ?: "Неизвестная ошибка")
            }
        }
    }
}
