package ru.andvl.gateway.guard

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service

enum class GuardMode { REDACT, BLOCK }

data class InputDecision(
    val allow: Boolean,
    val processedText: String,
    val findings: List<Finding>,
    val blockReason: String? = null,
)

@Service
class InputGuard(
    private val engine: RedactionEngine,
    @Value("\${gateway.redaction.mode:redact}") modeStr: String,
) {

    private val mode: GuardMode = runCatching { GuardMode.valueOf(modeStr.uppercase()) }
        .getOrDefault(GuardMode.REDACT)

    fun process(text: String, map: RedactionMap): InputDecision {
        val res = engine.apply(text, map)
        if (res.findings.isEmpty()) return InputDecision(true, text, emptyList())
        return when (mode) {
            GuardMode.REDACT -> InputDecision(true, res.text, res.findings)
            GuardMode.BLOCK -> InputDecision(
                allow = false,
                processedText = res.text,
                findings = res.findings,
                blockReason = "Input contains ${res.findings.size} sensitive value(s) " +
                    "(${res.findings.map { it.ruleName }.distinct().joinToString(", ")}).",
            )
        }
    }
}
