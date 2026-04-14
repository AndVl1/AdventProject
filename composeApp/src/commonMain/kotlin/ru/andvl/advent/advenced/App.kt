package ru.andvl.advent.advenced

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeContentPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import ru.andvl.advent.advenced.presentation.modeldetails.ModelDetailsScreen
import ru.andvl.advent.advenced.presentation.models.ModelsScreen

@Composable
@Preview
fun App() {
    MaterialTheme {
        Surface(
            modifier = Modifier.fillMaxSize().safeContentPadding(),
            color = MaterialTheme.colorScheme.background,
        ) {
            var selectedModelId by rememberSaveable { mutableStateOf<String?>(null) }
            val currentId = selectedModelId
            if (currentId == null) {
                ModelsScreen(onModelClick = { selectedModelId = it })
            } else {
                ModelDetailsScreen(
                    modelId = currentId,
                    onBack = { selectedModelId = null },
                )
            }
        }
    }
}
