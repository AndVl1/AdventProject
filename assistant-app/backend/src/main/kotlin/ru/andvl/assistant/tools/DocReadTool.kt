package ru.andvl.assistant.tools

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.reflect.ToolSet
import org.springframework.stereotype.Component
import ru.andvl.assistant.tools.docs.DocStoreService

@Component
@LLMDescription("Tools for reading documents from the corporate document store")
class DocReadTool(
    private val store: DocStoreService,
) : ToolSet {

    @Tool
    @LLMDescription(
        "List names of available documents in the corporate document store. " +
            "Use to discover document names before calling doc_read.",
    )
    fun doc_list(): String {
        val names = store.list()
        return if (names.isEmpty()) "no documents" else names.joinToString("\n")
    }

    @Tool
    @LLMDescription(
        "Read a document from the corporate document store by name " +
            "(use doc_list to discover names). Returns extracted plain text. " +
            "Truncated to 8000 chars.",
    )
    fun doc_read(
        @LLMDescription("Document file name with extension, e.g. q4-report.html")
        name: String,
    ): String {
        val doc = store.read(name) ?: return "document not found: $name"
        val body = doc.text.take(8000)
        return buildString {
            appendLine("NAME: ${doc.name}")
            appendLine("MIME: ${doc.mime}")
            appendLine("---")
            append(body)
        }
    }
}
