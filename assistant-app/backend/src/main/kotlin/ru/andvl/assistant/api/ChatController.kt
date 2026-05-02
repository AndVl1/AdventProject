package ru.andvl.assistant.api

import kotlinx.coroutines.runBlocking
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import ru.andvl.assistant.api.dto.ChatRequest
import ru.andvl.assistant.api.dto.ChatResponse
import ru.andvl.assistant.chat.AgentFactory
import ru.andvl.assistant.chat.SessionMessage
import ru.andvl.assistant.chat.SessionStore
import ru.andvl.assistant.chat.ToolCallTracer
import java.util.UUID

@RestController
@RequestMapping("/api")
class ChatController(
    private val agentFactory: AgentFactory,
    private val sessionStore: SessionStore,
) {

    @PostMapping("/chat")
    fun chat(@RequestBody request: ChatRequest): ChatResponse = runBlocking {
        val sessionId = request.sessionId ?: UUID.randomUUID()
        val history = sessionStore.getOrCreate(sessionId)

        // Формируем системный промпт с историей диалога
        val systemPrompt = buildSystemPrompt(history)

        val tracer = ToolCallTracer()

        val agent = agentFactory.create(
            modelId = request.model,
            enabledToolIds = request.enabledTools,
            systemPrompt = systemPrompt,
            tracer = tracer,
        )

        val reply = agent.run(request.message)

        // Сохраняем сообщения в сессию
        sessionStore.append(sessionId, SessionMessage(role = "user", content = request.message))
        sessionStore.append(sessionId, SessionMessage(role = "assistant", content = reply))

        ChatResponse(
            sessionId = sessionId,
            reply = reply,
            toolCalls = tracer.records,
            model = request.model,
        )
    }

    private fun buildSystemPrompt(history: List<SessionMessage>): String {
        val base = "You are a helpful assistant. Use provided tools when relevant."
        if (history.isEmpty()) return base

        val historyText = history.takeLast(20).joinToString("\n") { msg ->
            "${msg.role.uppercase()}: ${msg.content}"
        }
        return "$base\n\nConversation history:\n$historyText"
    }
}
