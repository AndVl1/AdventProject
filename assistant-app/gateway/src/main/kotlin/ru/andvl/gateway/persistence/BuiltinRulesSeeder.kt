package ru.andvl.gateway.persistence

import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component

@Component
class BuiltinRulesSeeder(private val repo: RegexRuleRepository) {

    @EventListener(ApplicationReadyEvent::class)
    fun seed() {
        BUILTIN.forEach { rule ->
            if (repo.findByName(rule.name) == null) repo.insert(rule)
        }
    }

    companion object {
        // Order matters: more specific patterns first (run before generic high-entropy heuristic)
        val BUILTIN: List<RegexRule> = listOf(
            RegexRule(
                name = "openai_key",
                pattern = """sk-(?:proj-)?[A-Za-z0-9_\-]{20,}""",
                category = "API_KEY",
                placeholder = "REDACTED_OPENAI_KEY",
                builtin = true,
            ),
            RegexRule(
                name = "anthropic_key",
                pattern = """sk-ant-[A-Za-z0-9_\-]{20,}""",
                category = "API_KEY",
                placeholder = "REDACTED_ANTHROPIC_KEY",
                builtin = true,
            ),
            RegexRule(
                name = "github_token",
                pattern = """gh[pousr]_[A-Za-z0-9]{36,}""",
                category = "API_KEY",
                placeholder = "REDACTED_GITHUB_TOKEN",
                builtin = true,
            ),
            RegexRule(
                name = "aws_access_key",
                pattern = """AKIA[0-9A-Z]{16}""",
                category = "API_KEY",
                placeholder = "REDACTED_AWS_KEY",
                builtin = true,
            ),
            RegexRule(
                name = "aws_secret_key",
                pattern = """(?<![A-Za-z0-9/+=])[A-Za-z0-9/+=]{40}(?![A-Za-z0-9/+=])""",
                category = "API_KEY",
                placeholder = "REDACTED_AWS_SECRET",
                builtin = true,
            ),
            RegexRule(
                name = "google_api_key",
                pattern = """AIza[0-9A-Za-z_\-]{35}""",
                category = "API_KEY",
                placeholder = "REDACTED_GOOGLE_KEY",
                builtin = true,
            ),
            RegexRule(
                name = "slack_token",
                pattern = """xox[abprs]-[A-Za-z0-9-]{10,}""",
                category = "API_KEY",
                placeholder = "REDACTED_SLACK_TOKEN",
                builtin = true,
            ),
            RegexRule(
                name = "jwt",
                pattern = """eyJ[A-Za-z0-9_\-]{8,}\.eyJ[A-Za-z0-9_\-]{8,}\.[A-Za-z0-9_\-]{8,}""",
                category = "API_KEY",
                placeholder = "REDACTED_JWT",
                builtin = true,
            ),
            RegexRule(
                name = "credit_card",
                pattern = """\b(?:\d[ -]*?){13,19}\b""",
                category = "PII",
                placeholder = "REDACTED_CREDIT_CARD",
                builtin = true,
            ),
            RegexRule(
                name = "email",
                pattern = """[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}""",
                category = "PII",
                placeholder = "REDACTED_EMAIL",
                builtin = true,
            ),
            RegexRule(
                name = "phone_e164",
                pattern = """\+\d[\d\s\-()]{8,16}\d""",
                category = "PII",
                placeholder = "REDACTED_PHONE",
                builtin = true,
            ),
            RegexRule(
                name = "phone_ru",
                pattern = """\b8[\s\-]?\(?\d{3}\)?[\s\-]?\d{3}[\s\-]?\d{2}[\s\-]?\d{2}\b""",
                category = "PII",
                placeholder = "REDACTED_PHONE",
                builtin = true,
            ),
        )
    }
}
