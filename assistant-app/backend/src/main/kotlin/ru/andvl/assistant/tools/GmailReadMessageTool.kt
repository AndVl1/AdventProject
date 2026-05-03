package ru.andvl.assistant.tools

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.reflect.ToolSet
import org.springframework.stereotype.Component
import ru.andvl.assistant.tools.gmail.GmailService

@Component
@LLMDescription("Tool for reading the full body of a specific email by its id")
class GmailReadMessageTool(
    private val gmail: GmailService,
) : ToolSet {

    @Tool
    @LLMDescription("Fetch the full body of a specific email by its Gmail message id (obtained from gmail_read). Returns headers and the plain-text body.")
    fun gmail_read_message(
        @LLMDescription("Gmail message id from gmail_read output")
        id: String,
    ): String {
        val msg = gmail.getMessage(id) ?: return "message not found or Gmail not configured"
        return buildString {
            appendLine("ID: ${msg.id}")
            appendLine("FROM: ${msg.from}")
            appendLine("TO: ${msg.to}")
            appendLine("SUBJECT: ${msg.subject}")
            appendLine("DATE: ${msg.date}")
            appendLine("---")
            append(msg.body)
        }
    }
}
