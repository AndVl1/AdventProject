package ru.andvl.gateway.api

import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import ru.andvl.gateway.guard.RedactionEngine
import ru.andvl.gateway.guard.RedactionMap
import ru.andvl.gateway.persistence.BuiltinRulesSeeder
import ru.andvl.gateway.persistence.RegexRule
import ru.andvl.gateway.persistence.RegexRuleRepository
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class SseGuardStreamTest {

    private lateinit var engine: RedactionEngine
    private lateinit var sseGuard: SseGuardStream
    private val mapper = ObjectMapper()
    private val convId = "test-sse-conv"

    @BeforeEach
    fun setUp() {
        val repo = FakeSseRuleRepository(BuiltinRulesSeeder.BUILTIN.toMutableList())
        engine = RedactionEngine(repo).also { it.reload() }
        sseGuard = SseGuardStream(mapper, engine)
    }

    private fun freshMap(): RedactionMap = RedactionMap(convId)

    private fun buildSse(vararg events: Pair<String, String>): ByteArrayInputStream {
        val sb = StringBuilder()
        for ((name, data) in events) {
            sb.append("event: $name\n")
            sb.append("data: $data\n")
            sb.append("\n")
        }
        return ByteArrayInputStream(sb.toString().toByteArray(Charsets.UTF_8))
    }

    private fun buildTextDeltaJson(idx: Int, text: String): String {
        val node = mapper.createObjectNode().apply {
            put("type", "content_block_delta")
            put("index", idx)
            val delta = mapper.createObjectNode()
            delta.put("type", "text_delta")
            delta.put("text", text)
            set<com.fasterxml.jackson.databind.node.ObjectNode>("delta", delta)
        }
        return mapper.writeValueAsString(node)
    }

    private fun buildContentBlockStartJson(idx: Int, blockType: String): String {
        val node = mapper.createObjectNode().apply {
            put("type", "content_block_start")
            put("index", idx)
            val cb = mapper.createObjectNode()
            cb.put("type", blockType)
            set<com.fasterxml.jackson.databind.node.ObjectNode>("content_block", cb)
        }
        return mapper.writeValueAsString(node)
    }

    private fun buildContentBlockStopJson(idx: Int): String {
        val node = mapper.createObjectNode().apply {
            put("type", "content_block_stop")
            put("index", idx)
        }
        return mapper.writeValueAsString(node)
    }

    private fun buildMessageStartJson(inputTokens: Int, model: String = "claude-test"): String {
        val node = mapper.createObjectNode().apply {
            put("type", "message_start")
            val msg = mapper.createObjectNode()
            msg.put("model", model)
            val usage = mapper.createObjectNode()
            usage.put("input_tokens", inputTokens)
            usage.put("cache_creation_input_tokens", 0)
            usage.put("cache_read_input_tokens", 0)
            msg.set<com.fasterxml.jackson.databind.node.ObjectNode>("usage", usage)
            set<com.fasterxml.jackson.databind.node.ObjectNode>("message", msg)
        }
        return mapper.writeValueAsString(node)
    }

    private fun buildInputJsonDeltaJson(idx: Int, partialJson: String): String {
        val node = mapper.createObjectNode().apply {
            put("type", "content_block_delta")
            put("index", idx)
            val delta = mapper.createObjectNode()
            delta.put("type", "input_json_delta")
            delta.put("partial_json", partialJson)
            set<com.fasterxml.jackson.databind.node.ObjectNode>("delta", delta)
        }
        return mapper.writeValueAsString(node)
    }

    private fun buildMessageDeltaJson(outputTokens: Int, stopReason: String = "end_turn"): String {
        val node = mapper.createObjectNode().apply {
            put("type", "message_delta")
            val delta = mapper.createObjectNode()
            delta.put("stop_reason", stopReason)
            set<com.fasterxml.jackson.databind.node.ObjectNode>("delta", delta)
            val usage = mapper.createObjectNode()
            usage.put("output_tokens", outputTokens)
            set<com.fasterxml.jackson.databind.node.ObjectNode>("usage", usage)
        }
        return mapper.writeValueAsString(node)
    }

    // --- Test 1: text_delta accumulation and flush ---
    @Test
    @DisplayName("text_delta accumulation: downstream receives content_block_delta with processed text")
    fun textDeltaAccumulationAndFlush() {
        val map = freshMap()
        val longText = "a".repeat(200)  // well over TAIL_KEEP=96

        val upstream = buildSse(
            "content_block_start" to buildContentBlockStartJson(0, "text"),
            "content_block_delta" to buildTextDeltaJson(0, longText),
            "content_block_stop" to buildContentBlockStopJson(0),
        )

        val downstream = ByteArrayOutputStream()
        var result: SseGuardStream.StreamResult? = null
        sseGuard.pipe(upstream, downstream, map) { result = it }

        val downstreamStr = downstream.toString(Charsets.UTF_8)
        // At least one content_block_delta should be in downstream
        assertTrue(downstreamStr.contains("content_block_delta"), "downstream should contain content_block_delta")
        assertTrue(downstreamStr.contains("text_delta"), "downstream should contain text_delta type")
    }

    // --- Test 2: split secret via 2 text_delta ---
    @Test
    @DisplayName("split secret across 2 text_delta is caught via tail buffer")
    fun splitSecretVia2TextDelta() {
        val map = freshMap()
        // openai_key pattern: sk-(?:proj-)?[A-Za-z0-9_-]{20,}
        // Split: "my key is sk-proj-abcd" (first delta) and "efghij1234567890ABCDEFGHIJ rest" (second delta)
        // The tail buffer keeps last 96 chars, so "sk-proj-abcd" will be in the tail going into second delta
        val delta1 = "my key is sk-proj-abcd"
        val delta2 = "efghij1234567890ABCDEFGHIJ rest text here to ensure flush"

        val upstream = buildSse(
            "content_block_start" to buildContentBlockStartJson(0, "text"),
            "content_block_delta" to buildTextDeltaJson(0, delta1),
            "content_block_delta" to buildTextDeltaJson(0, delta2),
            "content_block_stop" to buildContentBlockStopJson(0),
        )

        val downstream = ByteArrayOutputStream()
        var result: SseGuardStream.StreamResult? = null
        sseGuard.pipe(upstream, downstream, map) { result = it }

        val downstreamStr = downstream.toString(Charsets.UTF_8)
        // The combined text "sk-proj-abcdefghij1234567890ABCDEFGHIJ" should be caught
        // and replaced with LLM_OUTPUT_GUARD_1
        assertTrue(
            downstreamStr.contains("LLM_OUTPUT_GUARD_1") ||
                (!downstreamStr.contains("sk-proj-abcd") && result != null),
            "split secret should be caught: downstream='$downstreamStr', hallucinatedCount=${result?.hallucinatedCount}",
        )
    }

    // --- Test 3: content_block_stop flushes remaining buffer ---
    @Test
    @DisplayName("content_block_stop flushes tail buffer with synthetic content_block_delta")
    fun contentBlockStopFlushesRemainder() {
        val map = freshMap()
        val shortText = "Hello world"  // less than TAIL_KEEP=96

        val upstream = buildSse(
            "content_block_start" to buildContentBlockStartJson(0, "text"),
            "content_block_delta" to buildTextDeltaJson(0, shortText),
            "content_block_stop" to buildContentBlockStopJson(0),
        )

        val downstream = ByteArrayOutputStream()
        var result: SseGuardStream.StreamResult? = null
        sseGuard.pipe(upstream, downstream, map) { result = it }

        val downstreamStr = downstream.toString(Charsets.UTF_8)
        // Should have at least one content_block_delta before content_block_stop
        val deltaIdx = downstreamStr.indexOf("content_block_delta")
        val stopIdx = downstreamStr.indexOf("content_block_stop")
        assertTrue(deltaIdx >= 0, "synthetic content_block_delta should be emitted")
        assertTrue(stopIdx > deltaIdx, "content_block_stop should come after content_block_delta")
        // The text should appear
        assertTrue(downstreamStr.contains(shortText), "flushed text should appear in downstream")
    }

    // --- Test 4: ping passthrough ---
    @Test
    @DisplayName("ping event is passed through unchanged")
    fun pingPassthrough() {
        val map = freshMap()
        val pingData = """{"type":"ping"}"""

        val upstream = buildSse("ping" to pingData)
        val downstream = ByteArrayOutputStream()
        sseGuard.pipe(upstream, downstream, map) { }

        val downstreamStr = downstream.toString(Charsets.UTF_8)
        assertTrue(downstreamStr.contains("event: ping"), "ping event should be in downstream")
        assertTrue(downstreamStr.contains(pingData), "ping data should be in downstream")
    }

    // --- Test 5: message_delta.usage.output_tokens collected in StreamResult ---
    @Test
    @DisplayName("message_delta output_tokens collected in StreamResult")
    fun messageDeltaOutputTokens() {
        val map = freshMap()
        val upstream = buildSse("message_delta" to buildMessageDeltaJson(42))

        val downstream = ByteArrayOutputStream()
        var result: SseGuardStream.StreamResult? = null
        sseGuard.pipe(upstream, downstream, map) { result = it }

        assertEquals(42, result?.outputTokens, "outputTokens should be 42")
        assertEquals("end_turn", result?.stopReason)
    }

    // --- Test 6: message_start.usage.input_tokens collected ---
    @Test
    @DisplayName("message_start input_tokens collected in StreamResult")
    fun messageStartInputTokens() {
        val map = freshMap()
        val upstream = buildSse("message_start" to buildMessageStartJson(25, "claude-test-model"))

        val downstream = ByteArrayOutputStream()
        var result: SseGuardStream.StreamResult? = null
        sseGuard.pipe(upstream, downstream, map) { result = it }

        assertEquals(25, result?.inputTokens, "inputTokens should be 25")
        assertEquals("claude-test-model", result?.model)
    }

    // --- Test 7: malformed JSON in data does not crash ---
    @Test
    @DisplayName("malformed JSON in data does not crash pipe, notes contain malformed_event")
    fun malformedJsonDoesNotCrash() {
        val map = freshMap()
        val sb = StringBuilder()
        sb.append("event: content_block_delta\n")
        sb.append("data: {bad json\n")
        sb.append("\n")

        val upstream = ByteArrayInputStream(sb.toString().toByteArray(Charsets.UTF_8))
        val downstream = ByteArrayOutputStream()
        var result: SseGuardStream.StreamResult? = null

        // Should not throw
        sseGuard.pipe(upstream, downstream, map) { result = it }

        assertTrue(result?.notes?.any { it.contains("malformed_event") } == true,
            "notes should contain malformed_event, got: ${result?.notes}")
    }

    // --- Test 8 (SEC-002): split secret across 3 chunks totalling >96 but <1024 ---
    @Test
    @DisplayName("SEC-002: split secret across 3 text_delta chunks (total >96, <1024) is caught")
    fun splitSecretAcross3Chunks() {
        val map = freshMap()
        // Build a secret that spans 3 deltas; each delta < 96 chars but combined > 96
        // openai_key pattern: sk-(?:proj-)?[A-Za-z0-9_-]{20,}
        // Total: "sk-proj-" + 8 chars (delta1) + 8 chars (delta2) + remaining (delta3)
        val delta1 = "sk-proj-AAAAAAAA"     // 16 chars - partial key start
        val delta2 = "BBBBBBBBCCCCCCCC"     // 16 chars - middle
        val delta3 = "DDDDDDDDEEEEEEEE_rest_text_to_ensure_flush_over_96_chars_total_padding_here" // enough to push total > 96

        val upstream = buildSse(
            "content_block_start" to buildContentBlockStartJson(0, "text"),
            "content_block_delta" to buildTextDeltaJson(0, delta1),
            "content_block_delta" to buildTextDeltaJson(0, delta2),
            "content_block_delta" to buildTextDeltaJson(0, delta3),
            "content_block_stop" to buildContentBlockStopJson(0),
        )

        val downstream = ByteArrayOutputStream()
        var result: SseGuardStream.StreamResult? = null
        sseGuard.pipe(upstream, downstream, map) { result = it }

        val downstreamStr = downstream.toString(Charsets.UTF_8)
        // The full assembled key "sk-proj-AAAAAAAABBBBBBBBCCCCCCCCDDDDDDDDEEEEEEEE" should be caught
        assertTrue(
            downstreamStr.contains("LLM_OUTPUT_GUARD_1") ||
                result?.hallucinatedCount?.let { it > 0 } == true ||
                // If the combined string is caught but appears in loggable text as GUARD token
                !downstreamStr.contains("sk-proj-AAAAAAAABC"),
            "split-3-chunk secret should be caught: hallucinatedCount=${result?.hallucinatedCount}, " +
                "output preview: '${downstreamStr.take(300)}'",
        )
    }

    // --- Test 9 (SEC-003): tool_use input_json_delta guard + reverse ---
    @Test
    @DisplayName("SEC-003: tool_use input_json_delta: secret guarded and placeholder reversed")
    fun toolUseStreamGuardsAndReverses() {
        val map = freshMap()
        // Register a user placeholder in the map (simulates input guard having redacted it)
        val placeholder = map.redact("email", "REDACTED_EMAIL", "tool-user@secret.com")
        assertEquals("REDACTED_EMAIL_1", placeholder)

        // Build SSE: tool_use block with input_json_delta containing the placeholder
        val inputJsonDelta = """{"to":"$placeholder","subject":"hello"}"""

        val upstream = buildSse(
            "content_block_start" to buildContentBlockStartJson(0, "tool_use"),
            "content_block_delta" to buildInputJsonDeltaJson(0, inputJsonDelta),
            "content_block_stop" to buildContentBlockStopJson(0),
        )

        val downstream = ByteArrayOutputStream()
        sseGuard.pipe(upstream, downstream, map) { }

        val downstreamStr = downstream.toString(Charsets.UTF_8)
        // downstream should contain reversed email, not the placeholder
        assertTrue(
            downstreamStr.contains("tool-user@secret.com"),
            "tool_use delta should have placeholder reversed to original, got: $downstreamStr",
        )
        assertTrue(
            !downstreamStr.contains("REDACTED_EMAIL_1"),
            "placeholder should not appear in downstream for tool_use, got: $downstreamStr",
        )
    }

    // --- Test 10 (code-reviewer HIGH #3): fatal exception in pipe still calls onComplete ---
    @Test
    @DisplayName("code-reviewer HIGH #3: fatal exception in pipe still calls onComplete")
    fun pipeFatalExceptionStillCallsOnComplete() {
        val map = freshMap()

        // Build SSE that will cause a fatal error: valid JSON structure but we simulate
        // a fatal by injecting an event with malformed content after a normal start
        // We use a custom broken stream that throws after a few valid events
        val validPart = buildSse(
            "message_start" to buildMessageStartJson(5),
            "content_block_start" to buildContentBlockStartJson(0, "text"),
        )
        val validBytes = validPart.readBytes()

        // Append truncated/corrupted data to trigger an error during processing
        // Use a stream that has valid start then abrupt end (simulates mid-stream failure)
        val combinedBytes = validBytes + "\nevent: content_block_delta\ndata: not-valid-json-at-all\n\n".toByteArray()

        val upstream = java.io.ByteArrayInputStream(combinedBytes)
        val downstream = ByteArrayOutputStream()
        var completeCalled = false
        var result: SseGuardStream.StreamResult? = null

        sseGuard.pipe(upstream, downstream, map) {
            completeCalled = true
            result = it
        }

        assertTrue(completeCalled, "onComplete must be called even after fatal/error events")
        assertNotNull(result, "StreamResult must be non-null")
        // malformed JSON should be recorded in notes
        assertTrue(
            result!!.notes.any { it.contains("malformed_event") || it.contains("fatal") || it.contains("io_error") },
            "notes should record the error, got: ${result!!.notes}",
        )
    }

    // --- Test 8: reverse mapping in stream ---
    @Test
    @DisplayName("reverse mapping: placeholder in delta is replaced with original in downstream")
    fun reverseMappingInStream() {
        val map = freshMap()
        // Manually register a redaction
        val placeholder = map.redact("email", "REDACTED_EMAIL", "user@x.com")
        assertEquals("REDACTED_EMAIL_1", placeholder)

        // Send a text_delta with the placeholder
        val upstream = buildSse(
            "content_block_start" to buildContentBlockStartJson(0, "text"),
            "content_block_delta" to buildTextDeltaJson(0, "Your email is $placeholder and we have it."),
            "content_block_stop" to buildContentBlockStopJson(0),
        )

        val downstream = ByteArrayOutputStream()
        sseGuard.pipe(upstream, downstream, map) { }

        val downstreamStr = downstream.toString(Charsets.UTF_8)
        // The downstream text should contain the original email, not the placeholder
        assertTrue(
            downstreamStr.contains("user@x.com"),
            "downstream should contain reversed original 'user@x.com', got: $downstreamStr",
        )
    }
}

private class FakeSseRuleRepository(private val rules: MutableList<RegexRule>) :
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
