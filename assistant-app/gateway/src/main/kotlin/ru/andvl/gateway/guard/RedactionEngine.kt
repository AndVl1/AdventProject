package ru.andvl.gateway.guard

import org.springframework.stereotype.Service
import ru.andvl.gateway.persistence.RegexRuleRepository
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicReference

data class CompiledRule(
    val name: String,
    val regex: Regex,
    val placeholder: String,
    val category: String,
)

data class Finding(
    val ruleName: String,
    val placeholder: String,
    val original: String,
    val originalHash: String,
)

data class RedactionResult(
    val text: String,
    val findings: List<Finding>,
)

/**
 * Applies all enabled regex rules to a text, replacing matches with deterministic
 * REDACTED_TYPE_N placeholders allocated via [RedactionMap].
 *
 * Rules are compiled lazily on first call and cached. [reload] forces recompile —
 * call after admin CRUD on regex_rule.
 *
 * Thread-safety: rules cache is an AtomicReference. Multiple threads can apply()
 * in parallel without locks.
 */
@Service
class RedactionEngine(private val ruleRepo: RegexRuleRepository) {

    private val cache = AtomicReference<List<CompiledRule>>()

    fun reload() {
        val compiled = ruleRepo.findEnabled().mapNotNull { r ->
            runCatching {
                CompiledRule(
                    name = r.name,
                    regex = Regex(r.pattern),
                    placeholder = r.placeholder,
                    category = r.category,
                )
            }.getOrNull()  // skip rules with invalid regex
        }
        cache.set(compiled)
    }

    private fun rules(): List<CompiledRule> {
        var c = cache.get()
        if (c == null) {
            reload()
            c = cache.get() ?: emptyList()
        }
        return c
    }

    /**
     * Applies all rules to [text]. Returns redacted text + findings (one per replaced match).
     * If [map] is non-null, allocates and tracks placeholders for reverse mapping.
     * If [map] is null (output guard re-scan), produces placeholders but does not track.
     */
    fun apply(text: String, map: RedactionMap?): RedactionResult {
        if (text.isEmpty()) return RedactionResult(text, emptyList())
        var current = text
        val findings = mutableListOf<Finding>()
        for (rule in rules()) {
            current = rule.regex.replace(current) { mr ->
                val original = mr.value
                val placeholder = map?.redact(rule.name, rule.placeholder, original) ?: rule.placeholder
                findings += Finding(rule.name, placeholder, original, sha256Prefix(original))
                placeholder
            }
        }
        return RedactionResult(current, findings)
    }

    companion object {
        fun sha256Prefix(s: String, len: Int = 16): String {
            val md = MessageDigest.getInstance("SHA-256").digest(s.toByteArray())
            return md.joinToString("") { "%02x".format(it) }.take(len)
        }
    }
}
