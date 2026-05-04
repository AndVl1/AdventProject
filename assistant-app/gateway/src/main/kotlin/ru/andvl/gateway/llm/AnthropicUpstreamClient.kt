package ru.andvl.gateway.llm

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.annotation.PostConstruct
import okhttp3.ConnectionPool
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.io.InputStream
import java.net.URI
import java.util.concurrent.TimeUnit

sealed interface UpstreamResult {
    data class Ok(val json: JsonNode) : UpstreamResult
    data class Error(
        val status: Int,
        val errorJson: JsonNode?,
        val rawBody: String?,
        val headers: Map<String, String> = emptyMap(),
    ) : UpstreamResult
    data class Failure(val cause: Throwable) : UpstreamResult
}

sealed interface UpstreamStreamResult {
    data class Ok(val body: InputStream) : UpstreamStreamResult
    data class Error(
        val status: Int,
        val errorJson: JsonNode?,
        val rawBody: String?,
        val headers: Map<String, String> = emptyMap(),
    ) : UpstreamStreamResult
    data class Failure(val cause: Throwable) : UpstreamStreamResult
}

// Список headers, которые форвардим клиенту при upstream-ошибках (особенно 429).
// CC ожидает retry-after / anthropic-ratelimit-* для корректного backoff. Без них —
// агрессивный retry, доп. удар по уже забитому лимиту.
private val FORWARDED_ERROR_HEADERS = setOf(
    "retry-after",
    "anthropic-ratelimit-requests-limit",
    "anthropic-ratelimit-requests-remaining",
    "anthropic-ratelimit-requests-reset",
    "anthropic-ratelimit-tokens-limit",
    "anthropic-ratelimit-tokens-remaining",
    "anthropic-ratelimit-tokens-reset",
    "anthropic-ratelimit-input-tokens-limit",
    "anthropic-ratelimit-input-tokens-remaining",
    "anthropic-ratelimit-input-tokens-reset",
    "anthropic-ratelimit-output-tokens-limit",
    "anthropic-ratelimit-output-tokens-remaining",
    "anthropic-ratelimit-output-tokens-reset",
    "anthropic-priority-input-tokens-limit",
    "anthropic-priority-input-tokens-remaining",
    "anthropic-priority-input-tokens-reset",
    "anthropic-priority-output-tokens-limit",
    "anthropic-priority-output-tokens-remaining",
    "anthropic-priority-output-tokens-reset",
    "request-id",
    "x-request-id",
)

@Service
class AnthropicUpstreamClient(
    private val mapper: ObjectMapper,
    @Value("\${gateway.anthropic.base-url:https://api.anthropic.com}")
    private val baseUrl: String,
    @Value("\${gateway.anthropic.allowed-hosts:api.anthropic.com}")
    private val allowedHostsCsv: String,
    private val router: ModelEndpointRouter,
) {

    private val log = LoggerFactory.getLogger(AnthropicUpstreamClient::class.java)

    // Anthropic ожидает Content-Type ровно `application/json` (без `; charset=utf-8`).
    // Direct CC и Python proxy шлют именно так. Иначе load-shed 429 без retry-after.
    private val jsonMediaType = "application/json".toMediaType()

    // OkHttp вместо JDK HttpClient. См. комментарий в pom.xml.
    // HTTP/1.1 only — Anthropic SSE отлично держит keep-alive на 1.1, и мы избегаем
    // любых HTTP/2 особенностей. Connection pool ограничен, чтобы не плодить SSL-сокеты.
    // readTimeout = 5 мин: больше любого нормального стрима, но защищает от зависших соединений.
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        // HTTP/2 + HTTP/1.1: прямой Claude Code (Node.js) ходит по HTTP/2. Если шлюз
        // даёт HTTP/1.1 — Anthropic edge (Cloudflare) видит другой ALPN/JA3 fingerprint
        // и режет как «не-CC клиент» (429 без retry-after, без token-counters).
        // OkHttp HTTP/2 — battle-tested, JDK-баги (SelectorManager spin, SSLEngineImpl
        // ReentrantLock) были у java.net.http, не у OkHttp.
        .protocols(listOf(Protocol.HTTP_1_1))
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.MINUTES)
        .writeTimeout(60, TimeUnit.SECONDS)
        .callTimeout(0, TimeUnit.MILLISECONDS) // 0 = без общего cap; полагаемся на read/write
        .connectionPool(ConnectionPool(maxIdleConnections = 8, keepAliveDuration = 5, TimeUnit.MINUTES))
        .retryOnConnectionFailure(true)
        .build()

    // SEC-004: Fail-fast SSRF protection. Validate ALL base-urls (default + all routes) on startup
    // to prevent misconfig leading to api-key leakage to arbitrary hosts.
    @PostConstruct
    open fun validateBaseUrl() {
        val allowedHosts = allowedHostsCsv.split(",").map { it.trim().lowercase() }.filter { it.isNotBlank() }
        val localHosts = setOf("localhost", "127.0.0.1")

        for (url in router.allBaseUrls()) {
            val uri = runCatching { URI.create(url) }.getOrElse {
                throw IllegalStateException("SEC-004: base-url is not a valid URI: '$url'", it)
            }
            val scheme = uri.scheme?.lowercase()
            val host = uri.host?.lowercase() ?: ""

            val isLocal = host in localHosts
            val isAllowed = host in allowedHosts

            if (scheme == "http" && !isLocal) {
                throw IllegalStateException(
                    "SEC-004: base-url uses http scheme for non-local host '$host'. " +
                        "Only https is allowed for non-localhost hosts.",
                )
            }
            if (scheme != "https" && scheme != "http") {
                throw IllegalStateException(
                    "SEC-004: base-url has unsupported scheme '$scheme'. Only https is allowed.",
                )
            }
            if (!isAllowed && !isLocal) {
                throw IllegalStateException(
                    "SEC-004: base-url host '$host' is not in the allowlist. " +
                        "Allowed hosts: $allowedHosts. " +
                        "Override via gateway.anthropic.allowed-hosts property.",
                )
            }
            log.info("SEC-004: upstream base-url validated: scheme=$scheme host=$host")
        }
    }

    private fun messagesUrl(base: String, query: String?): String {
        val trimmed = base.trimEnd('/')
        val q = query?.takeIf { it.isNotBlank() }
        return if (q != null) "$trimmed/v1/messages?$q" else "$trimmed/v1/messages"
    }

    private fun buildRequest(
        bodyBytes: ByteArray,
        apiKey: String?,
        bearerToken: String?,
        anthropicVersion: String,
        beta: String?,
        base: String,
        sessionId: String? = null,
        passthroughHeaders: Map<String, List<String>> = emptyMap(),
        upstreamQuery: String? = null,
    ): Request {
        require(!apiKey.isNullOrBlank() || !bearerToken.isNullOrBlank()) {
            "either apiKey or bearerToken must be provided"
        }
        val builder = Request.Builder()
            .url(messagesUrl(base, upstreamQuery))

        // Pass-through всех клиентских headers 1:1. Прямой CC шлёт UA `(external, sdk-cli)`,
        // `anthropic-dangerous-direct-browser-access: true`, `x-app: cli`, `x-client-request-id`,
        // `X-Stainless-*`. Все эти headers — часть нормального CC fingerprint (проверено через
        // mitmdump на direct CC без gateway). Anthropic edge ожидает их РОВНО так. Любая
        // мутация (нормализация UA, синтез billing-header, инъекция беты) ломает fingerprint
        // → load-shed 429 без retry-after.
        for ((name, values) in passthroughHeaders) {
            for (v in values) {
                if (v.isBlank()) continue
                builder.addHeader(name, v)
            }
        }

        // Override после passthrough (наши значения важнее любых клиентских дублей).
        builder.header("anthropic-version", anthropicVersion)
        builder.header("content-type", "application/json")
        if (!sessionId.isNullOrBlank()) {
            builder.header("X-Claude-Code-Session-Id", sessionId)
        }
        // Anthropic auth schemes:
        //  - x-api-key   — стандартный API key (sk-ant-api...)
        //  - Authorization: Bearer — OAuth-токен (sk-ant-oat...) от Claude Code Pro/Max login
        // Форвардим РОВНО ОДНУ схему (конфликт схем → 429 вместо 401).
        val effective = apiKey?.takeIf { it.isNotBlank() } ?: bearerToken
        if (!effective.isNullOrBlank()) {
            val isOauth = effective.startsWith("sk-ant-oat", ignoreCase = true)
            val preferBearer = isOauth || (apiKey.isNullOrBlank() && !bearerToken.isNullOrBlank())
            if (preferBearer) {
                builder.removeHeader("x-api-key")
                builder.header("Authorization", "Bearer $effective")
            } else {
                builder.removeHeader("Authorization")
                builder.header("x-api-key", effective)
            }
        }
        if (!beta.isNullOrBlank()) {
            builder.header("anthropic-beta", beta)
        }
        builder.post(bodyBytes.toRequestBody(jsonMediaType))
        return builder.build()
    }

    open fun send(
        bodyBytes: ByteArray,
        apiKey: String?,
        bearerToken: String?,
        anthropicVersion: String,
        beta: String?,
        baseUrl: String = this.baseUrl,
        sessionId: String? = null,
        passthroughHeaders: Map<String, List<String>> = emptyMap(),
        upstreamQuery: String? = null,
    ): UpstreamResult {
        return try {
            val request = buildRequest(
                bodyBytes, apiKey, bearerToken, anthropicVersion, beta, baseUrl, sessionId, passthroughHeaders, upstreamQuery,
            )
            httpClient.newCall(request).execute().use { response ->
                val statusCode = response.code
                val rawBody = response.body?.string().orEmpty()
                if (statusCode in 200..299) {
                    val json = runCatching { mapper.readTree(rawBody) }.getOrNull()
                        ?: return UpstreamResult.Error(statusCode, null, rawBody, collectErrorHeaders(response))
                    UpstreamResult.Ok(json)
                } else {
                    val errorJson = runCatching { mapper.readTree(rawBody) }.getOrNull()
                    val headers = collectErrorHeaders(response)
                    if (statusCode == 429) logRateLimit(request, response, headers)
                    UpstreamResult.Error(statusCode, errorJson, rawBody, headers)
                }
            }
        } catch (e: Exception) {
            log.error("upstream send failed: {}", e.message)
            UpstreamResult.Failure(e)
        }
    }

    open fun sendStream(
        bodyBytes: ByteArray,
        apiKey: String?,
        bearerToken: String?,
        anthropicVersion: String,
        beta: String?,
        baseUrl: String = this.baseUrl,
        sessionId: String? = null,
        passthroughHeaders: Map<String, List<String>> = emptyMap(),
        upstreamQuery: String? = null,
    ): UpstreamStreamResult {
        return try {
            val request = buildRequest(
                bodyBytes, apiKey, bearerToken, anthropicVersion, beta, baseUrl, sessionId, passthroughHeaders, upstreamQuery,
            )
            val response: Response = httpClient.newCall(request).execute()
            val statusCode = response.code
            if (statusCode in 200..299) {
                // Оборачиваем body InputStream так, чтобы close() закрывал и Response (отпускал
                // соединение в пул и освобождал SSL-контекст). Без этого OkHttp лижет ресурс
                // до GC, и при разрыве клиентом мы держим upstream-соединение зомбём.
                val rawStream = response.body?.byteStream()
                if (rawStream == null) {
                    response.close()
                    UpstreamStreamResult.Error(statusCode, null, null)
                } else {
                    UpstreamStreamResult.Ok(ResponseClosingInputStream(rawStream, response))
                }
            } else {
                response.use { r ->
                    val rawBody = r.body?.string()
                    val errorJson = rawBody?.let { runCatching { mapper.readTree(it) }.getOrNull() }
                    val headers = collectErrorHeaders(r)
                    if (statusCode == 429) logRateLimit(request, r, headers)
                    UpstreamStreamResult.Error(statusCode, errorJson, rawBody, headers)
                }
            }
        } catch (e: Exception) {
            log.error("upstream sendStream failed: {}", e.message)
            UpstreamStreamResult.Failure(e)
        }
    }

    private fun collectErrorHeaders(response: Response): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for (name in response.headers.names()) {
            if (name.lowercase() in FORWARDED_ERROR_HEADERS) {
                out[name] = response.header(name).orEmpty()
            }
        }
        return out
    }

    private fun logRateLimit(request: Request, response: Response, headers: Map<String, String>) {
        // Диагностика 429 от Anthropic. Smoking gun обычно в *-tokens-* counters
        // и retry-after. Также логируем что мы реально отправили в anthropic-beta —
        // потому что без `extended-cache-ttl-2025-04-11` 1h-кэш CC не активируется
        // и каждый запрос идёт как полный input → blow ITPM → 429.
        val sentBeta = request.header("anthropic-beta")
        val sentSession = request.header("X-Claude-Code-Session-Id")
        val sentUa = request.header("User-Agent") ?: request.header("user-agent")
        val sentStainless = request.headers.names()
            .filter { it.lowercase().startsWith("x-stainless") }
            .joinToString(",") { "$it=${request.header(it)}" }
        val authMode = if (request.header("Authorization")?.startsWith("Bearer ") == true) "oauth"
        else if (request.header("x-api-key") != null) "api-key" else "none"
        log.warn(
            "anthropic 429: auth={} sessionId={} ua={} stainless=[{}] sentBeta=[{}] retry-after={} input-tokens-remaining={} input-tokens-reset={} request-id={}",
            authMode,
            sentSession ?: "<none>",
            sentUa ?: "<none>",
            sentStainless.ifBlank { "<none>" },
            sentBeta ?: "<none>",
            headers["retry-after"] ?: "-",
            headers["anthropic-ratelimit-input-tokens-remaining"] ?: "-",
            headers["anthropic-ratelimit-input-tokens-reset"] ?: "-",
            headers["request-id"] ?: headers["x-request-id"] ?: "-",
        )
    }

    private class ResponseClosingInputStream(
        private val delegate: InputStream,
        private val response: Response,
    ) : InputStream() {
        override fun read(): Int = delegate.read()
        override fun read(b: ByteArray, off: Int, len: Int): Int = delegate.read(b, off, len)
        override fun available(): Int = delegate.available()
        override fun close() {
            try {
                delegate.close()
            } finally {
                response.close()
            }
        }
    }
}
