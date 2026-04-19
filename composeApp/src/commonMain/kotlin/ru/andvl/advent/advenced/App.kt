package ru.andvl.advent.advenced

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeContentPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import ru.andvl.advent.advenced.presentation.model_detail.ModelDetailScreen
import ru.andvl.advent.advenced.presentation.models.ModelsScreen

@Composable
@Preview
fun App() {
    var currentScreen by remember { mutableStateOf<AppScreen>(AppScreen.Models) }
    var selectedModelId by remember { mutableStateOf<String?>(null) }

    MaterialTheme {
        Surface(
            modifier = Modifier.fillMaxSize().safeContentPadding(),
            color = MaterialTheme.colorScheme.background,
        ) {
            when (val screen = currentScreen) {
                is AppScreen.Models -> ModelsScreen(
                    onModelClick = { modelId ->
                        selectedModelId = modelId
                        currentScreen = AppScreen.Detail(modelId)
                    },
                )
                is AppScreen.Detail -> ModelDetailScreen(
                    modelId = screen.modelId,
                    onBack = {
                        currentScreen = AppScreen.Models
                        selectedModelId = null
                    },
                )
            }
        }
    }
}

private sealed interface AppScreen {
    data object Models : AppScreen
    data class Detail(val modelId: String) : AppScreen
}
