package ru.andvl.gateway.api

import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import ru.andvl.gateway.guard.RedactionEngine
import ru.andvl.gateway.guard.RedactionMap
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicInteger

@Service
class SseGuardStream(
    private val mapper: ObjectMapper,
    private val redactionEngine: RedactionEngine,
) {

    private val log = LoggerFactory.getLogger(SseGuardStream::class.java)

    companion object {
        // SEC-002: increased from 96 to 1024 to catch long secrets (JWT 300-1000 chars,
        // sk-proj ~140, sk-ant >100) that wouldn't fit in the previous 96-char tail window
        const val TAIL_KEEP = 1024
    }

    data class StreamResult(
        val loggableText: String,
        val hallucinatedCount: Int,
        val suspiciousUrls: List<String>,
        val inputTokens: Int,
        val outputTokens: Int,
        val cacheCreationInputTokens: Int,
        val cacheReadInputTokens: Int,
        val model: String?,
        val notes: List<String>,
        val stopReason: String?,
    )

    fun pipe(
        upstream: InputStream,
        downstream: OutputStream,
        map: RedactionMap,
        onComplete: (StreamResult) -> Unit,
    ) {
        val loggableText = StringBuilder()
        val notes = mutableListOf<String>()
        val suspiciousUrls = mutableListOf<String>()
        var inputTokens = 0
        var outputTokens = 0
        var cacheCreationInputTokens = 0
        var cacheReadInputTokens = 0
        var model: String? = null
        var stopReason: String? = null
        val hallucinatedCounter = AtomicInteger(0)

        // Per-block tail buffers (text blocks) and tool_use accumulators
        val tailBuffers = mutableMapOf<Int, StringBuilder>()
        val toolUseAccum = mutableMapOf<Int, StringBuilder>()
        val blockTypes = mutableMapOf<Int, String>()

        val reader = BufferedReader(InputStreamReader(upstream, Charsets.UTF_8))

        // SSE parsing state
        var currentEventName: String? = null
        var currentDataLine: String? = null

        // ВАЖНО: НЕ глотать IOException. Если клиент разорвал соединение,
        // надо пробросить наружу — внешний `body.use { }` закроет upstream InputStream
        // и upstream HTTP-стрим освободится. Иначе мы продолжали бы читать Anthropic
        // до конца впустую (тратя токены) и держали HTTP-worker занятым.
        fun writeEvent(name: String, jsonRaw: String) {
            downstream.write("event: $name\n".toByteArray(Charsets.UTF_8))
            downstream.write("data: $jsonRaw\n\n".toByteArray(Charsets.UTF_8))
            downstream.flush()
        }

        fun guardChunk(prefix: String): String {
            val res = redactionEngine.apply(prefix, null)
            if (res.findings.isEmpty()) return prefix
            // res.text already has rule.placeholder substituted; replace each placeholder
            // with a numbered LLM_OUTPUT_GUARD_N token
            var processed = res.text
            for (f in res.findings) {
                val n = hallucinatedCounter.incrementAndGet()
                val replacement = "LLM_OUTPUT_GUARD_$n"
                processed = processed.replace(f.placeholder, replacement)
            }
            return processed
        }

        fun flushTailBuffer(idx: Int, downstream: OutputStream, writeEvent: (String, String) -> Unit) {
            val buf = tailBuffers[idx] ?: return
            val remaining = buf.toString()
            if (remaining.isNotEmpty()) {
                val processed = guardChunk(remaining)
                loggableText.append(processed)
                val reversed = map.reverse(processed)
                if (reversed.isNotEmpty()) {
                    // emit synthetic content_block_delta
                    val syntheticDelta = mapper.createObjectNode().apply {
                        put("type", "content_block_delta")
                        put("index", idx)
                        val delta = mapper.createObjectNode()
                        delta.put("type", "text_delta")
                        delta.put("text", reversed)
                        set<ObjectNode>("delta", delta)
                    }
                    writeEvent("content_block_delta", mapper.writeValueAsString(syntheticDelta))
                }
                buf.clear()
            }
            tailBuffers.remove(idx)
        }

        fun parseAndProcessEvent(name: String?, jsonRaw: String) {
            when (name) {
                null, "ping" -> {
                    // passthrough as-is
                    val eventName = name ?: "event"
                    writeEvent(eventName, jsonRaw)
                }
                "message_start" -> {
                    val json = runCatching { mapper.readTree(jsonRaw) as? ObjectNode }.getOrNull()
                        ?: run { notes += "malformed_event"; return }
                    model = json["message"]?.get("model")?.asText()
                    val usage = json["message"]?.get("usage")
                    inputTokens = usage?.get("input_tokens")?.asInt() ?: 0
                    cacheCreationInputTokens = usage?.get("cache_creation_input_tokens")?.asInt() ?: 0
                    cacheReadInputTokens = usage?.get("cache_read_input_tokens")?.asInt() ?: 0
                    writeEvent(name, jsonRaw)
                }
                "content_block_start" -> {
                    val json = runCatching { mapper.readTree(jsonRaw) as? ObjectNode }.getOrNull()
                        ?: run { notes += "malformed_event"; return }
                    val idx = json["index"]?.asInt() ?: 0
                    val blockType = json["content_block"]?.get("type")?.asText() ?: ""
                    blockTypes[idx] = blockType
                    when (blockType) {
                        "text" -> tailBuffers[idx] = StringBuilder()
                        "tool_use" -> toolUseAccum[idx] = StringBuilder()
                    }
                    writeEvent(name, jsonRaw)
                }
                "content_block_delta" -> {
                    val json = runCatching { mapper.readTree(jsonRaw) as? ObjectNode }.getOrNull()
                        ?: run { notes += "malformed_event"; return }
                    val idx = json["index"]?.asInt() ?: 0
                    val delta = json["delta"] as? ObjectNode
                        ?: run { writeEvent(name, jsonRaw); return }
                    val deltaType = delta["type"]?.asText() ?: ""

                    when (deltaType) {
                        "text_delta" -> {
                            val text = delta["text"]?.asText() ?: ""
                            val buf = tailBuffers.getOrPut(idx) { StringBuilder() }
                            buf.append(text)
                            val len = buf.length
                            if (len > TAIL_KEEP) {
                                val prefixEnd = len - TAIL_KEEP
                                val prefix = buf.substring(0, prefixEnd)
                                val tail = buf.substring(prefixEnd)
                                val processed = guardChunk(prefix)
                                loggableText.append(processed)
                                val reversed = map.reverse(processed)
                                // emit event with reversed text
                                val outJson = json.deepCopy() as ObjectNode
                                (outJson["delta"] as? ObjectNode)?.put("text", reversed)
                                writeEvent(name, mapper.writeValueAsString(outJson))
                                buf.clear()
                                buf.append(tail)
                            }
                            // if buf.length <= TAIL_KEEP: do NOT write event to downstream yet
                        }
                        "input_json_delta" -> {
                            // SEC-003: buffer input_json_delta instead of immediate passthrough
                            // to allow guard scan at content_block_stop. True streaming for
                            // tool_use is sacrificed intentionally for security correctness.
                            val partial = delta["partial_json"]?.asText() ?: ""
                            toolUseAccum.getOrPut(idx) { StringBuilder() }.append(partial)
                            // do NOT emit downstream yet — will emit at content_block_stop
                        }
                        else -> {
                            // thinking_delta, signature_delta, other: passthrough
                            writeEvent(name, jsonRaw)
                        }
                    }
                }
                "content_block_stop" -> {
                    val json = runCatching { mapper.readTree(jsonRaw) as? ObjectNode }.getOrNull()
                        ?: run { notes += "malformed_event"; return }
                    val idx = json["index"]?.asInt() ?: 0
                    val blockType = blockTypes[idx] ?: ""

                    if (blockType == "text") {
                        // flush remaining tail buffer with synthetic delta before stop
                        flushTailBuffer(idx, downstream) { evName, evData -> writeEvent(evName, evData) }
                    } else if (blockType == "tool_use") {
                        // SEC-003: flush buffered tool_use input_json through guard + reverse
                        val accumulated = toolUseAccum[idx]?.toString() ?: ""
                        if (accumulated.isNotEmpty()) {
                            // 1. Scan for hallucinated secrets (output guard re-scan)
                            val scanned = run {
                                val res = redactionEngine.apply(accumulated, null)
                                var processed = res.text
                                for (f in res.findings) {
                                    val n = hallucinatedCounter.incrementAndGet()
                                    val replacement = "LLM_OUTPUT_GUARD_$n"
                                    processed = processed.replace(f.placeholder, replacement)
                                }
                                processed
                            }
                            // 2. Reverse client placeholders back to originals
                            val reversed = map.reverse(scanned)
                            // 3. Emit one synthetic input_json_delta with the full processed JSON
                            val syntheticDelta = mapper.createObjectNode().apply {
                                put("type", "content_block_delta")
                                put("index", idx)
                                val deltaNode = mapper.createObjectNode()
                                deltaNode.put("type", "input_json_delta")
                                deltaNode.put("partial_json", reversed)
                                set<ObjectNode>("delta", deltaNode)
                            }
                            writeEvent("content_block_delta", mapper.writeValueAsString(syntheticDelta))
                        }
                    }
                    toolUseAccum.remove(idx)
                    blockTypes.remove(idx)

                    writeEvent(name, jsonRaw)
                }
                "message_delta" -> {
                    val json = runCatching { mapper.readTree(jsonRaw) as? ObjectNode }.getOrNull()
                        ?: run { notes += "malformed_event"; return }
                    outputTokens = json["usage"]?.get("output_tokens")?.asInt() ?: outputTokens
                    stopReason = json["delta"]?.get("stop_reason")?.asText() ?: stopReason
                    writeEvent(name, jsonRaw)
                }
                "message_stop" -> {
                    writeEvent(name, jsonRaw)
                }
                "error" -> {
                    notes += "upstream_error: ${jsonRaw.take(200)}"
                    writeEvent(name, jsonRaw)
                }
                else -> {
                    // unknown event: passthrough
                    writeEvent(name, jsonRaw)
                }
            }
        }

        // code-reviewer HIGH #3: guard flag ensures onComplete is called exactly once
        // even if parseAndProcessEvent throws an unchecked exception (e.g. JsonProcessingException)
        var completed = false

        fun finalize() {
            if (completed) return
            completed = true
            // Safety net: flush any remaining tail buffers
            for (idx in tailBuffers.keys.toList()) {
                flushTailBuffer(idx, downstream) { evName, evData -> writeEvent(evName, evData) }
            }
            onComplete(
                StreamResult(
                    loggableText = loggableText.toString(),
                    hallucinatedCount = hallucinatedCounter.get(),
                    suspiciousUrls = suspiciousUrls,
                    inputTokens = inputTokens,
                    outputTokens = outputTokens,
                    cacheCreationInputTokens = cacheCreationInputTokens,
                    cacheReadInputTokens = cacheReadInputTokens,
                    model = model,
                    notes = notes,
                    stopReason = stopReason,
                ),
            )
        }

        try {
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                val l = line!!
                when {
                    l.startsWith("event:") -> {
                        currentEventName = l.substringAfter("event:").trim()
                    }
                    l.startsWith("data:") -> {
                        currentDataLine = l.substringAfter("data:").trim()
                    }
                    l.isEmpty() -> {
                        // SSE event boundary
                        val dataRaw = currentDataLine
                        val eventName = currentEventName
                        if (dataRaw != null) {
                            // Validate JSON
                            val isValidJson = runCatching { mapper.readTree(dataRaw) }.isSuccess
                            if (!isValidJson) {
                                notes += "malformed_event"
                            } else {
                                parseAndProcessEvent(eventName, dataRaw)
                            }
                        }
                        currentEventName = null
                        currentDataLine = null
                    }
                }
            }
        } catch (e: IOException) {
            // Может быть от upstream.read (Anthropic закрыл / таймаут)
            // ИЛИ от downstream.write (клиент разорвал TCP).
            // В обоих случаях finally{} вызовет finalize() → audit запишет частичный результат.
            notes += "io_error: ${e.message}"
            log.debug("pipe io_error: {}", e.message)
        } catch (e: Exception) {
            // Unexpected fatal error (e.g. JsonProcessingException from parseAndProcessEvent).
            // Record it in notes so audit captures it, then fall through to finalize().
            notes += "fatal: ${e.message}"
            log.error("pipe fatal error", e)
        } finally {
            finalize()
        }
    }
}
