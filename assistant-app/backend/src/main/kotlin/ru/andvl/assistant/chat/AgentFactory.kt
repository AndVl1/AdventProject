package ru.andvl.assistant.chat

import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.agents.core.tools.reflect.ToolSet
import ai.koog.agents.features.eventHandler.feature.handleEvents
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import org.springframework.stereotype.Component
import ru.andvl.assistant.tools.DocReadTool
import ru.andvl.assistant.tools.GmailReadMessageTool
import ru.andvl.assistant.tools.GmailReadTool
import ru.andvl.assistant.tools.GmailSendTool
import ru.andvl.assistant.tools.WebFetchSanitizedTool
import ru.andvl.assistant.tools.WebFetchTool

@Component
class AgentFactory(
    private val openRouterExecutor: PromptExecutor,
    private val gmailReadTool: GmailReadTool,
    private val gmailReadMessageTool: GmailReadMessageTool,
    private val gmailSendTool: GmailSendTool,
    private val webFetchTool: WebFetchTool,
    private val webFetchSanitizedTool: WebFetchSanitizedTool,
    private val docReadTool: DocReadTool,
) {

    /**
     * Стандартные возможности для динамически создаваемых моделей OpenRouter.
     * Включаем Tools, чтобы агент мог вызывать инструменты.
     */
    private val standardCapabilities = listOf(
        LLMCapability.Temperature,
        LLMCapability.Tools,
        LLMCapability.Completion,
    )

    /**
     * Создаёт AIAgent для одного запроса.
     * Агент пересоздаётся на каждый запрос, т.к. набор инструментов может меняться.
     * Трейсер [tracer] заполняется обработчиком onToolCallCompleted во время выполнения.
     *
     * Реальные имена полей контекста в koog 0.7.3:
     *   ToolCallCompletedContext.toolName, toolArgs, toolResult
     *   ToolCallFailedContext.toolName, toolArgs, message
     */
    fun create(
        modelId: String,
        enabledToolIds: List<String>,
        systemPrompt: String,
        tracer: ToolCallTracer,
    ): AIAgent<String, String> {
        val llmModel = LLModel(
            provider = LLMProvider.OpenRouter,
            id = modelId,
            capabilities = standardCapabilities,
            contextLength = 128_000,
        )

        val toolSets = buildToolSets(enabledToolIds)

        // ToolRegistryBuilder.tools(ToolSet) — встроенный метод, импорт расширения не нужен
        val registry = ToolRegistry {
            toolSets.forEach { ts -> tools(ts) }
        }

        return AIAgent(
            promptExecutor = openRouterExecutor,
            llmModel = llmModel,
            systemPrompt = systemPrompt,
            toolRegistry = registry,
            installFeatures = {
                handleEvents {
                    onToolCallCompleted { ctx ->
                        tracer.record(
                            name = ctx.toolName,
                            args = ctx.toolArgs.toString(),
                            result = ctx.toolResult?.toString(),
                            ok = true,
                        )
                    }
                    onToolCallFailed { ctx ->
                        tracer.record(
                            name = ctx.toolName,
                            args = ctx.toolArgs.toString(),
                            result = ctx.message,
                            ok = false,
                        )
                    }
                }
            },
        )
    }

    private fun buildToolSets(enabledToolIds: List<String>): List<ToolSet> {
        val all = mapOf(
            "gmail_read" to gmailReadTool,
            "gmail_read_message" to gmailReadMessageTool,
            "gmail_send" to gmailSendTool,
            "web_fetch" to webFetchTool,
            "web_fetch_sanitized" to webFetchSanitizedTool,
            "doc_read" to docReadTool,
        )
        return if (enabledToolIds.isEmpty()) {
            all.values.toList()
        } else {
            enabledToolIds.mapNotNull { all[it] }
        }
    }
}
