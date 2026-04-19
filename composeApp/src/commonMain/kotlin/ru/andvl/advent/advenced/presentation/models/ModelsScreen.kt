package ru.andvl.advent.advenced.presentation.models

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import ru.andvl.advent.advenced.di.ModelsGraph
import ru.andvl.advent.advenced.domain.model.AiModel

@Composable
fun ModelsScreen(
    modifier: Modifier = Modifier,
    onModelClick: (String) -> Unit = {},
) {
    val viewModel: ModelsViewModel = viewModel {
        ModelsViewModel(ModelsGraph.getTopModels)
    }
    val state by viewModel.state.collectAsStateWithLifecycle()
    ModelsContent(
        state = state,
        onRetry = viewModel::load,
        onModelClick = onModelClick,
        modifier = modifier,
    )
}

@Composable
private fun ModelsContent(
    state: ModelsUiState,
    onRetry: () -> Unit,
    onModelClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxSize()) {
        when (state) {
            is ModelsUiState.Loading -> CircularProgressIndicator(
                modifier = Modifier.align(Alignment.Center)
            )
            is ModelsUiState.Error -> Column(
                modifier = Modifier.align(Alignment.Center).padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = "Ошибка: ${state.message}",
                    color = MaterialTheme.colorScheme.error,
                )
                Button(onClick = onRetry) { Text("Повторить") }
            }
            is ModelsUiState.Success -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(state.models, key = { it.id }) { model ->
                    ModelRow(model, onModelClick)
                }
            }
        }
    }
}

@Composable
private fun ModelRow(model: AiModel, onModelClick: (String) -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        onClick = { onModelClick(model.id) },
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = model.name,
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = model.id,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            model.contextLength?.let {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "Контекст: $it токенов",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}
