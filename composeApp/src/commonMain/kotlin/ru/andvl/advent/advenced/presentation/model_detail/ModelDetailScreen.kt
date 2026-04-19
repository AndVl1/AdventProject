package ru.andvl.advent.advenced.presentation.model_detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import ru.andvl.advent.advenced.di.ModelsGraph
import ru.andvl.advent.advenced.domain.model.AiModel

@Composable
fun ModelDetailScreen(
    modelId: String,
    modifier: Modifier = Modifier,
    onBack: () -> Unit = {},
) {
    val viewModel: ModelDetailViewModel = viewModel {
        ModelDetailViewModel(ModelsGraph.getModelById, modelId)
    }
    val state by viewModel.state.collectAsStateWithLifecycle()
    ModelDetailContent(
        state = state,
        modifier = modifier,
        onBack = onBack,
    )
}

@Composable
private fun ModelDetailContent(
    state: ModelDetailUiState,
    modifier: Modifier = Modifier,
    onBack: () -> Unit,
) {
    Box(modifier = modifier.fillMaxSize()) {
        when (state) {
            is ModelDetailUiState.Loading -> CircularProgressIndicator(
                modifier = Modifier.align(Alignment.Center),
            )
            is ModelDetailUiState.Error -> Text(
                text = "Ошибка: ${state.message}",
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.align(Alignment.Center),
            )
            is ModelDetailUiState.Success -> ModelDetailInfo(
                model = state.model,
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                onBack = onBack,
            )
        }
    }
}

@Composable
private fun ModelDetailInfo(
    model: AiModel,
    modifier: Modifier = Modifier,
    onBack: () -> Unit,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = model.name,
            style = MaterialTheme.typography.headlineSmall,
        )

        Text(
            text = model.id,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )

        model.contextLength?.let { length ->
            DetailSection(title = "Длина контекста") {
                Text(
                    text = "$length токенов",
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        }

        model.description?.let { desc ->
            DetailSection(title = "Описание") {
                Text(
                    text = desc,
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        }
    }
}

@Composable
private fun DetailSection(
    title: String,
    content: @Composable () -> Unit,
) {
    Column {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        Surface(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            tonalElevation = 2.dp,
            shape = MaterialTheme.shapes.medium,
        ) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                content()
            }
        }
    }
}
