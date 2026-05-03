package ru.andvl.assistant.tools

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.reflect.ToolSet
import org.springframework.stereotype.Component
import ru.andvl.assistant.tools.gmail.GmailService

@Component
@LLMDescription("Tools for sending emails via the user's Gmail account")
class GmailSendTool(
    private val gmail: GmailService,
) : ToolSet {

    @Tool
    @LLMDescription("Send an email from the user's Gmail account. Returns 'ok' on success.")
    fun gmail_send(
        @LLMDescription("recipient email address")
        to: String,
        @LLMDescription("email subject")
        subject: String,
        @LLMDescription("email body in plain text")
        body: String,
    ): String =
        try {
            gmail.send(to, subject, body)
            "ok"
        } catch (e: Exception) {
            "error: ${e.message}"
        }
}
