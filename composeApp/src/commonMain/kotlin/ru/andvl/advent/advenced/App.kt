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
import ru.andvl.advent.advenced.presentation.chat.ChatScreen
import ru.andvl.advent.advenced.presentation.modeldetails.ModelDetailsScreen
import ru.andvl.advent.advenced.presentation.models.ModelsScreen

private sealed interface Screen {
    data object ModelList : Screen
    data class ModelDetails(val id: String) : Screen
    data object Chat : Screen
}

@Composable
@Preview
fun App() {
    MaterialTheme {
        Surface(
            modifier = Modifier.fillMaxSize().safeContentPadding(),
            color = MaterialTheme.colorScheme.background,
        ) {
            var screen by remember { mutableStateOf<Screen>(Screen.ModelList) }

            when (val current = screen) {
                is Screen.ModelList -> ModelsScreen(
                    onModelClick = { screen = Screen.ModelDetails(it) },
                    onChatClick = { screen = Screen.Chat },
                )
                is Screen.ModelDetails -> ModelDetailsScreen(
                    modelId = current.id,
                    onBack = { screen = Screen.ModelList },
                )
                is Screen.Chat -> ChatScreen(
                    onBack = { screen = Screen.ModelList },
                )
            }
        }
    }
}
