package ru.andvl.advent.advenced.presentation.modeldetails

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import ru.andvl.advent.advenced.di.ModelsGraph
import ru.andvl.advent.advenced.domain.model.AiModel
import ru.andvl.advent.advenced.domain.model.ModelPricing

@Composable
fun ModelDetailsScreen(
    modelId: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val viewModel: ModelDetailsViewModel = viewModel(key = "model-details-$modelId") {
        ModelDetailsViewModel(modelId, ModelsGraph.getModelDetails)
    }
    val state by viewModel.state.collectAsStateWithLifecycle()
    ModelDetailsContent(
        state = state,
        onBack = onBack,
        onRetry = viewModel::load,
        modifier = modifier,
    )
}

@Composable
private fun ModelDetailsContent(
    state: ModelDetailsUiState,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(modifier = Modifier.fillMaxWidth().padding(8.dp)) {
                TextButton(onClick = onBack) { Text("← Назад") }
            }
            Box(modifier = Modifier.fillMaxSize()) {
                when (state) {
                    is ModelDetailsUiState.Loading -> CircularProgressIndicator(
                        modifier = Modifier.align(Alignment.Center)
                    )
                    is ModelDetailsUiState.NotFound -> Text(
                        text = "Модель не найдена",
                        modifier = Modifier.align(Alignment.Center).padding(16.dp),
                    )
                    is ModelDetailsUiState.Error -> Column(
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
                    is ModelDetailsUiState.Success -> ModelDetailsBody(state.model)
                }
            }
        }
    }
}

@Composable
private fun ModelDetailsBody(model: AiModel) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(model.name, style = MaterialTheme.typography.headlineSmall)
        Text(
            text = model.id,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        InfoCard(title = "Основное") {
            model.contextLength?.let { Info("Контекст", "$it токенов") }
            model.modality?.let { Info("Модальность", it) }
            model.maxCompletionTokens?.let { Info("Макс. ответ", "$it токенов") }
        }

        model.pricing?.let { PricingCard(it) }

        model.description?.takeIf { it.isNotBlank() }?.let { desc ->
            InfoCard(title = "Описание") {
                Text(desc, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun InfoCard(title: String, content: @Composable () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
private fun PricingCard(pricing: ModelPricing) {
    InfoCard(title = "Цены (USD за токен)") {
        pricing.prompt?.let { Info("Prompt", it) }
        pricing.completion?.let { Info("Completion", it) }
        pricing.request?.let { Info("Request", it) }
        pricing.image?.let { Info("Image", it) }
    }
}

@Composable
private fun Info(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
