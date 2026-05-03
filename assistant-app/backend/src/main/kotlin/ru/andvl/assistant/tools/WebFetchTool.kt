package ru.andvl.assistant.tools

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.reflect.ToolSet
import org.jsoup.Jsoup
import org.springframework.stereotype.Component

@Component
@LLMDescription("Tools for fetching web pages")
class WebFetchTool : ToolSet {

    @Tool
    @LLMDescription(
        "Fetch a URL via HTTP GET and return its content as plain text. " +
            "HTML is stripped to body text. Truncated to 8000 chars.",
    )
    fun web_fetch(
        @LLMDescription("Full URL starting with http:// or https://")
        url: String,
    ): String {
        require(url.startsWith("http://") || url.startsWith("https://")) {
            "url must start with http:// or https://"
        }
        val doc = Jsoup.connect(url).timeout(10_000).get()
        return doc.body().text().take(8000)
    }
}
