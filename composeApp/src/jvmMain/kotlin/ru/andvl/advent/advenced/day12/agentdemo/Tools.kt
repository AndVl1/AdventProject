package ru.andvl.advent.advenced.day12.agentdemo

import ru.andvl.advent.advenced.day12.defense.InputSanitizer

// Видимый лог tool-вызовов. Каждое действие → строка с эмодзи,
// чтобы видео сразу читалось.

data class ToolEvent(val icon: String, val text: String) {
    override fun toString() = "$icon  $text"
}

class ToolExecutor(
    private val pages: Map<String, String>,
    private val emailWhitelist: List<String>? = null,   // null = без whitelist
    private val sanitizeFetched: Boolean = false,        // strip HTML-comments из FETCH
    private val normalizeFetched: Boolean = false,       // strip ZWS из FETCH
) {
    val log: MutableList<ToolEvent> = mutableListOf()
    val emailsSent: MutableList<EmailSent> = mutableListOf()

    data class EmailSent(val to: String, val subject: String, val body: String)

    fun fetch(url: String): String {
        val raw = pages[url]
        if (raw == null) {
            log.add(ToolEvent("📄", "FETCH $url → 404"))
            return "ERROR: 404 not found"
        }
        var content = raw
        val notes = mutableListOf<String>()
        if (sanitizeFetched) {
            val before = content.length
            content = InputSanitizer.stripHtml(content)
            if (content.length != before) notes.add("HTML-comments stripped (${before - content.length} chars)")
        }
        if (normalizeFetched) {
            val before = content.length
            content = InputSanitizer.normalizeUnicode(content)
            if (content.length != before) notes.add("ZWS removed (${before - content.length} chars)")
        }
        val note = if (notes.isEmpty()) "" else "  [defense: ${notes.joinToString(", ")}]"
        log.add(ToolEvent("📄", "FETCH $url → ${content.length} chars$note"))
        return content
    }

    fun sendEmail(to: String, subject: String, body: String): String {
        if (emailWhitelist != null && emailWhitelist.none { allowed -> to.endsWith(allowed) }) {
            log.add(ToolEvent("🚫", "SEND_EMAIL BLOCKED → $to (не в whitelist ${emailWhitelist})"))
            return "ERROR: recipient domain not allowed by policy"
        }
        emailsSent.add(EmailSent(to, subject, body))
        val emoji = if (to == ATTACKER_EMAIL) "💀" else "📧"
        val bodyPreview = body.replace("\n", " ").take(60).let { if (body.length > 60) "$it..." else it }
        log.add(ToolEvent(emoji, "SEND_EMAIL → $to | subject=\"$subject\" | body=\"$bodyPreview\""))
        return "ok"
    }

    fun printLog() {
        log.forEach { println("    $it") }
    }
}
