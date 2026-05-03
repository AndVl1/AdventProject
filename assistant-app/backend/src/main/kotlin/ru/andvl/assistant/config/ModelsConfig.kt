package ru.andvl.assistant.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import ru.andvl.assistant.api.dto.ModelInfo

@Configuration
class ModelsConfig {

    @Bean
    fun availableModels(): List<ModelInfo> = listOf(
        ModelInfo(
            id = "openai/gpt-4o-mini",
            label = "GPT-4o Mini",
            provider = "openrouter",
        ),
        ModelInfo(
            id = "anthropic/claude-haiku-4-5",
            label = "Claude Haiku 4.5",
            provider = "openrouter",
        ),
        ModelInfo(
            id = "google/gemini-2.5-flash",
            label = "Gemini 2.5 Flash",
            provider = "openrouter",
        ),
        ModelInfo(
            id = "qwen/qwen3-235b-a22b-2507",
            label = "Qwen3 235B",
            provider = "openrouter",
        ),
    )
}
