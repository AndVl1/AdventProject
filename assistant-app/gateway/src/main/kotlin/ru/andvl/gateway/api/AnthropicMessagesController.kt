package ru.andvl.gateway.api

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import ru.andvl.gateway.cost.CostTable
import ru.andvl.gateway.guard.ConversationRegistry
import ru.andvl.gateway.guard.Finding
import ru.andvl.gateway.guard.InputGuard
import ru.andvl.gateway.guard.OutputGuard
import ru.andvl.gateway.guard.RedactionEngine
import ru.andvl.gateway.guard.RedactionMap
import ru.andvl.gateway.llm.AnthropicUpstreamClient
import ru.andvl.gateway.llm.ModelEndpointRouter
import ru.andvl.gateway.llm.UpstreamResult
import ru.andvl.gateway.llm.UpstreamStreamResult
import ru.andvl.gateway.persistence.AuditLog
import ru.andvl.gateway.persistence.AuditRepository
import ru.andvl.gateway.persistence.CostRecord
import ru.andvl.gateway.persistence.CostRepository
import ru.andvl.gateway.persistence.RedactionEvent
import ru.andvl.gateway.persistence.RedactionEventRepository
import ru.andvl.gateway.ratelimit.RateLimiter
import java.net.URI
import java.util.UUID

// Маршруты:
//   /v1/messages          — стандартный Anthropic путь
//   /api/v1/messages      — алиас
@RestController
@RequestMapping(path = ["/v1", "/api/v1"])
class AnthropicMessagesController(
    private val mapper: ObjectMapper,
    private val registry: ConversationRegistry,
    private val inputGuard: InputGuard,
    private val outputGuard: OutputGuard,
    private val redactionEngine: RedactionEngine,
    private val upstream: AnthropicUpstreamClient,
    private val router: ModelEndpointRouter,
    private val sseGuard: SseGuardStream,
    private val rateLimiter: RateLimiter,
    private val costTable: CostTable,
    private val audit: AuditRepository,
    private val redactionEvents: RedactionEventRepository,
    private val costs: CostRepository,
) {

    private val log = LoggerFactory.getLogger(AnthropicMessagesController::class.java)

    // Дропаем при passthrough: hop-by-hop, наши внутренние, и те которые мы сами выставляем.
    // Всё остальное — User-Agent, X-Stainless-*, X-App-*, Accept-* и пр. — Anthropic нужно
    // для профилирования клиента (без них режут как «unknown proxy» → 429 без retry-after).
    private val DROP_PASSTHROUGH_HEADERS = setOf(
        "host", "content-length", "connection", "transfer-encoding", "keep-alive",
        "proxy-connection", "proxy-authenticate", "proxy-authorization", "te", "trailer", "upgrade",
        "expect", "content-type",
        // Если форвардить клиентский Accept-Encoding (gzip,br,zstd), OkHttp считает что
        // декомпрессия — забота клиента, и отдаёт тело как есть. Парсер апстрим-ответа
        // получает gzip-байты вместо JSON. Дроп → OkHttp сам ставит `gzip` и автоматом
        // декодирует на приёме.
        "accept-encoding",
        "x-conversation-id",
        "x-forwarded-for", "x-forwarded-proto", "x-forwarded-host", "x-forwarded-port", "x-real-ip",
        "x-api-key", "authorization",
        "anthropic-version", "anthropic-beta",
        "x-claude-code-session-id",
    )

    private fun collectPassthroughHeaders(req: HttpServletRequest): Map<String, List<String>> {
        val out = LinkedHashMap<String, MutableList<String>>()
        val names = req.headerNames ?: return emptyMap()
        while (names.hasMoreElements()) {
            val name = names.nextElement()
            if (name.lowercase() in DROP_PASSTHROUGH_HEADERS) continue
            val values = req.getHeaders(name)?.toList().orEmpty()
            if (values.isEmpty()) continue
            out.getOrPut(name) { mutableListOf() }.addAll(values)
        }
        return out
    }

    private val REDACTION_NOTICE = """
        [GATEWAY NOTICE] Some user-supplied values were replaced with placeholders of the form
        `REDACTED_<TYPE>_<N>` (e.g. `REDACTED_OPENAI_KEY_1`, `REDACTED_EMAIL_2`). These
        placeholders represent real secrets/PII that the user provided. Treat them as opaque
        tokens — do NOT try to guess, generate, or "decrypt" them, and do not warn the user
        about them. Simply use the placeholder when you need to refer back to the value;
        the gateway will swap it back to the original before the user sees your reply.
    """.trimIndent()

    @PostMapping(path = ["/messages"], consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun messages(
        @RequestBody rawBodyBytes: ByteArray,
        @RequestHeader(value = "x-api-key", required = false) apiKey: String?,
        @RequestHeader(value = "Authorization", required = false) authorization: String?,
        @RequestHeader(value = "anthropic-version", required = false) version: String?,
        @RequestHeader(value = "anthropic-beta", required = false) betaSingle: String?,
        @RequestHeader(value = "X-Conversation-Id", required = false) conversationIdHeader: String?,
        request: HttpServletRequest,
        response: HttpServletResponse,
    ): Any? {
        // anthropic-beta может приходить multi-value (несколько одноимённых заголовков
        // ИЛИ comma-joined). Spring при @RequestHeader String отдаёт первый — теряем хвост
        // (например `prompt-caching-2024-07-31`). Собираем все значения из request и склеиваем.
        val beta: String? = run {
            val multi = request.getHeaders("anthropic-beta")?.toList().orEmpty()
            val joined = multi.flatMap { it.split(',') }.map { it.trim() }.filter { it.isNotBlank() }
            if (joined.isNotEmpty()) joined.joinToString(",") else betaSingle?.takeIf { it.isNotBlank() }
        }
        // Доки Anthropic: X-Claude-Code-Session-Id шлёт каждый запрос CC. Форвардим в апстрим.
        val claudeSessionId: String? = request.getHeader("X-Claude-Code-Session-Id")?.takeIf { it.isNotBlank() }
        // Все клиентские headers кроме hop-by-hop / нашего overlay — pass-through в апстрим.
        val passthrough = collectPassthroughHeaders(request)
        val started = System.currentTimeMillis()
        val ip = clientIp(request)

        // Anthropic native API uses x-api-key. Claude Code OAuth login (and some clients) use
        // Authorization: Bearer <token>. Accept either, forward the same scheme upstream verbatim.
        val effectiveApiKey = apiKey?.takeIf { it.isNotBlank() }
        val bearerToken = authorization
            ?.takeIf { it.startsWith("Bearer ", ignoreCase = true) }
            ?.substring(7)
            ?.trim()
            ?.takeIf { it.isNotBlank() }
        val authSecret = effectiveApiKey ?: bearerToken
        if (authSecret == null) {
            audit.insert(
                AuditLog(
                    conversationId = null, clientIp = ip, model = null,
                    requestText = null, responseText = null,
                    status = "BLOCKED", blockReason = "missing api key",
                    inputFindings = null, outputFindings = null,
                    latencyMs = System.currentTimeMillis() - started,
                    endpointType = "anthropic",
                ),
            )
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(errorBody("authentication_error", "x-api-key or Authorization: Bearer header required"))
        }

        val effectiveVersion = version?.takeIf { it.isNotBlank() } ?: "2023-06-01"
        val effectiveBeta = beta?.takeIf { it.isNotBlank() }

        // SEC-001: normalize client-supplied id and bind registry key to api-key hash
        // to prevent cross-conversation secret hijack
        val normalizedClientId = ConversationKey.normalize(conversationIdHeader) ?: UUID.randomUUID().toString()
        val conversationId = ConversationKey.registryKey(authSecret, normalizedClientId)

        // Парсим только для guard. Если guard ничего не модифицирует — отправляем оригинальные
        // байты в апстрим (byte-identity). Любая ре-сериализация Jackson меняет форматирование
        // (порядок keys, escape unicode, числа) → Anthropic prompt-cache prefix mismatch →
        // каждый запрос идёт как полный input → ITPM blow → 429.
        val body: JsonNode = runCatching { mapper.readTree(rawBodyBytes) }.getOrElse {
            return ResponseEntity.badRequest()
                .body(errorBody("invalid_request_error", "invalid JSON: ${it.message}"))
        }

        // Rate limit check
        val rl = rateLimiter.check(ip)
        if (!rl.allowed) {
            audit.insert(
                AuditLog(
                    conversationId = conversationId, clientIp = ip, model = body["model"]?.asText(),
                    requestText = null, responseText = null,
                    status = "RATE_LIMITED", blockReason = "rate limit ${rateLimiter.limitPerMinute()}/min",
                    inputFindings = null, outputFindings = null, latencyMs = 0,
                    endpointType = "anthropic",
                ),
            )
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header("X-RateLimit-Reset-Ms", rl.resetMs.toString())
                .body(errorBody("rate_limit_exceeded", "Too many requests. Reset in ${rl.resetMs}ms."))
        }
        if (!body.isObject) {
            return ResponseEntity.badRequest()
                .body(errorBody("invalid_request_error", "JSON object expected"))
        }

        val root = body as ObjectNode
        val model = root["model"]?.asText() ?: "unknown"
        @Suppress("UNCHECKED_CAST")
        val messages = root["messages"] as? ArrayNode
            ?: return ResponseEntity.badRequest()
                .body(errorBody("invalid_request_error", "messages[] required"))

        val map = registry.forConversation(conversationId)

        // Walk and guard input
        val walkResult = walkAndGuardInput(root, map, messages)
        val allFindings = walkResult.allFindings
        val blockReason = walkResult.blockReason
        val redactedRequestText = walkResult.redactedRequestText
        val originalSystemPrompt = walkResult.originalSystemPrompt

        // Persist redaction events
        for (f in allFindings) {
            redactionEvents.insert(
                RedactionEvent(
                    conversationId = conversationId, direction = "INPUT",
                    ruleName = f.ruleName, placeholder = f.placeholder, originalHash = f.originalHash,
                ),
            )
        }

        if (blockReason != null) {
            audit.insert(
                AuditLog(
                    conversationId = conversationId, clientIp = ip, model = model,
                    requestText = redactedRequestText, responseText = null,
                    status = "BLOCKED", blockReason = blockReason,
                    inputFindings = mapper.writeValueAsString(allFindings.groupingBy { it.ruleName }.eachCount()),
                    outputFindings = null, latencyMs = System.currentTimeMillis() - started,
                    endpointType = "anthropic",
                ),
            )
            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                .body(errorBody("input_blocked", blockReason))
        }

        // НЕ инжектим redaction notice в `system`: Anthropic prompt-cache хэширует ПРЕФИКС
        // до cache_control breakpoint. Любая вставка перед существующим system-блоком ломает
        // prefix-hash → каждый запрос как cache-miss → весь input (60K+ tok) идёт в ITPM →
        // load-shed 429 без retry-after. Достаточно in-place маскировки в walkResult: модель
        // видит REDACTED_<TYPE>_<N> placeholder'ы и без явной инструкции корректно их трактует
        // (так же делает референсный Python proxy, который не 429ит).
        val modifiedRoot = walkResult.bodyMutated

        // Byte-identity для апстрима: если guard ничего не тронул — шлём оригинальные байты.
        // Иначе Jackson re-serialize ломает Anthropic prompt-cache (порядок ключей, escape,
        // числа) → каждый запрос как полный input → ITPM → 429.
        val upstreamBodyBytes: ByteArray = if (modifiedRoot) {
            mapper.writeValueAsBytes(root)
        } else {
            rawBodyBytes
        }
        val upstreamRequestJson = String(upstreamBodyBytes)
        val stream = root["stream"]?.asBoolean(false) ?: false
        val routedBaseUrl = router.resolve(root["model"]?.asText())
        // Direct CC sends `?beta=true` on /v1/messages; without it Anthropic load-sheds (429).
        val upstreamQuery: String? = request.queryString?.takeIf { it.isNotBlank() }

        // CC уже шлёт нужные caching beta (`prompt-caching-scope-2026-01-05` и пр.).
        // Пробрасываем effectiveBeta верватим — без augmentation, чтобы не добавить
        // конфликтующие/устаревшие beta'ы и не сломать routing на edge Anthropic.
        val finalBeta = effectiveBeta

        return if (!stream) {
            handleNonStream(
                root, upstreamBodyBytes, effectiveApiKey, bearerToken, effectiveVersion, finalBeta,
                conversationId, normalizedClientId, ip, model, map, originalSystemPrompt,
                redactedRequestText, allFindings, started, upstreamRequestJson, routedBaseUrl,
                claudeSessionId, passthrough, upstreamQuery,
            )
        } else {
            handleStream(
                root, upstreamBodyBytes, effectiveApiKey, bearerToken, effectiveVersion, finalBeta,
                conversationId, normalizedClientId, ip, model, map,
                redactedRequestText, allFindings, started, upstreamRequestJson, routedBaseUrl,
                response, claudeSessionId, passthrough, upstreamQuery,
            )
        }
    }

    private fun handleNonStream(
        root: ObjectNode,
        bodyBytes: ByteArray,
        apiKey: String?,
        bearerToken: String?,
        version: String,
        beta: String?,
        conversationId: String,
        clientConversationId: String,
        ip: String,
        model: String,
        map: RedactionMap,
        originalSystemPrompt: String?,
        redactedRequestText: String,
        allFindings: List<Finding>,
        started: Long,
        upstreamRequestJson: String,
        routedBaseUrl: String,
        claudeSessionId: String?,
        passthroughHeaders: Map<String, List<String>>,
        upstreamQuery: String?,
    ): ResponseEntity<*> {
        val routedHost = runCatching { URI.create(routedBaseUrl).host }.getOrNull() ?: routedBaseUrl
        return when (val result = upstream.send(
            bodyBytes, apiKey, bearerToken, version, beta, routedBaseUrl, claudeSessionId, passthroughHeaders, upstreamQuery,
        )) {
            is UpstreamResult.Failure -> {
                audit.insert(
                    AuditLog(
                        conversationId = conversationId, clientIp = ip, model = model,
                        requestText = redactedRequestText, responseText = null,
                        status = "ERROR", blockReason = "upstream call failed: ${result.cause.message}",
                        inputFindings = mapper.writeValueAsString(allFindings.groupingBy { it.ruleName }.eachCount()),
                        outputFindings = null, latencyMs = System.currentTimeMillis() - started,
                        upstreamRequestJson = upstreamRequestJson, upstreamResponseJson = null,
                        endpointType = "anthropic", routedUpstream = routedHost,
                    ),
                )
                ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .body(errorBody("upstream_error", "upstream call failed: ${result.cause.message}"))
            }
            is UpstreamResult.Error -> {
                val upstreamErrType = result.errorJson?.get("error")?.get("type")?.asText()
                val upstreamErrMsg = result.errorJson?.get("error")?.get("message")?.asText()
                val reason = buildString {
                    append("upstream_${result.status}")
                    if (!upstreamErrType.isNullOrBlank()) append(":").append(upstreamErrType)
                    if (!upstreamErrMsg.isNullOrBlank()) append(" — ").append(upstreamErrMsg.take(200))
                }
                audit.insert(
                    AuditLog(
                        conversationId = conversationId, clientIp = ip, model = model,
                        requestText = redactedRequestText, responseText = null,
                        status = "ERROR", blockReason = reason,
                        inputFindings = mapper.writeValueAsString(allFindings.groupingBy { it.ruleName }.eachCount()),
                        outputFindings = null, latencyMs = System.currentTimeMillis() - started,
                        upstreamRequestJson = upstreamRequestJson,
                        upstreamResponseJson = result.rawBody,
                        endpointType = "anthropic", routedUpstream = routedHost,
                    ),
                )
                val builder = ResponseEntity.status(result.status)
                // Форвардим upstream rate-limit headers клиенту — без retry-after и
                // anthropic-ratelimit-* CC не знает когда retry'ить и колотит лимит дальше.
                for ((name, value) in result.headers) {
                    if (value.isNotBlank()) builder.header(name, value)
                }
                builder.body(result.errorJson ?: errorBody("upstream_error", "status=${result.status}"))
            }
            is UpstreamResult.Ok -> {
                val json = result.json
                val upstreamSnapshot = json.deepCopy<JsonNode>()

                var loggableTextAccum = StringBuilder()
                var hallucinatedTotal = 0
                var leak = false
                var suspiciousUrls = emptyList<String>()

                // Walk content blocks in response
                val content = json["content"] as? ArrayNode
                val snapshotContent = upstreamSnapshot["content"] as? ArrayNode

                if (content != null) {
                    for (i in 0 until content.size()) {
                        val block = content.get(i) as? ObjectNode ?: continue
                        val blockType = block["type"]?.asText() ?: continue
                        when (blockType) {
                            "text" -> {
                                val rawText = block["text"]?.asText() ?: continue
                                val out = outputGuard.process(rawText, map, originalSystemPrompt)
                                block.put("text", out.finalText)
                                (snapshotContent?.get(i) as? ObjectNode)?.put("text", out.loggableText)
                                loggableTextAccum.append(out.loggableText)
                                hallucinatedTotal += out.hallucinatedCount
                                if (out.systemPromptLeak) leak = true
                                if (out.suspiciousUrls.isNotEmpty()) suspiciousUrls = out.suspiciousUrls
                            }
                            "tool_use" -> {
                                val inputNode = block["input"]
                                if (inputNode != null) {
                                    val serialized = mapper.writeValueAsString(inputNode)
                                    val reversed = map.reverse(serialized)
                                    if (reversed != serialized) {
                                        runCatching {
                                            val parsed = mapper.readTree(reversed)
                                            block.set<JsonNode>("input", parsed)
                                        }.onFailure {
                                            log.warn("tool_use input reverse parse failed: {}", it.message)
                                            block.put("input", reversed)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                val upstreamResponseJson = mapper.writeValueAsString(upstreamSnapshot)

                // Cost tracking
                val usage = json["usage"]
                var promptTok = 0
                var completionTok = 0
                if (usage != null && usage.isObject) {
                    promptTok = usage["input_tokens"]?.asInt() ?: 0
                    completionTok = usage["output_tokens"]?.asInt() ?: 0
                    val totalTok = promptTok + completionTok
                    val cost = costTable.estimateUsd(model, promptTok, completionTok)
                    costs.insert(
                        CostRecord(
                            conversationId = conversationId, model = model,
                            promptTokens = promptTok, completionTokens = completionTok,
                            totalTokens = totalTok, costUsd = cost,
                        ),
                    )
                }

                val findingsSummary = buildMap<String, Any> {
                    put("input", allFindings.groupingBy { it.ruleName }.eachCount())
                    put("output_hallucinated", hallucinatedTotal)
                    put("system_prompt_leak", leak)
                    put("suspicious_urls", suspiciousUrls)
                }

                audit.insert(
                    AuditLog(
                        conversationId = conversationId, clientIp = ip, model = model,
                        requestText = redactedRequestText,
                        responseText = loggableTextAccum.toString(),
                        status = "OK", blockReason = null,
                        inputFindings = mapper.writeValueAsString(allFindings.groupingBy { it.ruleName }.eachCount()),
                        outputFindings = mapper.writeValueAsString(findingsSummary),
                        latencyMs = System.currentTimeMillis() - started,
                        upstreamRequestJson = upstreamRequestJson,
                        upstreamResponseJson = upstreamResponseJson,
                        endpointType = "anthropic", routedUpstream = routedHost,
                        promptTokens = promptTok,
                        completionTokens = completionTok,
                        totalTokens = promptTok + completionTok,
                    ),
                )

                ResponseEntity.ok()
                    // SEC-001: return only client-facing id (no apiKeyHash exposed)
                    .header("X-Conversation-Id", clientConversationId)
                    .header("X-Gateway-Input-Redactions", allFindings.size.toString())
                    .header("X-Gateway-Output-Hallucinated", hallucinatedTotal.toString())
                    .header("X-Gateway-System-Prompt-Leak", leak.toString())
                    .header("X-Gateway-Stream", "false")
                    .header("X-Gateway-Routed-Upstream", URI.create(routedBaseUrl).host ?: routedBaseUrl)
                    .body(json)
            }
        }
    }

    private fun handleStream(
        root: ObjectNode,
        bodyBytes: ByteArray,
        apiKey: String?,
        bearerToken: String?,
        version: String,
        beta: String?,
        conversationId: String,
        clientConversationId: String,
        ip: String,
        model: String,
        map: RedactionMap,
        redactedRequestText: String,
        allFindings: List<Finding>,
        started: Long,
        upstreamRequestJson: String,
        routedBaseUrl: String,
        response: HttpServletResponse,
        claudeSessionId: String?,
        passthroughHeaders: Map<String, List<String>>,
        upstreamQuery: String?,
    ): Any? {
        val routedHost = runCatching { URI.create(routedBaseUrl).host }.getOrNull() ?: routedBaseUrl
        return when (val result = upstream.sendStream(
            bodyBytes, apiKey, bearerToken, version, beta, routedBaseUrl, claudeSessionId, passthroughHeaders, upstreamQuery,
        )) {
            is UpstreamStreamResult.Failure -> {
                audit.insert(
                    AuditLog(
                        conversationId = conversationId, clientIp = ip, model = model,
                        requestText = redactedRequestText, responseText = null,
                        status = "ERROR", blockReason = "upstream call failed: ${result.cause.message}",
                        inputFindings = mapper.writeValueAsString(allFindings.groupingBy { it.ruleName }.eachCount()),
                        outputFindings = null, latencyMs = System.currentTimeMillis() - started,
                        upstreamRequestJson = upstreamRequestJson, upstreamResponseJson = null,
                        endpointType = "anthropic", routedUpstream = routedHost,
                    ),
                )
                ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .body(errorBody("upstream_error", "upstream call failed: ${result.cause.message}"))
            }
            is UpstreamStreamResult.Error -> {
                val upstreamErrType = result.errorJson?.get("error")?.get("type")?.asText()
                val upstreamErrMsg = result.errorJson?.get("error")?.get("message")?.asText()
                val reason = buildString {
                    append("upstream_${result.status}")
                    if (!upstreamErrType.isNullOrBlank()) append(":").append(upstreamErrType)
                    if (!upstreamErrMsg.isNullOrBlank()) append(" — ").append(upstreamErrMsg.take(200))
                }
                audit.insert(
                    AuditLog(
                        conversationId = conversationId, clientIp = ip, model = model,
                        requestText = redactedRequestText, responseText = null,
                        status = "ERROR", blockReason = reason,
                        inputFindings = mapper.writeValueAsString(allFindings.groupingBy { it.ruleName }.eachCount()),
                        outputFindings = null, latencyMs = System.currentTimeMillis() - started,
                        upstreamRequestJson = upstreamRequestJson,
                        upstreamResponseJson = result.rawBody,
                        endpointType = "anthropic", routedUpstream = routedHost,
                    ),
                )
                val builder = ResponseEntity.status(result.status)
                for ((name, value) in result.headers) {
                    if (value.isNotBlank()) builder.header(name, value)
                }
                builder.body(result.errorJson ?: errorBody("upstream_error", "status=${result.status}"))
            }
            is UpstreamStreamResult.Ok -> {
                response.status = HttpStatus.OK.value()
                response.setHeader(HttpHeaders.CONTENT_TYPE, "text/event-stream;charset=UTF-8")
                response.setHeader(HttpHeaders.CACHE_CONTROL, "no-cache")
                response.setHeader("X-Accel-Buffering", "no")
                response.setHeader("X-Conversation-Id", clientConversationId)
                response.setHeader("X-Gateway-Input-Redactions", allFindings.size.toString())
                response.setHeader("X-Gateway-Stream", "true")
                response.setHeader("X-Gateway-System-Prompt-Leak", "skipped")
                response.setHeader("X-Gateway-Routed-Upstream", URI.create(routedBaseUrl).host ?: routedBaseUrl)
                response.flushBuffer()
                result.body.use { upstreamStream ->
                    sseGuard.pipe(upstreamStream, response.outputStream, map) { streamResult ->
                            val cacheInputTok = streamResult.cacheCreationInputTokens + streamResult.cacheReadInputTokens
                            val totalInputTok = streamResult.inputTokens + cacheInputTok
                            val totalTok = totalInputTok + streamResult.outputTokens
                            val cost = costTable.estimateUsd(
                                streamResult.model ?: model,
                                totalInputTok,
                                streamResult.outputTokens,
                            )
                            costs.insert(
                                CostRecord(
                                    conversationId = conversationId,
                                    model = streamResult.model ?: model,
                                    promptTokens = totalInputTok,
                                    completionTokens = streamResult.outputTokens,
                                    totalTokens = totalTok,
                                    costUsd = cost,
                                ),
                            )
                            audit.insert(
                                AuditLog(
                                    conversationId = conversationId, clientIp = ip,
                                    model = streamResult.model ?: model,
                                    requestText = redactedRequestText,
                                    responseText = streamResult.loggableText,
                                    status = "OK", blockReason = null,
                                    inputFindings = mapper.writeValueAsString(
                                        allFindings.groupingBy { it.ruleName }.eachCount(),
                                    ),
                                    outputFindings = mapper.writeValueAsString(
                                        mapOf(
                                            "output_hallucinated" to streamResult.hallucinatedCount,
                                            "system_prompt_leak" to false,
                                            "suspicious_urls" to streamResult.suspiciousUrls,
                                            "stream_notes" to streamResult.notes,
                                        ),
                                    ),
                                    latencyMs = System.currentTimeMillis() - started,
                                    upstreamRequestJson = upstreamRequestJson,
                                    upstreamResponseJson = null,
                                    endpointType = "anthropic", routedUpstream = routedHost,
                                    promptTokens = totalInputTok,
                                    completionTokens = streamResult.outputTokens,
                                    totalTokens = totalTok,
                                ),
                        )
                    }
                }
                null
            }
        }
    }

    private data class InputWalkResult(
        val allFindings: List<Finding>,
        val blockReason: String?,
        val redactedRequestText: String,
        val originalSystemPrompt: String?,
        val bodyMutated: Boolean,
    )

    private fun walkAndGuardInput(root: ObjectNode, map: RedactionMap, messages: ArrayNode): InputWalkResult {
        val allFindings = mutableListOf<Finding>()
        var blockReason: String? = null
        val redactedRequestText = StringBuilder()
        var originalSystemPrompt: String? = null
        var mutated = false

        // Handle system field (Anthropic format)
        val systemNode = root["system"]
        if (systemNode != null && !systemNode.isNull) {
            when {
                systemNode.isTextual -> {
                    val text = systemNode.asText()
                    if (originalSystemPrompt == null) originalSystemPrompt = text
                    val decision = inputGuard.process(text, map)
                    allFindings += decision.findings
                    if (!decision.allow) {
                        blockReason = decision.blockReason
                    } else {
                        // Byte-identity: апдейтим JSON только если guard реально что-то изменил.
                        // Иначе любая нормализация (trim, NFC, CRLF→LF) ломает Anthropic prompt-cache,
                        // каждый запрос идёт как полный input → пробивает ITPM → 429 «temporarily limiting».
                        if (decision.processedText != text) {
                            root.put("system", decision.processedText)
                            mutated = true
                        }
                        redactedRequestText.append("[system] ").append(decision.processedText).append('\n')
                    }
                }
                systemNode.isArray -> {
                    for (i in 0 until systemNode.size()) {
                        val block = systemNode.get(i) as? ObjectNode ?: continue
                        val blockType = block["type"]?.asText() ?: continue
                        if (blockType == "text") {
                            val text = block["text"]?.asText() ?: continue
                            if (originalSystemPrompt == null) originalSystemPrompt = text
                            val decision = inputGuard.process(text, map)
                            allFindings += decision.findings
                            if (!decision.allow) {
                                blockReason = decision.blockReason
                                break
                            }
                            if (decision.processedText != text) {
                                block.put("text", decision.processedText)
                                mutated = true
                            }
                            redactedRequestText.append("[system] ").append(decision.processedText).append('\n')
                        }
                    }
                }
            }
        }

        if (blockReason != null) {
            return InputWalkResult(allFindings, blockReason, redactedRequestText.toString(), originalSystemPrompt, mutated)
        }

        // Walk messages
        for (i in 0 until messages.size()) {
            val msg = messages.get(i) as? ObjectNode ?: continue
            val role = msg["role"]?.asText() ?: ""
            val contentNode = msg["content"]

            // Guard text content
            if (contentNode != null && !contentNode.isNull) {
                when {
                    contentNode.isTextual -> {
                        val text = contentNode.asText()
                        if (role == "system" && originalSystemPrompt == null) originalSystemPrompt = text
                        val decision = inputGuard.process(text, map)
                        allFindings += decision.findings
                        if (!decision.allow) {
                            blockReason = decision.blockReason
                            break
                        }
                        if (decision.processedText != text) {
                            msg.put("content", decision.processedText)
                            mutated = true
                        }
                        redactedRequestText.append("[$role] ").append(decision.processedText).append('\n')
                    }
                    contentNode.isArray -> {
                        for (j in 0 until contentNode.size()) {
                            val part = contentNode.get(j) as? ObjectNode ?: continue
                            val partType = part["type"]?.asText() ?: continue
                            // image/document/thinking blocks are passed through unmodified.
                            // Base64 image data is NOT regex-scanned (documented limitation, see GuardTest case 7).
                            // Anthropic accepts these blocks; gateway preserves them byte-equal.
                            when (partType) {
                                "text" -> {
                                    val text = part["text"]?.asText() ?: continue
                                    if (role == "system" && originalSystemPrompt == null) originalSystemPrompt = text
                                    val decision = inputGuard.process(text, map)
                                    allFindings += decision.findings
                                    if (!decision.allow) {
                                        blockReason = decision.blockReason
                                        break
                                    }
                                    if (decision.processedText != text) {
                                        part.put("text", decision.processedText)
                                        mutated = true
                                    }
                                    redactedRequestText.append("[$role] ").append(decision.processedText).append('\n')
                                }
                                "tool_result" -> {
                                    // tool_result.content can be string or array
                                    val trContent = part["content"]
                                    if (trContent != null && !trContent.isNull) {
                                        when {
                                            trContent.isTextual -> {
                                                val text = trContent.asText()
                                                val decision = inputGuard.process(text, map)
                                                allFindings += decision.findings
                                                if (!decision.allow) {
                                                    blockReason = decision.blockReason
                                                    break
                                                }
                                                if (decision.processedText != text) {
                                                    part.put("content", decision.processedText)
                                                    mutated = true
                                                }
                                            }
                                            trContent.isArray -> {
                                                for (k in 0 until trContent.size()) {
                                                    val trPart = trContent.get(k) as? ObjectNode ?: continue
                                                    if (trPart["type"]?.asText() == "text") {
                                                        val text = trPart["text"]?.asText() ?: continue
                                                        val decision = inputGuard.process(text, map)
                                                        allFindings += decision.findings
                                                        if (!decision.allow) {
                                                            blockReason = decision.blockReason
                                                            break
                                                        }
                                                        if (decision.processedText != text) {
                                                            trPart.put("text", decision.processedText)
                                                            mutated = true
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                                "tool_use" -> {
                                    val inputNode = part["input"]
                                    if (inputNode != null) {
                                        val serialized = mapper.writeValueAsString(inputNode)
                                        val decision = inputGuard.process(serialized, map)
                                        allFindings += decision.findings
                                        if (!decision.allow) {
                                            blockReason = decision.blockReason
                                            break
                                        }
                                        if (decision.processedText != serialized) {
                                            runCatching {
                                                val parsed = mapper.readTree(decision.processedText)
                                                part.set<JsonNode>("input", parsed)
                                                mutated = true
                                            }.onFailure {
                                                log.warn("tool_use input guard parse failed: {}", it.message)
                                            }
                                        }
                                    }
                                }
                            }
                            if (blockReason != null) break
                        }
                    }
                }
            }

            if (blockReason != null) break
        }

        return InputWalkResult(allFindings, blockReason, redactedRequestText.toString(), originalSystemPrompt, mutated)
    }

    private fun injectAnthropicRedactionNote(root: ObjectNode) {
        val noticeBlock = mapper.createObjectNode().apply {
            put("type", "text")
            put("text", REDACTION_NOTICE)
        }

        val systemNode = root["system"]
        val newSystemArray = mapper.createArrayNode()
        newSystemArray.add(noticeBlock)

        when {
            systemNode == null || systemNode.isNull -> {
                // nothing to prepend to; just set the notice
            }
            systemNode.isTextual -> {
                val existingBlock = mapper.createObjectNode().apply {
                    put("type", "text")
                    put("text", systemNode.asText())
                }
                newSystemArray.add(existingBlock)
            }
            systemNode.isArray -> {
                for (i in 0 until systemNode.size()) {
                    newSystemArray.add(systemNode.get(i))
                }
            }
        }

        root.set<ArrayNode>("system", newSystemArray)
    }

    private fun errorBody(code: String, message: String): JsonNode {
        val n = mapper.createObjectNode()
        val err = mapper.createObjectNode()
        err.put("type", code)
        err.put("message", message)
        n.set<JsonNode>("error", err)
        return n
    }

    private fun clientIp(req: HttpServletRequest): String {
        val xff = req.getHeader("X-Forwarded-For")
        return xff?.split(',')?.firstOrNull()?.trim()?.takeIf { it.isNotBlank() } ?: req.remoteAddr ?: "unknown"
    }
}
