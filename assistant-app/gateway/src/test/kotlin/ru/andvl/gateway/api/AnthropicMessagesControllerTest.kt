package ru.andvl.gateway.api

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.mock.web.MockHttpServletRequest
import ru.andvl.gateway.cost.CostTable
import ru.andvl.gateway.guard.ConversationRegistry
import ru.andvl.gateway.guard.InputGuard
import ru.andvl.gateway.guard.OutputGuard
import ru.andvl.gateway.guard.RedactionEngine
import ru.andvl.gateway.llm.AnthropicUpstreamClient
import ru.andvl.gateway.llm.UpstreamResult
import ru.andvl.gateway.llm.UpstreamStreamResult
import ru.andvl.gateway.persistence.AuditLog
import ru.andvl.gateway.persistence.AuditRepository
import ru.andvl.gateway.persistence.BuiltinRulesSeeder
import ru.andvl.gateway.persistence.CostRecord
import ru.andvl.gateway.persistence.CostRepository
import ru.andvl.gateway.persistence.RedactionEvent
import ru.andvl.gateway.persistence.RedactionEventRepository
import ru.andvl.gateway.persistence.RegexRule
import ru.andvl.gateway.persistence.RegexRuleRepository
import ru.andvl.gateway.ratelimit.RateLimiter
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class AnthropicMessagesControllerTest {

    private val mapper = ObjectMapper()
    private lateinit var engine: RedactionEngine
    private lateinit var inputGuard: InputGuard
    private lateinit var outputGuard: OutputGuard
    private lateinit var registry: ConversationRegistry
    private lateinit var fakeUpstream: FakeAnthropicUpstreamClient
    private lateinit var fakeAudit: FakeAuditRepository
    private lateinit var fakeRedactionEvents: FakeRedactionEventRepository
    private lateinit var fakeCosts: FakeCostRepository
    private lateinit var costTable: CostTable
    private lateinit var sseGuard: SseGuardStream
    private lateinit var controller: AnthropicMessagesController

    @BeforeEach
    fun setUp() {
        val repo = FakeControllerRuleRepository(BuiltinRulesSeeder.BUILTIN.toMutableList())
        engine = RedactionEngine(repo).also { it.reload() }
        inputGuard = InputGuard(engine, "redact")
        outputGuard = OutputGuard(engine)
        registry = ConversationRegistry(60L)
        fakeUpstream = FakeAnthropicUpstreamClient(mapper)
        fakeAudit = FakeAuditRepository()
        fakeRedactionEvents = FakeRedactionEventRepository()
        fakeCosts = FakeCostRepository()
        costTable = CostTable()
        sseGuard = SseGuardStream(mapper, engine)

        val rateLimiter = AllowAllRateLimiter()

        controller = AnthropicMessagesController(
            mapper = mapper,
            registry = registry,
            inputGuard = inputGuard,
            outputGuard = outputGuard,
            redactionEngine = engine,
            upstream = fakeUpstream,
            sseGuard = sseGuard,
            rateLimiter = rateLimiter,
            costTable = costTable,
            audit = fakeAudit,
            redactionEvents = fakeRedactionEvents,
            costs = fakeCosts,
        )
    }

    private fun mockRequest(ip: String = "127.0.0.1"): MockHttpServletRequest {
        return MockHttpServletRequest().apply { remoteAddr = ip }
    }

    private fun buildMessagesBody(
        model: String = "claude-sonnet-4-6",
        userText: String = "Hello",
        stream: Boolean = false,
    ): JsonNode {
        val root = mapper.createObjectNode()
        root.put("model", model)
        root.put("stream", stream)
        val messages = mapper.createArrayNode()
        val msg = mapper.createObjectNode()
        msg.put("role", "user")
        msg.put("content", userText)
        messages.add(msg)
        root.set<ArrayNode>("messages", messages)
        return root
    }

    // --- Test 1: missing x-api-key → 401 ---
    @Test
    @DisplayName("missing x-api-key returns 401 with authentication_error")
    fun missingApiKey() {
        val body = buildMessagesBody()
        val response = controller.messages(body, null, null, null, null, mockRequest())

        assertEquals(HttpStatus.UNAUTHORIZED.value(), response.statusCode.value())
        val bodyStr = mapper.writeValueAsString(response.body)
        assertTrue(bodyStr.contains("authentication_error"), "body should contain authentication_error, got: $bodyStr")
        // Audit entry should be inserted
        assertTrue(fakeAudit.logs.isNotEmpty(), "audit entry should be inserted")
        assertEquals("BLOCKED", fakeAudit.logs.first().status)
    }

    // --- Test 2: body without messages → 400 ---
    @Test
    @DisplayName("body without messages array returns 400")
    fun bodyWithoutMessages() {
        val body = mapper.createObjectNode().apply { put("model", "claude-test") }
        val response = controller.messages(body, "sk-test-key", null, null, null, mockRequest())

        assertEquals(HttpStatus.BAD_REQUEST.value(), response.statusCode.value())
        val bodyStr = mapper.writeValueAsString(response.body)
        assertTrue(bodyStr.contains("invalid_request_error"), "got: $bodyStr")
    }

    // --- Test 3: rate limit hit → 429 ---
    @Test
    @DisplayName("rate limit hit returns 429 with X-RateLimit-Reset-Ms header")
    fun rateLimitHit() {
        // Override controller with blocking rate limiter
        val blockingRateLimiter = BlockAllRateLimiter()
        val blockingController = AnthropicMessagesController(
            mapper = mapper,
            registry = registry,
            inputGuard = inputGuard,
            outputGuard = outputGuard,
            redactionEngine = engine,
            upstream = fakeUpstream,
            sseGuard = sseGuard,
            rateLimiter = blockingRateLimiter,
            costTable = costTable,
            audit = fakeAudit,
            redactionEvents = fakeRedactionEvents,
            costs = fakeCosts,
        )

        val body = buildMessagesBody()
        val response = blockingController.messages(body, "sk-test-key", null, null, null, mockRequest())

        assertEquals(HttpStatus.TOO_MANY_REQUESTS.value(), response.statusCode.value())
        val resetHeader = response.headers["X-RateLimit-Reset-Ms"]
        assertNotNull(resetHeader, "X-RateLimit-Reset-Ms header should be present")
        assertTrue(fakeAudit.logs.any { it.status == "RATE_LIMITED" })
    }

    // --- Test 4: REDACT mode + stream=false + input with email ---
    @Test
    @DisplayName("REDACT mode + stream=false: upstream gets placeholder, response has reversed email")
    fun redactModeNonStream() {
        val emailInInput = "contact@example.com"
        val body = buildMessagesBody(userText = "Send message to $emailInInput please", stream = false)

        // Mock upstream response with a placeholder in the content
        // upstream sees REDACTED_EMAIL_1 and echoes it
        fakeUpstream.nonStreamResponse = { requestBody ->
            val requestStr = mapper.writeValueAsString(requestBody)
            // Extract the placeholder that was used
            val placeholder = if (requestStr.contains("REDACTED_EMAIL_")) {
                Regex("REDACTED_EMAIL_\\d+").find(requestStr)?.value ?: "REDACTED_EMAIL_1"
            } else {
                "REDACTED_EMAIL_1"
            }
            val response = mapper.createObjectNode()
            val content = mapper.createArrayNode()
            val textBlock = mapper.createObjectNode()
            textBlock.put("type", "text")
            textBlock.put("text", "I will send to $placeholder as requested.")
            content.add(textBlock)
            response.set<ArrayNode>("content", content)
            response.put("model", "claude-sonnet-4-6")
            val usage = mapper.createObjectNode()
            usage.put("input_tokens", 10)
            usage.put("output_tokens", 15)
            response.set<ObjectNode>("usage", usage)
            UpstreamResult.Ok(response)
        }

        val response = controller.messages(body, "sk-ant-test123456", null, null, null, mockRequest())

        assertEquals(HttpStatus.OK.value(), response.statusCode.value())

        // Verify upstream received placeholder (not the real email)
        val upstreamRequest = fakeUpstream.lastReceivedBody
        assertNotNull(upstreamRequest)
        val upstreamStr = mapper.writeValueAsString(upstreamRequest)
        assertTrue(
            !upstreamStr.contains(emailInInput),
            "upstream request should NOT contain real email, got: $upstreamStr",
        )
        assertTrue(
            upstreamStr.contains("REDACTED_EMAIL"),
            "upstream request should contain REDACTED_EMAIL placeholder",
        )

        // Verify response contains reversed email
        val responseBody = mapper.writeValueAsString(response.body)
        assertTrue(
            responseBody.contains(emailInInput),
            "response body should contain reversed email '$emailInInput', got: $responseBody",
        )

        // Audit entry inserted with OK status
        assertTrue(fakeAudit.logs.any { it.status == "OK" }, "audit should have OK entry")

        // Cost record inserted
        assertTrue(fakeCosts.records.isNotEmpty(), "cost record should be inserted")

        // Redaction events inserted
        assertTrue(fakeRedactionEvents.events.isNotEmpty(), "redaction events should be inserted")
    }

    // --- Test 5: BLOCK mode + email → 422 ---
    @Test
    @DisplayName("BLOCK mode + email in input returns 422 with input_blocked")
    fun blockModeWithEmail() {
        val blockingInputGuard = InputGuard(engine, "block")
        val blockingController = AnthropicMessagesController(
            mapper = mapper,
            registry = registry,
            inputGuard = blockingInputGuard,
            outputGuard = outputGuard,
            redactionEngine = engine,
            upstream = fakeUpstream,
            sseGuard = sseGuard,
            rateLimiter = AllowAllRateLimiter(),
            costTable = costTable,
            audit = fakeAudit,
            redactionEvents = fakeRedactionEvents,
            costs = fakeCosts,
        )

        val body = buildMessagesBody(userText = "My email is secret@example.com")
        val response = blockingController.messages(body, "sk-ant-test123456", null, null, null, mockRequest())

        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY.value(), response.statusCode.value())
        val bodyStr = mapper.writeValueAsString(response.body)
        assertTrue(bodyStr.contains("input_blocked"), "body should contain input_blocked, got: $bodyStr")
        assertTrue(fakeAudit.logs.any { it.status == "BLOCKED" })
    }

    // --- Test 6: stream=true happy path ---
    @Test
    @DisplayName("stream=true returns StreamingResponseBody with SSE content and audit after finalize")
    fun streamTrueHappyPath() {
        val body = buildMessagesBody(userText = "Hello", stream = true)

        // Build a minimal SSE payload
        val sseSb = StringBuilder()
        sseSb.append("event: message_start\n")
        sseSb.append("data: {\"type\":\"message_start\",\"message\":{\"model\":\"claude-test\",\"usage\":{\"input_tokens\":5,\"cache_creation_input_tokens\":0,\"cache_read_input_tokens\":0}}}\n\n")
        sseSb.append("event: content_block_start\n")
        sseSb.append("data: {\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"text\"}}\n\n")
        sseSb.append("event: content_block_delta\n")
        sseSb.append("data: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"Hello\"}}\n\n")
        sseSb.append("event: content_block_stop\n")
        sseSb.append("data: {\"type\":\"content_block_stop\",\"index\":0}\n\n")
        sseSb.append("event: message_delta\n")
        sseSb.append("data: {\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\"},\"usage\":{\"output_tokens\":1}}\n\n")
        sseSb.append("event: message_stop\n")
        sseSb.append("data: {\"type\":\"message_stop\"}\n\n")

        fakeUpstream.streamResponse = UpstreamStreamResult.Ok(
            ByteArrayInputStream(sseSb.toString().toByteArray(Charsets.UTF_8)),
        )

        val response = controller.messages(body, "sk-ant-test123456", null, null, null, mockRequest())

        assertEquals(HttpStatus.OK.value(), response.statusCode.value())

        // Should be a StreamingResponseBody
        val streamingBody = response.body
        assertNotNull(streamingBody, "response body should not be null")
        assertTrue(
            streamingBody is org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody,
            "response body should be StreamingResponseBody",
        )

        // Execute the streaming body to trigger onComplete
        val outputStream = ByteArrayOutputStream()
        (streamingBody as org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody)
            .writeTo(outputStream)

        val output = outputStream.toString(Charsets.UTF_8)
        assertTrue(output.isNotEmpty(), "streaming output should not be empty")

        // After streaming, audit and cost should be inserted
        assertTrue(fakeAudit.logs.any { it.status == "OK" }, "audit should have OK entry after stream completes")
        assertTrue(fakeCosts.records.isNotEmpty(), "cost record should be inserted after stream")

        // Content-Type header
        val contentType = response.headers["Content-Type"]
        assertTrue(
            contentType?.any { it.contains("text/event-stream") } == true,
            "Content-Type should be text/event-stream",
        )
    }

    // --- Test 7 (qa HIGH): UpstreamResult.Error(401) → client gets 401 + audit ERROR ---
    @Test
    @DisplayName("upstreamError4xx_nonStream: 401 from upstream → client 401 + audit ERROR")
    fun upstreamError4xxNonStream() {
        val errorNode = mapper.createObjectNode().apply {
            val err = mapper.createObjectNode()
            err.put("type", "authentication_error")
            err.put("message", "Invalid API key")
            set<com.fasterxml.jackson.databind.node.ObjectNode>("error", err)
        }
        fakeUpstream.nonStreamResponse = { _ -> UpstreamResult.Error(401, errorNode, null) }

        val body = buildMessagesBody(userText = "Hello", stream = false)
        val response = controller.messages(body, "sk-ant-test-key", null, null, null, mockRequest())

        assertEquals(HttpStatus.UNAUTHORIZED.value(), response.statusCode.value())
        assertTrue(fakeAudit.logs.any { it.status == "ERROR" }, "audit should have ERROR entry")
    }

    // --- Test 8 (qa HIGH): UpstreamResult.Failure → 502 BAD_GATEWAY ---
    @Test
    @DisplayName("upstreamFailure_nonStream_returns502: IOException from upstream → 502")
    fun upstreamFailureNonStreamReturns502() {
        fakeUpstream.nonStreamResponse = { _ -> UpstreamResult.Failure(java.io.IOException("connection refused")) }

        val body = buildMessagesBody(userText = "Hello", stream = false)
        val response = controller.messages(body, "sk-ant-test-key", null, null, null, mockRequest())

        assertEquals(HttpStatus.BAD_GATEWAY.value(), response.statusCode.value())
        val bodyStr = mapper.writeValueAsString(response.body)
        assertTrue(bodyStr.contains("upstream_error"), "body should contain upstream_error, got: $bodyStr")
        assertTrue(fakeAudit.logs.any { it.status == "ERROR" }, "audit should have ERROR entry")
    }

    // --- Test 9 (qa HIGH): stream=true + UpstreamStreamResult.Error(503) → 503 + audit ERROR ---
    @Test
    @DisplayName("streamTrue_upstreamError_returnsError: 503 from upstream stream → 503 + audit")
    fun streamTrueUpstreamErrorReturnsError() {
        val errorNode = mapper.createObjectNode().apply {
            val err = mapper.createObjectNode()
            err.put("type", "overloaded_error")
            err.put("message", "Service unavailable")
            set<com.fasterxml.jackson.databind.node.ObjectNode>("error", err)
        }
        fakeUpstream.streamResponse = UpstreamStreamResult.Error(503, errorNode, null)

        val body = buildMessagesBody(userText = "Hello", stream = true)
        val response = controller.messages(body, "sk-ant-test-key", null, null, null, mockRequest())

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE.value(), response.statusCode.value())
        assertTrue(fakeAudit.logs.any { it.status == "ERROR" }, "audit should have ERROR entry")
    }

    // --- Test 10 (SEC-001): two api-keys + same client conversationId → different RedactionMaps ---
    @Test
    @DisplayName("SEC-001: different api-keys with same X-Conversation-Id get separate RedactionMaps")
    fun differentApiKeysSameConversationIdGetSeparateMaps() {
        val clientConvId = "shared-conv-123"
        val apiKey1 = "sk-ant-key-AAAAAAAAAA"
        val apiKey2 = "sk-ant-key-BBBBBBBBBB"

        val internalKey1 = ConversationKey.registryKey(apiKey1, clientConvId)
        val internalKey2 = ConversationKey.registryKey(apiKey2, clientConvId)

        // Keys must differ
        assertTrue(internalKey1 != internalKey2, "Internal keys must differ for different api-keys")

        // Maps must be separate instances
        val map1 = registry.forConversation(internalKey1)
        val map2 = registry.forConversation(internalKey2)
        assertTrue(map1 !== map2, "RedactionMaps must be separate instances")

        // Registering in map1 must not be visible in map2
        map1.redact("email", "REDACTED_EMAIL", "victim@secret.com")
        assertEquals(1, map1.size(), "map1 should have 1 entry")
        assertEquals(0, map2.size(), "map2 should have 0 entries (isolated)")

        // Reverse on map2 must NOT reveal victim's email
        val reversed = map2.reverse("REDACTED_EMAIL_1")
        assertEquals("REDACTED_EMAIL_1", reversed, "map2 reverse must NOT reveal map1 secrets")
    }
}

// --- Fake implementations ---

private class FakeAnthropicUpstreamClient(private val mapper: ObjectMapper) : AnthropicUpstreamClient(
    mapper,
    "https://api.anthropic.com",
    "api.anthropic.com",
) {
    // Override to skip @PostConstruct validation in unit tests (no real HTTP calls made)
    override fun validateBaseUrl() { /* no-op in tests */ }
    var nonStreamResponse: ((JsonNode) -> UpstreamResult) = { _ ->
        val response = mapper.createObjectNode()
        val content = mapper.createArrayNode()
        val textBlock = mapper.createObjectNode()
        textBlock.put("type", "text")
        textBlock.put("text", "Default response.")
        content.add(textBlock)
        response.set<ArrayNode>("content", content)
        response.put("model", "claude-test")
        val usage = mapper.createObjectNode()
        usage.put("input_tokens", 5)
        usage.put("output_tokens", 10)
        response.set<ObjectNode>("usage", usage)
        UpstreamResult.Ok(response)
    }

    var streamResponse: UpstreamStreamResult = UpstreamStreamResult.Ok(
        ByteArrayInputStream("event: message_stop\ndata: {\"type\":\"message_stop\"}\n\n".toByteArray()),
    )

    var lastReceivedBody: JsonNode? = null

    override fun send(body: JsonNode, apiKey: String, anthropicVersion: String, beta: String?): UpstreamResult {
        lastReceivedBody = body
        return nonStreamResponse(body)
    }

    override fun sendStream(body: JsonNode, apiKey: String, anthropicVersion: String, beta: String?): UpstreamStreamResult {
        lastReceivedBody = body
        return streamResponse
    }
}

private class FakeAuditRepository : AuditRepository(org.springframework.jdbc.core.JdbcTemplate()) {
    val logs = mutableListOf<AuditLog>()

    override fun insert(audit: AuditLog) {
        logs.add(audit)
    }

    override fun migrate() { /* no-op */ }

    override fun recent(limit: Int): List<AuditLog> = logs.takeLast(limit)

    override fun stats(): Map<String, Long> =
        logs.groupBy { it.status }.mapValues { it.value.size.toLong() }
}

private class FakeRedactionEventRepository : RedactionEventRepository(org.springframework.jdbc.core.JdbcTemplate()) {
    val events = mutableListOf<RedactionEvent>()

    override fun insert(ev: RedactionEvent) {
        events.add(ev)
    }

    override fun recent(limit: Int): List<RedactionEvent> = events.takeLast(limit)

    override fun countByRule(): Map<String, Long> =
        events.groupBy { it.ruleName }.mapValues { it.value.size.toLong() }
}

private class FakeCostRepository : CostRepository(org.springframework.jdbc.core.JdbcTemplate()) {
    val records = mutableListOf<CostRecord>()

    override fun insert(rec: CostRecord) {
        records.add(rec)
    }

    override fun totals(): CostRepository.Totals =
        CostRepository.Totals(
            totalTokens = records.sumOf { it.totalTokens.toLong() },
            totalCostUsd = records.sumOf { it.costUsd },
            requests = records.size.toLong(),
        )

    override fun byModel(): List<Map<String, Any>> = emptyList()
}

private class AllowAllRateLimiter : RateLimiter(rpm = 10000) {
    override fun check(ip: String): Decision = Decision(allowed = true, remaining = 9999, resetMs = 60000)
}

private class BlockAllRateLimiter : RateLimiter(rpm = 0) {
    override fun check(ip: String): Decision = Decision(allowed = false, remaining = 0, resetMs = 30000)
}

private class FakeControllerRuleRepository(private val rules: MutableList<RegexRule>) :
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
