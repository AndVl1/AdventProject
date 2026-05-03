package ru.andvl.gateway.api

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import jakarta.servlet.http.HttpServletRequest
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
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
import ru.andvl.gateway.llm.LlmClient
import ru.andvl.gateway.persistence.AuditLog
import ru.andvl.gateway.persistence.AuditRepository
import ru.andvl.gateway.persistence.CostRecord
import ru.andvl.gateway.persistence.CostRepository
import ru.andvl.gateway.persistence.RedactionEvent
import ru.andvl.gateway.persistence.RedactionEventRepository
import ru.andvl.gateway.ratelimit.RateLimiter
import java.util.UUID

// Маршруты:
//   /v1/chat/completions          — собственный путь шлюза
//   /api/v1/chat/completions      — алиас под koog OpenRouter-клиента (он бьёт по
//                                   <baseUrl>/api/v1/chat/completions)
@RestController
@RequestMapping(path = ["/v1", "/api/v1"])
class ProxyController(
    private val mapper: ObjectMapper,
    private val registry: ConversationRegistry,
    private val inputGuard: InputGuard,
    private val outputGuard: OutputGuard,
    private val llm: LlmClient,
    private val rateLimiter: RateLimiter,
    private val costTable: CostTable,
    private val audit: AuditRepository,
    private val redactionEvents: RedactionEventRepository,
    private val costs: CostRepository,
) {

    private val log = LoggerFactory.getLogger(ProxyController::class.java)

    @PostMapping("/chat/completions")
    fun chatCompletions(
        @RequestBody body: JsonNode,
        @RequestHeader(value = "X-Conversation-Id", required = false) conversationIdHeader: String?,
        request: HttpServletRequest,
    ): ResponseEntity<JsonNode> {
        val started = System.currentTimeMillis()
        val ip = clientIp(request)
        val conversationId = conversationIdHeader?.takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString()

        val rl = rateLimiter.check(ip)
        if (!rl.allowed) {
            audit.insert(
                AuditLog(
                    conversationId = conversationId, clientIp = ip, model = body["model"]?.asText(),
                    requestText = null, responseText = null,
                    status = "RATE_LIMITED", blockReason = "rate limit ${rateLimiter.limitPerMinute()}/min",
                    inputFindings = null, outputFindings = null, latencyMs = 0,
                ),
            )
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header("X-RateLimit-Reset-Ms", rl.resetMs.toString())
                .body(errorBody("rate_limit_exceeded", "Too many requests. Reset in ${rl.resetMs}ms."))
        }

        if (!body.isObject) return ResponseEntity.badRequest().body(errorBody("bad_request", "JSON object expected"))
        val root = body as ObjectNode
        val model = root["model"]?.asText() ?: "unknown"
        val messages = root["messages"] as? ArrayNode
            ?: return ResponseEntity.badRequest().body(errorBody("bad_request", "messages[] required"))

        val map = registry.forConversation(conversationId)

        // Apply input guard to every message content. If BLOCK mode and any finding -> 400.
        val redactedRequestText = StringBuilder()
        val allFindings = mutableListOf<Finding>()
        var blockReason: String? = null

        // Find original system prompt (first system msg) for downstream output-leak check.
        var originalSystemPrompt: String? = null

        for (i in 0 until messages.size()) {
            val msg = messages.get(i) as? ObjectNode ?: continue
            val role = msg["role"]?.asText() ?: ""
            val contentText = extractTextContent(msg["content"])
            if (contentText != null) {
                if (role == "system" && originalSystemPrompt == null) originalSystemPrompt = contentText

                val decision = inputGuard.process(contentText, map)
                allFindings += decision.findings
                if (!decision.allow) {
                    blockReason = decision.blockReason
                    break
                }
                setTextContent(msg, decision.processedText)
                redactedRequestText.append("[$role] ").append(decision.processedText).append('\n')
            }
            // Replay history: assistant сообщения с tool_calls содержат уже развёрнутые
            // (реальные) значения в function.arguments — их надо снова заредачить, иначе
            // LLM увидит секрет.
            val toolCalls = msg["tool_calls"] as? ArrayNode
            if (toolCalls != null) {
                for (j in 0 until toolCalls.size()) {
                    val tc = toolCalls.get(j) as? ObjectNode ?: continue
                    val fn = tc["function"] as? ObjectNode ?: continue
                    val argsRaw = fn["arguments"]?.asText() ?: continue
                    val decision = inputGuard.process(argsRaw, map)
                    allFindings += decision.findings
                    if (!decision.allow) {
                        blockReason = decision.blockReason
                        break
                    }
                    if (decision.processedText != argsRaw) {
                        fn.put("arguments", decision.processedText)
                        redactedRequestText.append("[tool_call:").append(fn["name"]?.asText() ?: "?")
                            .append("] ").append(decision.processedText).append('\n')
                    }
                }
                if (blockReason != null) break
            }
        }

        // Persist redaction events for audit
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
                    // В requestText кладём текст уже после InputGuard (с плейсхолдерами).
                    // Сырые секреты в БД не попадают.
                    requestText = redactedRequestText.toString(), responseText = null,
                    status = "BLOCKED", blockReason = blockReason,
                    inputFindings = mapper.writeValueAsString(allFindings.groupingBy { it.ruleName }.eachCount()),
                    outputFindings = null, latencyMs = System.currentTimeMillis() - started,
                ),
            )
            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                .body(errorBody("input_blocked", blockReason))
        }

        // If we redacted anything, prepend a system note explaining REDACTED tokens.
        if (allFindings.isNotEmpty()) injectRedactionSystemNote(messages)

        // Snapshot того, что РЕАЛЬНО улетает в upstream LLM:
        //   - после InputGuard (плейсхолдеры вместо секретов)
        //   - после injectRedactionSystemNote (system-инструкция про REDACTED)
        // Безопасно для хранения: сырых секретов нет, плейсхолдеры юзера тут уже стоят.
        val upstreamRequestJson = mapper.writeValueAsString(root)

        // Forward upstream
        val upstream = llm.chatCompletion(root)
            ?: run {
                audit.insert(
                    AuditLog(
                        conversationId = conversationId, clientIp = ip, model = model,
                        requestText = redactedRequestText.toString(),             // post-guard, безопасно
                        responseText = null, status = "ERROR", blockReason = "upstream call failed",
                        inputFindings = mapper.writeValueAsString(allFindings.groupingBy { it.ruleName }.eachCount()),
                        outputFindings = null, latencyMs = System.currentTimeMillis() - started,
                        upstreamRequestJson = upstreamRequestJson,
                        upstreamResponseJson = null,
                    ),
                )
                return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .body(errorBody("upstream_error", "Upstream LLM call failed"))
            }

        // Snapshot upstream ДО любых мутаций: tool_calls.arguments тут ещё в REDACTED-форме
        // (LLM видела только плейсхолдеры) → безопасно для БД. Content патчим ниже на loggableText.
        val upstreamSnapshot = upstream.deepCopy<JsonNode>()
        val snapChoices = upstreamSnapshot["choices"] as? ArrayNode

        // Process model output.
        val choices = upstream["choices"] as? ArrayNode
        var assistantTextLoggable = ""   // безопасно для audit: sanitized + плейсхолдеры юзера НЕ развёрнуты
        var hallucinatedTotal = 0        // только счётчик; сами значения нигде не сохраняем
        var leak = false
        var suspiciousUrls = emptyList<String>()
        if (choices != null && choices.size() > 0) {
            for (i in 0 until choices.size()) {
                val choice = choices.get(i) as? ObjectNode ?: continue
                val msg = choice["message"] as? ObjectNode ?: continue
                // 1) text content path (может быть null если ответ — чисто tool_calls)
                val raw = msg["content"]?.asText()
                if (raw != null) {
                    val out = outputGuard.process(raw, map, originalSystemPrompt)
                    msg.put("content", out.finalText)
                    // patch snapshot's content на loggable (sanitized, без reverse)
                    (snapChoices?.get(i) as? ObjectNode)?.let { sc ->
                        (sc["message"] as? ObjectNode)?.put("content", out.loggableText)
                    }
                    assistantTextLoggable += out.loggableText
                    hallucinatedTotal += out.hallucinatedCount
                    if (out.systemPromptLeak) leak = true
                    if (out.suspiciousUrls.isNotEmpty()) suspiciousUrls = out.suspiciousUrls
                    // Галлюцинации НЕ пишем в redaction_event — оригиналы и хэши не храним.
                }
                // 2) tool_calls path — модель видела только REDACTED_*, в function.arguments
                //    (JSON-строка) могут лежать плейсхолдеры. Разворачиваем для клиента,
                //    иначе внешний tool (Gmail и т.п.) получит "REDACTED_EMAIL_1" как
                //    реальное значение. В snapshot оставляем как было (REDACTED).
                val toolCalls = msg["tool_calls"] as? ArrayNode
                if (toolCalls != null) {
                    for (j in 0 until toolCalls.size()) {
                        val tc = toolCalls.get(j) as? ObjectNode ?: continue
                        val fn = tc["function"] as? ObjectNode ?: continue
                        val argsRaw = fn["arguments"]?.asText() ?: continue
                        val argsReversed = map.reverse(argsRaw)
                        if (argsReversed != argsRaw) {
                            fn.put("arguments", argsReversed)
                        }
                    }
                }
            }
        }

        val upstreamResponseJson = mapper.writeValueAsString(upstreamSnapshot)

        // Cost tracking
        val usage = upstream["usage"]
        if (usage != null && usage.isObject) {
            val pt = usage["prompt_tokens"]?.asInt() ?: 0
            val ct = usage["completion_tokens"]?.asInt() ?: 0
            val tt = usage["total_tokens"]?.asInt() ?: (pt + ct)
            val cost = costTable.estimateUsd(model, pt, ct)
            costs.insert(
                CostRecord(
                    conversationId = conversationId, model = model,
                    promptTokens = pt, completionTokens = ct, totalTokens = tt, costUsd = cost,
                ),
            )
        }

        val findingsSummary = buildMap<String, Any> {
            put("input", allFindings.groupingBy { it.ruleName }.eachCount())
            // только число галлюцинаций; конкретные значения не хранятся.
            put("output_hallucinated", hallucinatedTotal)
            put("system_prompt_leak", leak)
            put("suspicious_urls", suspiciousUrls)
        }

        audit.insert(
            AuditLog(
                conversationId = conversationId, clientIp = ip, model = model,
                // requestText: пост-InputGuard (плейсхолдеры) — сырые секреты в БД не попадают.
                // responseText: пост-OutputGuard rescan, ДО reverse — галлюцинации замаскированы,
                // плейсхолдеры юзера НЕ развёрнуты в оригиналы. Reverse применён только для
                // finalText, что отдан клиенту в HTTP-ответе.
                requestText = redactedRequestText.toString(),
                responseText = assistantTextLoggable,
                status = "OK",
                blockReason = null,
                inputFindings = mapper.writeValueAsString(allFindings.groupingBy { it.ruleName }.eachCount()),
                outputFindings = mapper.writeValueAsString(findingsSummary),
                latencyMs = System.currentTimeMillis() - started,
                upstreamRequestJson = upstreamRequestJson,
                upstreamResponseJson = upstreamResponseJson,
            ),
        )

        return ResponseEntity.ok()
            .header("X-Conversation-Id", conversationId)
            .header("X-Gateway-Input-Redactions", allFindings.size.toString())
            .header("X-Gateway-Output-Hallucinated", hallucinatedTotal.toString())
            .header("X-Gateway-System-Prompt-Leak", leak.toString())
            .body(upstream)
    }

    private fun extractTextContent(node: JsonNode?): String? {
        if (node == null || node.isNull) return null
        if (node.isTextual) return node.asText()
        // OpenAI extended format: array of {type:"text", text:"..."}
        if (node.isArray) {
            val sb = StringBuilder()
            for (i in 0 until node.size()) {
                val part = node.get(i)
                if (part.isObject && part["type"]?.asText() == "text") {
                    sb.append(part["text"]?.asText() ?: "")
                }
            }
            return sb.toString()
        }
        return null
    }

    private fun setTextContent(msg: ObjectNode, text: String) {
        val current = msg["content"]
        if (current != null && current.isArray) {
            // overwrite first text part; clear others to keep things simple
            val arr = mapper.createArrayNode()
            arr.add(mapper.createObjectNode().put("type", "text").put("text", text))
            msg.set<JsonNode>("content", arr)
        } else {
            msg.put("content", text)
        }
    }

    private fun injectRedactionSystemNote(messages: ArrayNode) {
        val note = """
            [GATEWAY NOTICE] Some user-supplied values were replaced with placeholders of the form
            `REDACTED_<TYPE>_<N>` (e.g. `REDACTED_OPENAI_KEY_1`, `REDACTED_EMAIL_2`). These
            placeholders represent real secrets/PII that the user provided. Treat them as opaque
            tokens — do NOT try to guess, generate, or "decrypt" them, and do not warn the user
            about them. Simply use the placeholder when you need to refer back to the value;
            the gateway will swap it back to the original before the user sees your reply.
        """.trimIndent()
        // prepend as a new system message (don't overwrite existing one)
        val sys = mapper.createObjectNode()
        sys.put("role", "system")
        sys.put("content", note)
        // ArrayNode has no insert(0,...) directly; build a new one
        val newArr = mapper.createArrayNode()
        newArr.add(sys)
        for (i in 0 until messages.size()) newArr.add(messages.get(i))
        messages.removeAll()
        messages.addAll(newArr)
    }

    private fun clientIp(req: HttpServletRequest): String {
        val xff = req.getHeader("X-Forwarded-For")
        return xff?.split(',')?.firstOrNull()?.trim()?.takeIf { it.isNotBlank() } ?: req.remoteAddr ?: "unknown"
    }

    private fun errorBody(code: String, message: String): JsonNode {
        val n = mapper.createObjectNode()
        val err = mapper.createObjectNode()
        err.put("type", code)
        err.put("message", message)
        n.set<JsonNode>("error", err)
        return n
    }
}
