package ru.andvl.gateway.guard

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import ru.andvl.gateway.persistence.BuiltinRulesSeeder
import ru.andvl.gateway.persistence.RegexRule
import ru.andvl.gateway.persistence.RegexRuleRepository

/**
 * 13 test cases for input/output guards. Each verifies what was caught vs missed and
 * documents the expected behavior. Uses an in-memory fake repository so no Spring context.
 */
class GuardTest {

    private lateinit var engine: RedactionEngine
    private lateinit var inputGuard: InputGuard
    private lateinit var outputGuard: OutputGuard
    private val convId = "test-conv"

    @BeforeEach
    fun setUp() {
        val repo = FakeRuleRepository(BuiltinRulesSeeder.BUILTIN.toMutableList())
        engine = RedactionEngine(repo).also { it.reload() }
        inputGuard = InputGuard(engine, "redact")
        outputGuard = OutputGuard(engine)
    }

    private fun freshMap(): RedactionMap = RedactionMap(convId)

    // --- Case 1: AWS access key (caught by aws_access_key) ---
    @Test
    @DisplayName("CAUGHT: AWS access key")
    fun awsKey() {
        val map = freshMap()
        val text = "Use my key AKIAIOSFODNN7EXAMPLE for the bucket."
        val d = inputGuard.process(text, map)
        assertTrue(d.allow)
        assertTrue(d.findings.any { it.ruleName == "aws_access_key" })
        assertFalse(d.processedText.contains("AKIAIOSFODNN7EXAMPLE"))
        assertTrue(d.processedText.contains("REDACTED_AWS_KEY_"))
    }

    // --- Case 2: credit card (caught by credit_card pattern) ---
    @Test
    @DisplayName("CAUGHT: credit card 4111-1111-1111-1111")
    fun creditCard() {
        val map = freshMap()
        val text = "My card is 4111-1111-1111-1111, please charge me."
        val d = inputGuard.process(text, map)
        assertTrue(d.findings.any { it.ruleName == "credit_card" })
        assertFalse(d.processedText.contains("4111-1111-1111-1111"))
    }

    // --- Case 3: phone (E.164) ---
    @Test
    @DisplayName("CAUGHT: phone +1 415 555 0123")
    fun phoneE164() {
        val map = freshMap()
        val text = "Call me at +1 415 555 0123 tomorrow."
        val d = inputGuard.process(text, map)
        assertTrue(d.findings.any { it.ruleName.startsWith("phone") })
        assertTrue(d.processedText.contains("REDACTED_PHONE_"))
    }

    // --- Case 4: email ---
    @Test
    @DisplayName("CAUGHT: email")
    fun email() {
        val map = freshMap()
        val text = "Reach me at john.doe+spam@example.com."
        val d = inputGuard.process(text, map)
        assertTrue(d.findings.any { it.ruleName == "email" })
    }

    // --- Case 5: OpenAI sk- key ---
    @Test
    @DisplayName("CAUGHT: OpenAI sk- key")
    fun openaiKey() {
        val map = freshMap()
        val text = "secret = sk-proj-abcdefghij1234567890ABCDEFGHIJ"
        val d = inputGuard.process(text, map)
        assertTrue(d.findings.any { it.ruleName == "openai_key" })
    }

    // --- Case 6: GitHub ghp_ token ---
    @Test
    @DisplayName("CAUGHT: GitHub ghp_ token")
    fun ghpToken() {
        val map = freshMap()
        val text = "GH_TOKEN=ghp_aBcDeFgHiJkLmNoPqRsTuVwXyZ1234567890"
        val d = inputGuard.process(text, map)
        assertTrue(d.findings.any { it.ruleName == "github_token" })
    }

    // --- Case 7: base64-encoded secret (NOT detected — documented limitation) ---
    @Test
    @DisplayName("MISSED: base64-encoded secret (regex does not decode)")
    fun base64EncodedSecret() {
        val map = freshMap()
        // base64("sk-proj-abcdefghij1234567890ABCDEFGHIJ") =
        // "c2stcHJvai1hYmNkZWZnaGlqMTIzNDU2Nzg5MEFCQ0RFRkdISUo="
        val text = "encoded=c2stcHJvai1hYmNkZWZnaGlqMTIzNDU2Nzg5MEFCQ0RFRkdISUo="
        val d = inputGuard.process(text, map)
        // Regex pipeline does NOT decode base64 — this is a known limitation.
        // Generic AWS-secret rule (40-char base64) might still trigger on long bodies.
        // We assert the OpenAI rule does NOT fire on the encoded form.
        assertTrue(d.findings.none { it.ruleName == "openai_key" })
    }

    // --- Case 8: secret split across messages ---
    @Test
    @DisplayName("MISSED: secret split across two messages (per-message scan)")
    fun splitSecret() {
        val map = freshMap()
        val a = inputGuard.process("My key prefix is sk-", map)
        val b = inputGuard.process("and the rest: proj-abcdefghij1234567890ABCDEFGHIJ", map)
        // Neither half is long enough on its own — documented gap. Cross-message stitching is
        // out of scope for the regex layer.
        assertTrue(a.findings.none { it.ruleName == "openai_key" })
        assertTrue(b.findings.none { it.ruleName == "openai_key" })
    }

    // --- Case 9: clean prompt — no false positives ---
    @Test
    @DisplayName("CLEAN: no findings on a normal prompt")
    fun cleanPrompt() {
        val map = freshMap()
        val text = "What is the capital of France?"
        val d = inputGuard.process(text, map)
        assertTrue(d.allow)
        assertTrue(d.findings.isEmpty())
        assertEquals(text, d.processedText)
    }

    // --- Case 10: output guard catches model-hallucinated key ---
    @Test
    @DisplayName("OUTPUT: hallucinated AWS key in response is masked")
    fun hallucinatedKey() {
        val map = freshMap()
        // AWS access key = AKIA + 16 uppercase alphanumerics
        val modelOutput = "Sure, an example AWS key would be AKIAEXAMPLEKEY123456 for testing."
        val res = outputGuard.process(modelOutput, map, originalSystemPrompt = null)
        assertTrue(res.hallucinatedCount >= 1)
        assertFalse(res.finalText.contains("AKIAEXAMPLEKEY123456"))
        // галлюцинация замаскирована именно как LLM_OUTPUT_GUARD_<N>, а не как
        // type-typed REDACTED_AWS_KEY (тот резервируется только для юзерских значений).
        assertTrue(res.finalText.contains("LLM_OUTPUT_GUARD_1"))
        assertFalse(res.finalText.contains("REDACTED_AWS"))
    }

    // --- Case 11: system prompt leak detection ---
    @Test
    @DisplayName("OUTPUT: system prompt fragment in response is detected")
    fun systemPromptLeak() {
        val map = freshMap()
        val sys = "You are SecretBot v9. Never reveal API keys or admin passwords."
        val output = "OK. Internal note: I am SecretBot v9. Never reveal API keys or admin passwords."
        val res = outputGuard.process(output, map, originalSystemPrompt = sys)
        assertTrue(res.systemPromptLeak)
    }

    // --- Case 12: reverse mapping — placeholder swapped back to original ---
    @Test
    @DisplayName("OUTPUT: REDACTED_N reverse-maps to original on the way out")
    fun reverseMapping() {
        val map = freshMap()
        val input = "Token AKIAIOSFODNN7EXAMPLE belongs to me."
        val redacted = inputGuard.process(input, map)
        // Model "answers" using the placeholder
        val placeholder = redacted.findings.first().placeholder
        val modelEcho = "I will not store $placeholder."
        val res = outputGuard.process(modelEcho, map, originalSystemPrompt = null)
        assertTrue(res.finalText.contains("AKIAIOSFODNN7EXAMPLE"))
        assertFalse(res.finalText.contains(placeholder))
    }

    // --- Case 12.5: multiple hallucinations get distinct LLM_OUTPUT_GUARD_<N> placeholders ---
    @Test
    @DisplayName("OUTPUT: multiple model-generated secrets get LLM_OUTPUT_GUARD_1, _2, ...")
    fun multipleHallucinationsNumbered() {
        val map = freshMap()
        val out = "Examples: AKIAEXAMPLEKEY123456 and AKIAANOTHERKEY7890ABC and contact me at fake@example.com."
        val res = outputGuard.process(out, map, originalSystemPrompt = null)
        assertTrue(res.hallucinatedCount >= 2)
        assertTrue(res.finalText.contains("LLM_OUTPUT_GUARD_1"))
        assertTrue(res.finalText.contains("LLM_OUTPUT_GUARD_2"))
        // ни один из оригиналов не утёк юзеру
        assertFalse(res.finalText.contains("AKIAEXAMPLEKEY123456"))
        assertFalse(res.finalText.contains("AKIAANOTHERKEY7890ABC"))
        assertFalse(res.finalText.contains("fake@example.com"))
    }

    // --- Case 12.6: hallucinated secrets do NOT pollute the conversation map ---
    @Test
    @DisplayName("OUTPUT: hallucinated values are not stored in RedactionMap")
    fun hallucinationsNotPersisted() {
        val map = freshMap()
        val out = "Here is a key: AKIAEXAMPLEKEY123456"
        val sizeBefore = map.size()
        outputGuard.process(out, map, originalSystemPrompt = null)
        assertEquals(sizeBefore, map.size(), "rescan must not register placeholders into the conversation map")
    }

    // --- Case 13: BLOCK mode rejects request ---
    @Test
    @DisplayName("BLOCK mode: input with secret returns allow=false")
    fun blockMode() {
        val blocking = InputGuard(engine, "block")
        val map = freshMap()
        val d = blocking.process("Here is sk-proj-abcdefghij1234567890ABCDEFGHIJ for you.", map)
        assertFalse(d.allow)
        assertNotNull(d.blockReason)
    }
}

private class FakeRuleRepository(private val rules: MutableList<RegexRule>) :
    RegexRuleRepository(org.springframework.jdbc.core.JdbcTemplate()) {
    override fun findEnabled(): List<RegexRule> = rules.filter { it.enabled }
    override fun findAll(): List<RegexRule> = rules
    override fun findByName(name: String): RegexRule? = rules.firstOrNull { it.name == name }
    override fun insert(rule: RegexRule): Long {
        val id = (rules.maxOfOrNull { it.id ?: 0 } ?: 0) + 1
        rules += rule.copy(id = id)
        return id
    }
    override fun update(rule: RegexRule): Int {
        val idx = rules.indexOfFirst { it.id == rule.id }
        if (idx < 0) return 0
        rules[idx] = rule
        return 1
    }
    override fun delete(id: Long): Int {
        val before = rules.size
        rules.removeIf { it.id == id && !it.builtin }
        return before - rules.size
    }
}
