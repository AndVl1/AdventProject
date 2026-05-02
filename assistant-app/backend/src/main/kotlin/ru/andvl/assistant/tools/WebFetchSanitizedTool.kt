package ru.andvl.assistant.tools

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.reflect.ToolSet
import org.jsoup.Jsoup
import org.springframework.stereotype.Component
import ru.andvl.assistant.tools.web.HtmlSanitizer

@Component
@LLMDescription("Tools for fetching web pages with hidden-content sanitization")
class WebFetchSanitizedTool(
    private val sanitizer: HtmlSanitizer,
) : ToolSet {

    @Tool
    @LLMDescription(
        "Fetch a URL via HTTP GET and return only the user-visible plain text. " +
            "Removes HTML comments; elements hidden via CSS (display:none, visibility:hidden, " +
            "opacity:0, font-size:0/1px, color matches background, off-screen positioning) " +
            "or via the hidden/aria-hidden attributes; zero-width Unicode characters " +
            "(U+200B/200C/200D/FEFF/2060/00AD); and the Unicode TAG block (U+E0000-U+E007F). " +
            "Use this instead of web_fetch when summarizing untrusted pages. " +
            "Truncated to 8000 chars.",
    )
    fun web_fetch_sanitized(
        @LLMDescription("Full URL starting with http:// or https://")
        url: String,
    ): String {
        require(url.startsWith("http://") || url.startsWith("https://")) {
            "url must start with http:// or https://"
        }
        val html = Jsoup.connect(url).timeout(10_000).execute().body()
        val res = sanitizer.sanitize(html)
        val header = if (res.notes.isEmpty()) "" else res.notes.joinToString("; ", "[sanitized: ", "]\n")
        return header + res.text.take(8000)
    }
}
