package ru.andvl.gateway.guard

import org.springframework.stereotype.Service

data class OutputResult(
    val finalText: String,                    // что отдаём клиенту (плейсхолдеры развернуты в оригиналы)
    val loggableText: String,                 // безопасно для БД: галлюцинации замаскированы, плейсхолдеры юзера НЕ развернуты
    val rescanFindings: List<Finding>,        // model-hallucinated secrets that we masked
    val systemPromptLeak: Boolean,
    val suspiciousUrls: List<String>,
    val notes: List<String>,
)

@Service
class OutputGuard(private val engine: RedactionEngine) {

    private val urlRegex = Regex("""https?://[^\s)]+""")
    private val suspiciousHostHints = listOf(
        "wallet", "seed", "recover", "private-key", "credentials", "verify-account",
        "secure-login", "update-payment", "kyc-verify",
    )

    private fun detectSystemPromptLeak(systemPrompt: String?, output: String): Boolean {
        if (systemPrompt.isNullOrBlank()) return false
        val sp = systemPrompt.replace(Regex("\\s+"), " ").trim()
        if (sp.length < 25) return false
        val outNorm = output.replace(Regex("\\s+"), " ")
        val window = 25
        var i = 0
        while (i <= sp.length - window) {
            val piece = sp.substring(i, i + window)
            if (outNorm.contains(piece, ignoreCase = true)) return true
            i++
        }
        return false
    }

    fun process(rawOutput: String, map: RedactionMap, originalSystemPrompt: String?): OutputResult {
        val notes = mutableListOf<String>()

        // 1) re-scan RAW output for hallucinated secrets BEFORE reversing the map.
        //    Otherwise reversed user secrets get masked again, defeating the round-trip.
        val rescan = engine.apply(rawOutput, null)
        if (rescan.findings.isNotEmpty()) {
            notes += "masked ${rescan.findings.size} model-generated secret-shaped value(s)"
        }

        // 2) reverse REDACTED_N -> original on the rescan-cleaned text, so user sees
        //    their own values back. (Hallucinated keys are now opaque REDACTED_TYPE
        //    placeholders not present in the conversation map, so they survive.)
        val reversed = map.reverse(rescan.text)
        if (reversed != rescan.text) notes += "reversed placeholder(s) in output"
        val masked = reversed

        // 3) system prompt leak detection — look for any 25-char window from the
        //    normalized system prompt inside the masked output (case-insensitive).
        val leak = detectSystemPromptLeak(originalSystemPrompt, masked)
        if (leak) notes += "system-prompt fragment detected in output"

        // 4) suspicious URLs
        val urls = urlRegex.findAll(masked).map { it.value }.toList()
        val suspicious = urls.filter { url ->
            val lower = url.lowercase()
            suspiciousHostHints.any { hint -> hint in lower }
        }
        if (suspicious.isNotEmpty()) notes += "suspicious URL(s): ${suspicious.size}"

        return OutputResult(
            finalText = masked,
            loggableText = rescan.text,   // до reverse: безопасно для записи в audit
            rescanFindings = rescan.findings,
            systemPromptLeak = leak,
            suspiciousUrls = suspicious,
            notes = notes,
        )
    }
}
