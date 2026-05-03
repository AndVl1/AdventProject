package ru.andvl.gateway.api

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import ru.andvl.gateway.cost.CostTable
import ru.andvl.gateway.guard.ConversationRegistry
import ru.andvl.gateway.guard.InputGuard
import ru.andvl.gateway.guard.OutputGuard
import ru.andvl.gateway.guard.RedactionEngine
import ru.andvl.gateway.llm.AnthropicRoutesProperties
import ru.andvl.gateway.llm.AnthropicUpstreamClient
import ru.andvl.gateway.llm.ModelEndpointRouter
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
    private lateinit var fakeRouter: ModelEndpointRouter
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
        fakeRouter = ModelEndpointRouter("https://api.anthropic.com", AnthropicRoutesProperties())
        fakeUpstream = FakeAnthropicUpstreamClient(mapper, fakeRouter)
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
            router = fakeRouter,
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

    private fun mockResponse(): MockHttpServletResponse = MockHttpServletResponse()

    @Suppress("UNCHECKED_CAST")
    private val Any?.statusCode get() = (this as ResponseEntity<*>).statusCode
    private val Any?.body get() = (this as ResponseEntity<*>).body
    private val Any?.headers get() = (this as ResponseEntity<*>).headers

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
        val response = controller.messages(mapper.writeValueAsBytes(body), null, null, null, null, null, mockRequest(), mockResponse())

        assertEquals(HttpStatus.UNAUTHORIZED.value(), response.statusCode.value())
        val bodyStr = mapper.writeValueAsString(response.body)
        assertTrue(bodyStr.contains("authentication_error"), "body should contain authentication_error, got: $bodyStr")
        // Audit entry should be inserted
        assertTrue(fakeAudit.logs.isNotEmpty(), "audit entry should be inserted")
        assertEquals("BLOCKED", fakeAudit.logs.first().status)
    }

    // --- Test 1b: Authorization: Bearer accepted in lieu of x-api-key ---
    @Test
    @DisplayName("Authorization: Bearer accepted, forwarded to upstream as bearerToken")
    fun bearerAuthAccepted() {
        val body = buildMessagesBody()
        val response = controller.messages(
            mapper.writeValueAsBytes(body), null, "Bearer sk-ant-oat01-abcdef", null, null, null, mockRequest(),
        mockResponse(),
        )

        assertEquals(HttpStatus.OK.value(), response.statusCode.value())
        assertEquals("sk-ant-oat01-abcdef", fakeUpstream.lastReceivedBearerToken)
        assertEquals(null, fakeUpstream.lastReceivedApiKey)
    }

    @Test
    @DisplayName("x-api-key wins over Authorization when both present")
    fun apiKeyWinsOverBearer() {
        val body = buildMessagesBody()
        controller.messages(
            mapper.writeValueAsBytes(body), "sk-prim-key", "Bearer sk-fallback", null, null, null, mockRequest(),
        mockResponse(),
        )

        assertEquals("sk-prim-key", fakeUpstream.lastReceivedApiKey)
        // Bearer is also extracted but apiKey path still wins for ConversationKey namespace.
        // Both forwarded → upstream gets x-api-key OR Authorization, here both available.
        assertEquals("sk-fallback", fakeUpstream.lastReceivedBearerToken)
    }

    // --- Test 2: body without messages → 400 ---
    @Test
    @DisplayName("body without messages array returns 400")
    fun bodyWithoutMessages() {
        val body = mapper.createObjectNode().apply { put("model", "claude-test") }
        val response = controller.messages(mapper.writeValueAsBytes(body), "sk-test-key", null, null, null, null, mockRequest(), mockResponse())

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
            router = fakeRouter,
            sseGuard = sseGuard,
            rateLimiter = blockingRateLimiter,
            costTable = costTable,
            audit = fakeAudit,
            redactionEvents = fakeRedactionEvents,
            costs = fakeCosts,
        )

        val body = buildMessagesBody()
        val response = blockingController.messages(mapper.writeValueAsBytes(body), "sk-test-key", null, null, null, null, mockRequest(), mockResponse())

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

        val response = controller.messages(mapper.writeValueAsBytes(body), "sk-ant-test123456", null, null, null, null, mockRequest(), mockResponse())

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
            router = fakeRouter,
            sseGuard = sseGuard,
            rateLimiter = AllowAllRateLimiter(),
            costTable = costTable,
            audit = fakeAudit,
            redactionEvents = fakeRedactionEvents,
            costs = fakeCosts,
        )

        val body = buildMessagesBody(userText = "My email is secret@example.com")
        val response = blockingController.messages(mapper.writeValueAsBytes(body), "sk-ant-test123456", null, null, null, null, mockRequest(), mockResponse())

        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY.value(), response.statusCode.value())
        val bodyStr = mapper.writeValueAsString(response.body)
        assertTrue(bodyStr.contains("input_blocked"), "body should contain input_blocked, got: $bodyStr")
        assertTrue(fakeAudit.logs.any { it.status == "BLOCKED" })
    }

    // --- Test 6: stream=true happy path ---
    @Test
    @DisplayName("stream=true writes SSE directly to HttpServletResponse, audit recorded after finalize")
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

        val resp = mockResponse()
        val ret = controller.messages(mapper.writeValueAsBytes(body), "sk-ant-test123456", null, null, null, null, mockRequest(), resp)

        // Streaming case returns null; data and headers are on the response object.
        assertEquals(null, ret)
        assertEquals(HttpStatus.OK.value(), resp.status)
        assertEquals("text/event-stream;charset=UTF-8", resp.getHeader("Content-Type"))
        assertTrue(resp.contentAsByteArray.isNotEmpty(), "streaming output should not be empty")

        // After streaming, audit and cost should be inserted
        assertTrue(fakeAudit.logs.any { it.status == "OK" }, "audit should have OK entry after stream completes")
        assertTrue(fakeCosts.records.isNotEmpty(), "cost record should be inserted after stream")
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
        val response = controller.messages(mapper.writeValueAsBytes(body), "sk-ant-test-key", null, null, null, null, mockRequest(), mockResponse())

        assertEquals(HttpStatus.UNAUTHORIZED.value(), response.statusCode.value())
        assertTrue(fakeAudit.logs.any { it.status == "ERROR" }, "audit should have ERROR entry")
    }

    // --- Test 8 (qa HIGH): UpstreamResult.Failure → 502 BAD_GATEWAY ---
    @Test
    @DisplayName("upstreamFailure_nonStream_returns502: IOException from upstream → 502")
    fun upstreamFailureNonStreamReturns502() {
        fakeUpstream.nonStreamResponse = { _ -> UpstreamResult.Failure(java.io.IOException("connection refused")) }

        val body = buildMessagesBody(userText = "Hello", stream = false)
        val response = controller.messages(mapper.writeValueAsBytes(body), "sk-ant-test-key", null, null, null, null, mockRequest(), mockResponse())

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
        val response = controller.messages(mapper.writeValueAsBytes(body), "sk-ant-test-key", null, null, null, null, mockRequest(), mockResponse())

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE.value(), response.statusCode.value())
        assertTrue(fakeAudit.logs.any { it.status == "ERROR" }, "audit should have ERROR entry")
    }

    // --- Test 11: model routing → custom upstream base-url ---
    @Test
    @DisplayName("modelRoutesToCustomUpstream: qwen-* routes to http://qwen.local, claude-* stays on default")
    fun modelRoutesToCustomUpstream() {
        val qwenBaseUrl = "http://qwen.local"
        val defaultUrl = "https://api.anthropic.com"
        val routesProps = AnthropicRoutesProperties(
            routes = listOf(
                AnthropicRoutesProperties.RouteConfig(pattern = "qwen-*", baseUrl = qwenBaseUrl),
            ),
        )
        val routerWithQwen = ModelEndpointRouter(defaultUrl, routesProps)
        val routerUpstream = FakeAnthropicUpstreamClient(mapper, routerWithQwen)
        routerUpstream.validateBaseUrl() // no-op, just ensure it doesn't throw

        val routingController = AnthropicMessagesController(
            mapper = mapper,
            registry = registry,
            inputGuard = inputGuard,
            outputGuard = outputGuard,
            redactionEngine = engine,
            upstream = routerUpstream,
            router = routerWithQwen,
            sseGuard = sseGuard,
            rateLimiter = AllowAllRateLimiter(),
            costTable = costTable,
            audit = fakeAudit,
            redactionEvents = fakeRedactionEvents,
            costs = fakeCosts,
        )

        // qwen-3.6-35b → should route to qwen.local
        val qwenBody = buildMessagesBody(model = "qwen-3.6-35b")
        routingController.messages(mapper.writeValueAsBytes(qwenBody), "sk-test-key", null, null, null, null, mockRequest(), mockResponse())
        assertEquals(qwenBaseUrl, routerUpstream.lastReceivedBaseUrl, "qwen model should route to qwen.local")

        // claude-sonnet → should route to default
        val claudeBody = buildMessagesBody(model = "claude-sonnet-4-6")
        routingController.messages(mapper.writeValueAsBytes(claudeBody), "sk-test-key", null, null, null, null, mockRequest(), mockResponse())
        assertEquals(defaultUrl, routerUpstream.lastReceivedBaseUrl, "claude model should route to default upstream")
    }

    // --- Test 12: image block with base64 source passes through byte-equal ---
    @Test
    @DisplayName("imageBlockPassesThroughUntouched: base64 image block arrives upstream unchanged")
    fun imageBlockPassesThroughUntouched() {
        val fakeBase64 = "AAAA"
        val root = mapper.createObjectNode()
        root.put("model", "claude-sonnet-4-6")
        root.put("stream", false)
        val messages = mapper.createArrayNode()
        val msg = mapper.createObjectNode()
        msg.put("role", "user")
        val content = mapper.createArrayNode()

        val imageBlock = mapper.createObjectNode()
        imageBlock.put("type", "image")
        val source = mapper.createObjectNode()
        source.put("type", "base64")
        source.put("media_type", "image/jpeg")
        source.put("data", fakeBase64)
        imageBlock.set<ObjectNode>("source", source)
        content.add(imageBlock)

        val textBlock = mapper.createObjectNode()
        textBlock.put("type", "text")
        textBlock.put("text", "describe this")
        content.add(textBlock)

        msg.set<ArrayNode>("content", content)
        messages.add(msg)
        root.set<ArrayNode>("messages", messages)

        val response = controller.messages(mapper.writeValueAsBytes(root), "sk-ant-test123456", null, null, null, null, mockRequest(), mockResponse())
        assertEquals(HttpStatus.OK.value(), response.statusCode.value())

        val upstreamBody = fakeUpstream.lastReceivedBody
        assertNotNull(upstreamBody)
        val upstreamMessages = upstreamBody!!["messages"] as? ArrayNode
        assertNotNull(upstreamMessages)
        val upstreamContent = upstreamMessages!![0]["content"] as? ArrayNode
        assertNotNull(upstreamContent)

        val upstreamImage = upstreamContent!!.firstOrNull { it["type"]?.asText() == "image" }
        assertNotNull(upstreamImage, "image block should be present in upstream request")
        assertEquals("base64", upstreamImage!!["source"]["type"].asText())
        assertEquals("image/jpeg", upstreamImage["source"]["media_type"].asText())
        assertEquals(fakeBase64, upstreamImage["source"]["data"].asText())

        // text block should also be present unmodified
        val upstreamText = upstreamContent.firstOrNull { it["type"]?.asText() == "text" }
        assertNotNull(upstreamText, "text block should be present in upstream request")
        assertEquals("describe this", upstreamText!!["text"].asText())
    }

    // --- Test 13: image block with URL source passes through byte-equal ---
    @Test
    @DisplayName("imageBlockBase64Url: url-form image source passes through unchanged")
    fun imageBlockBase64Url() {
        val imageUrl = "https://example.com/cat.jpg"
        val root = mapper.createObjectNode()
        root.put("model", "claude-sonnet-4-6")
        root.put("stream", false)
        val messages = mapper.createArrayNode()
        val msg = mapper.createObjectNode()
        msg.put("role", "user")
        val content = mapper.createArrayNode()

        val imageBlock = mapper.createObjectNode()
        imageBlock.put("type", "image")
        val source = mapper.createObjectNode()
        source.put("type", "url")
        source.put("url", imageUrl)
        imageBlock.set<ObjectNode>("source", source)
        content.add(imageBlock)

        msg.set<ArrayNode>("content", content)
        messages.add(msg)
        root.set<ArrayNode>("messages", messages)

        val response = controller.messages(mapper.writeValueAsBytes(root), "sk-ant-test123456", null, null, null, null, mockRequest(), mockResponse())
        assertEquals(HttpStatus.OK.value(), response.statusCode.value())

        val upstreamBody = fakeUpstream.lastReceivedBody
        assertNotNull(upstreamBody)
        val upstreamMessages = upstreamBody!!["messages"] as? ArrayNode
        val upstreamContent = upstreamMessages!![0]["content"] as? ArrayNode
        assertNotNull(upstreamContent)

        val upstreamImage = upstreamContent!!.firstOrNull { it["type"]?.asText() == "image" }
        assertNotNull(upstreamImage, "image block should be present in upstream request")
        assertEquals("url", upstreamImage!!["source"]["type"].asText())
        assertEquals(imageUrl, upstreamImage["source"]["url"].asText())
    }

    // --- Test 14: document block passes through byte-equal ---
    @Test
    @DisplayName("documentBlockPassesThrough: base64 document block arrives upstream unchanged")
    fun documentBlockPassesThrough() {
        val fakeBase64 = "JVBERi0xLjQ="
        val root = mapper.createObjectNode()
        root.put("model", "claude-sonnet-4-6")
        root.put("stream", false)
        val messages = mapper.createArrayNode()
        val msg = mapper.createObjectNode()
        msg.put("role", "user")
        val content = mapper.createArrayNode()

        val docBlock = mapper.createObjectNode()
        docBlock.put("type", "document")
        val source = mapper.createObjectNode()
        source.put("type", "base64")
        source.put("media_type", "application/pdf")
        source.put("data", fakeBase64)
        docBlock.set<ObjectNode>("source", source)
        content.add(docBlock)

        msg.set<ArrayNode>("content", content)
        messages.add(msg)
        root.set<ArrayNode>("messages", messages)

        val response = controller.messages(mapper.writeValueAsBytes(root), "sk-ant-test123456", null, null, null, null, mockRequest(), mockResponse())
        assertEquals(HttpStatus.OK.value(), response.statusCode.value())

        val upstreamBody = fakeUpstream.lastReceivedBody
        assertNotNull(upstreamBody)
        val upstreamMessages = upstreamBody!!["messages"] as? ArrayNode
        val upstreamContent = upstreamMessages!![0]["content"] as? ArrayNode
        assertNotNull(upstreamContent)

        val upstreamDoc = upstreamContent!!.firstOrNull { it["type"]?.asText() == "document" }
        assertNotNull(upstreamDoc, "document block should be present in upstream request")
        assertEquals("base64", upstreamDoc!!["source"]["type"].asText())
        assertEquals("application/pdf", upstreamDoc["source"]["media_type"].asText())
        assertEquals(fakeBase64, upstreamDoc["source"]["data"].asText())
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

    // --- redaction notice injection: append-at-end behavior ---

    private fun buildMessagesBodyWithSystem(
        userText: String,
        systemBlocks: List<Pair<String, Boolean>>, // (text, hasCacheControl)
    ): JsonNode {
        val root = mapper.createObjectNode()
        root.put("model", "claude-sonnet-4-6")
        root.put("stream", false)
        val systemArr = mapper.createArrayNode()
        for ((text, cc) in systemBlocks) {
            val block = mapper.createObjectNode()
            block.put("type", "text")
            block.put("text", text)
            if (cc) {
                val cache = mapper.createObjectNode()
                cache.put("type", "ephemeral")
                block.set<ObjectNode>("cache_control", cache)
            }
            systemArr.add(block)
        }
        root.set<ArrayNode>("system", systemArr)
        val messages = mapper.createArrayNode()
        val msg = mapper.createObjectNode()
        msg.put("role", "user")
        msg.put("content", userText)
        messages.add(msg)
        root.set<ArrayNode>("messages", messages)
        return root
    }

    @Test
    @DisplayName("redaction notice is APPENDED at end of system[]; existing blocks (with cache_control) untouched")
    fun redactionNoticeAppendedAtEnd() {
        val body = buildMessagesBodyWithSystem(
            userText = "My email is leak@example.com please process",
            systemBlocks = listOf(
                "You are a helpful assistant." to false,
                "Long stable instructions block (cached)..." to true, // cache breakpoint here
            ),
        )

        fakeUpstream.nonStreamResponse = { _ ->
            val resp = mapper.createObjectNode()
            val content = mapper.createArrayNode()
            val tb = mapper.createObjectNode()
            tb.put("type", "text"); tb.put("text", "ok")
            content.add(tb)
            resp.set<ArrayNode>("content", content)
            resp.put("model", "claude-sonnet-4-6")
            val u = mapper.createObjectNode()
            u.put("input_tokens", 10); u.put("output_tokens", 5)
            resp.set<ObjectNode>("usage", u)
            UpstreamResult.Ok(resp)
        }

        val response = controller.messages(
            mapper.writeValueAsBytes(body), "sk-ant-test-append", null, null, null, null,
            mockRequest(), mockResponse(),
        )
        assertEquals(HttpStatus.OK.value(), response.statusCode.value())

        val upstream = fakeUpstream.lastReceivedBody
        assertNotNull(upstream)
        val sys = upstream!!["system"] as? ArrayNode
        assertNotNull(sys, "system must remain an array")
        assertEquals(3, sys!!.size(), "must have 2 original blocks + 1 appended notice")

        // First two blocks preserved byte-identically (incl. cache_control on #2).
        assertEquals("You are a helpful assistant.", sys[0]["text"].asText())
        assertFalse(sys[0].has("cache_control"), "first block must NOT gain cache_control")
        assertEquals("Long stable instructions block (cached)...", sys[1]["text"].asText())
        assertNotNull(sys[1]["cache_control"], "second block must keep its cache_control")
        assertEquals("ephemeral", sys[1]["cache_control"]["type"].asText())

        // Notice is the LAST block (appended), without its own cache_control.
        val notice = sys[2]
        assertEquals("text", notice["type"].asText())
        assertTrue(
            notice["text"].asText().contains("[GATEWAY NOTICE]"),
            "last block must be the gateway redaction notice, got: ${notice["text"].asText()}",
        )
        assertFalse(notice.has("cache_control"), "notice block must NOT carry cache_control")
    }

    @Test
    @DisplayName("no PII → no notice injected, system[] preserved as-is")
    fun noPiiNoNoticeInjection() {
        val body = buildMessagesBodyWithSystem(
            userText = "What is 2+2?",
            systemBlocks = listOf("You are a helpful assistant." to true),
        )

        fakeUpstream.nonStreamResponse = { _ ->
            val resp = mapper.createObjectNode()
            val content = mapper.createArrayNode()
            val tb = mapper.createObjectNode()
            tb.put("type", "text"); tb.put("text", "4")
            content.add(tb)
            resp.set<ArrayNode>("content", content)
            resp.put("model", "claude-sonnet-4-6")
            val u = mapper.createObjectNode()
            u.put("input_tokens", 5); u.put("output_tokens", 1)
            resp.set<ObjectNode>("usage", u)
            UpstreamResult.Ok(resp)
        }

        val response = controller.messages(
            mapper.writeValueAsBytes(body), "sk-ant-test-clean", null, null, null, null,
            mockRequest(), mockResponse(),
        )
        assertEquals(HttpStatus.OK.value(), response.statusCode.value())

        val upstream = fakeUpstream.lastReceivedBody
        assertNotNull(upstream)
        val sys = upstream!!["system"] as? ArrayNode
        assertNotNull(sys)
        assertEquals(1, sys!!.size(), "no extra block must be appended on clean input")
        assertEquals("You are a helpful assistant.", sys[0]["text"].asText())
        assertFalse(
            mapper.writeValueAsString(sys).contains("[GATEWAY NOTICE]"),
            "notice text must be absent in upstream system",
        )
    }
}

// --- Fake implementations ---

private class FakeAnthropicUpstreamClient(
    private val mapper: ObjectMapper,
    router: ModelEndpointRouter,
) : AnthropicUpstreamClient(
    mapper,
    "https://api.anthropic.com",
    "api.anthropic.com",
    router,
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
    var lastReceivedBaseUrl: String? = null

    var lastReceivedApiKey: String? = null
    var lastReceivedBearerToken: String? = null

    var lastReceivedSessionId: String? = null
    var lastReceivedPassthrough: Map<String, List<String>>? = null

    override fun send(
        bodyBytes: ByteArray,
        apiKey: String?,
        bearerToken: String?,
        anthropicVersion: String,
        beta: String?,
        baseUrl: String,
        sessionId: String?,
        passthroughHeaders: Map<String, List<String>>,
        upstreamQuery: String?,
    ): UpstreamResult {
        val body = mapper.readTree(bodyBytes)
        lastReceivedBody = body
        lastReceivedBaseUrl = baseUrl
        lastReceivedApiKey = apiKey
        lastReceivedBearerToken = bearerToken
        lastReceivedSessionId = sessionId
        lastReceivedPassthrough = passthroughHeaders
        return nonStreamResponse(body)
    }

    override fun sendStream(
        bodyBytes: ByteArray,
        apiKey: String?,
        bearerToken: String?,
        anthropicVersion: String,
        beta: String?,
        baseUrl: String,
        sessionId: String?,
        passthroughHeaders: Map<String, List<String>>,
        upstreamQuery: String?,
    ): UpstreamStreamResult {
        val body = mapper.readTree(bodyBytes)
        lastReceivedBody = body
        lastReceivedBaseUrl = baseUrl
        lastReceivedApiKey = apiKey
        lastReceivedBearerToken = bearerToken
        lastReceivedSessionId = sessionId
        lastReceivedPassthrough = passthroughHeaders
        return streamResponse
    }
}

private class FakeAuditRepository : AuditRepository(org.springframework.jdbc.core.JdbcTemplate()) {
    val logs = mutableListOf<AuditLog>()

    override fun insert(audit: AuditLog) {
        logs.add(audit)
    }

    override fun migrate() { /* no-op */ }

    override fun recent(limit: Int, endpointType: String?, modelFilter: String?): List<AuditLog> {
        var result = logs.asSequence()
        if (endpointType != null) result = result.filter { it.endpointType == endpointType }
        if (modelFilter != null) {
            val regex = Regex(modelFilter.replace("*", ".*").replace("?", "."))
            result = result.filter { it.model?.let { m -> regex.matches(m) } ?: false }
        }
        return result.toList().takeLast(limit)
    }

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
