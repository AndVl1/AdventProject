package ru.andvl.advent.advenced

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeContentPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview

@Composable
@Preview
fun App() {
    MaterialTheme {
        OpenRouterScreen(
            modifier = Modifier
                .safeContentPadding()
                .fillMaxSize(),
        )
    }
}
