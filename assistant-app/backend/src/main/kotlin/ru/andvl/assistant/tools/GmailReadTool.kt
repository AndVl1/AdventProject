package ru.andvl.assistant.tools

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.reflect.ToolSet
import org.springframework.stereotype.Component
import ru.andvl.assistant.tools.gmail.GmailService

@Component
@LLMDescription("Tools for reading emails from the user's Gmail inbox")
class GmailReadTool(
    private val gmail: GmailService,
) : ToolSet {

    @Tool
    @LLMDescription("List N most recent emails from the user's Gmail inbox. Returns id, from, subject, snippet for each. Use the id with gmail_read_message to fetch the full body of a specific email.")
    fun gmail_read(
        @LLMDescription("Maximum number of emails to return (default 10, max 25)")
        limit: Int = 10,
    ): String {
        val capped = limit.coerceIn(1, 25)
        return gmail.listRecent(capped)
            .joinToString("\n---\n") { msg ->
                "ID: ${msg.id}\nFROM: ${msg.from}\nSUBJECT: ${msg.subject}\nSNIPPET: ${msg.snippet}"
            }
            .ifEmpty { "no messages" }
    }
}
