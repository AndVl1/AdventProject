package ru.andvl.gateway.llm

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.annotation.PostConstruct
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.io.InputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

sealed interface UpstreamResult {
    data class Ok(val json: JsonNode) : UpstreamResult
    data class Error(val status: Int, val errorJson: JsonNode?, val rawBody: String?) : UpstreamResult
    data class Failure(val cause: Throwable) : UpstreamResult
}

sealed interface UpstreamStreamResult {
    data class Ok(val body: InputStream) : UpstreamStreamResult
    data class Error(val status: Int, val errorJson: JsonNode?, val rawBody: String?) : UpstreamStreamResult
    data class Failure(val cause: Throwable) : UpstreamStreamResult
}

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

    // HTTP/1.1 + явный requestTimeout: на JDK 21 HTTP/2 SelectorManager уходил в spin-loop
    // (97% CPU per worker) при разрыве клиента в середине SSE — каждый «зависший» стрим
    // оставлял HttpClient-Worker, не освобождаемый до рестарта. См. JDK-8334077, JDK-8295056.
    private val httpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(30))
        .version(HttpClient.Version.HTTP_1_1)
        .build()

    // Hard cap на весь запрос (включая чтение тела стрима).
    // Anthropic SSE редко идёт >5 минут — больше = почти наверняка зависшее соединение.
    private val requestTimeout: Duration = Duration.ofMinutes(5)

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

    private fun messagesUrl(base: String): URI {
        val trimmed = base.trimEnd('/')
        return URI.create("$trimmed/v1/messages")
    }

    private fun buildRequest(
        body: JsonNode,
        apiKey: String?,
        bearerToken: String?,
        anthropicVersion: String,
        beta: String?,
        base: String,
    ): HttpRequest {
        require(!apiKey.isNullOrBlank() || !bearerToken.isNullOrBlank()) {
            "either apiKey or bearerToken must be provided"
        }
        val bodyBytes = mapper.writeValueAsBytes(body)
        val builder = HttpRequest.newBuilder()
            .uri(messagesUrl(base))
            .timeout(requestTimeout)
            .header("anthropic-version", anthropicVersion)
            .header("content-type", "application/json")
        // Anthropic native API uses x-api-key. OAuth tokens (sk-ant-oat*) require Authorization: Bearer.
        // To support both schemes regardless of which header the client sent (Claude Code with
        // ANTHROPIC_AUTH_TOKEN sends Bearer, but the token may be a regular API key), forward the
        // single secret in BOTH headers — upstream picks the right one.
        val effective = apiKey?.takeIf { it.isNotBlank() } ?: bearerToken
        if (!effective.isNullOrBlank()) {
            builder.header("x-api-key", effective)
            builder.header("Authorization", "Bearer $effective")
        }
        if (!beta.isNullOrBlank()) {
            builder.header("anthropic-beta", beta)
        }
        builder.POST(HttpRequest.BodyPublishers.ofByteArray(bodyBytes))
        return builder.build()
    }

    fun send(
        body: JsonNode,
        apiKey: String?,
        bearerToken: String?,
        anthropicVersion: String,
        beta: String?,
        baseUrl: String = this.baseUrl,
    ): UpstreamResult {
        return try {
            val request = buildRequest(body, apiKey, bearerToken, anthropicVersion, beta, baseUrl)
            val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(Charsets.UTF_8))
            val statusCode = response.statusCode()
            val rawBody = response.body()
            if (statusCode in 200..299) {
                val json = runCatching { mapper.readTree(rawBody) }.getOrNull()
                    ?: return UpstreamResult.Error(statusCode, null, rawBody)
                UpstreamResult.Ok(json)
            } else {
                val errorJson = runCatching { mapper.readTree(rawBody) }.getOrNull()
                UpstreamResult.Error(statusCode, errorJson, rawBody)
            }
        } catch (e: Exception) {
            log.error("upstream send failed: {}", e.message)
            UpstreamResult.Failure(e)
        }
    }

    fun sendStream(
        body: JsonNode,
        apiKey: String?,
        bearerToken: String?,
        anthropicVersion: String,
        beta: String?,
        baseUrl: String = this.baseUrl,
    ): UpstreamStreamResult {
        return try {
            val request = buildRequest(body, apiKey, bearerToken, anthropicVersion, beta, baseUrl)
            val response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream())
            val statusCode = response.statusCode()
            if (statusCode in 200..299) {
                UpstreamStreamResult.Ok(response.body())
            } else {
                // Non-2xx: read body as string, do NOT open stream
                val rawBody = runCatching {
                    response.body().use { it.readBytes().toString(Charsets.UTF_8) }
                }.getOrNull()
                val errorJson = rawBody?.let { runCatching { mapper.readTree(it) }.getOrNull() }
                UpstreamStreamResult.Error(statusCode, errorJson, rawBody)
            }
        } catch (e: Exception) {
            log.error("upstream sendStream failed: {}", e.message)
            UpstreamStreamResult.Failure(e)
        }
    }
}
