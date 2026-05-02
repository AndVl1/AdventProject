package ru.andvl.assistant.api

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import ru.andvl.assistant.api.dto.ModelInfo
import ru.andvl.assistant.api.dto.ModelsResponse
import ru.andvl.assistant.api.dto.ToolInfo
import ru.andvl.assistant.api.dto.ToolsResponse

@RestController
@RequestMapping("/api")
class MetaController(
    private val availableModels: List<ModelInfo>,
) {

    @GetMapping("/models")
    fun models(): ModelsResponse = ModelsResponse(models = availableModels)

    @GetMapping("/tools")
    fun tools(): ToolsResponse = ToolsResponse(
        tools = listOf(
            ToolInfo(
                id = "gmail_read",
                label = "Gmail: Read",
                description = "List recent emails (id, from, subject, snippet)",
            ),
            ToolInfo(
                id = "gmail_read_message",
                label = "Gmail: Open Message",
                description = "Fetch full body of a specific email by id",
            ),
            ToolInfo(
                id = "gmail_send",
                label = "Gmail: Send",
                description = "Send an email",
            ),
            ToolInfo(
                id = "web_fetch",
                label = "Web Fetch",
                description = "GET a URL and return text",
            ),
        ),
    )
}
