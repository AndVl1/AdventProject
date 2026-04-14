package ru.andvl.advent.advenced

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
private data class OpenRouterModelsResponse(val data: List<OpenRouterModel> = emptyList())

@Serializable
data class OpenRouterModel(
    val id: String,
    val name: String? = null,
    val description: String? = null,
    @SerialName("context_length") val contextLength: Long? = null,
)

private sealed interface ModelsUiState {
    data object Loading : ModelsUiState
    data class Success(val models: List<OpenRouterModel>) : ModelsUiState
    data class Error(val message: String) : ModelsUiState
}

@Composable
fun OpenRouterScreen(modifier: Modifier = Modifier) {
    var state by remember { mutableStateOf<ModelsUiState>(ModelsUiState.Loading) }

    LaunchedEffect(Unit) {
        state = try {
            val client = HttpClient {
                install(ContentNegotiation) {
                    json(Json { ignoreUnknownKeys = true; isLenient = true })
                }
            }
            val response: OpenRouterModelsResponse =
                client.get("https://openrouter.ai/api/v1/models").body()
            client.close()
            ModelsUiState.Success(response.data.take(20))
        } catch (t: Throwable) {
            ModelsUiState.Error(t.message ?: "Unknown error")
        }
    }

    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        when (val s = state) {
            ModelsUiState.Loading -> CircularProgressIndicator()
            is ModelsUiState.Error -> Text(
                text = "Ошибка: ${s.message}",
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(16.dp),
            )
            is ModelsUiState.Success -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(s.models, key = { it.id }) { model ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(
                                text = model.name ?: model.id,
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Text(
                                text = model.id,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            model.contextLength?.let {
                                Text(
                                    text = "Контекст: $it",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
