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
) {

    private val log = LoggerFactory.getLogger(AnthropicUpstreamClient::class.java)

    private val httpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(60))
        .version(HttpClient.Version.HTTP_2)
        .build()

    // SEC-004: Fail-fast SSRF protection. Validate base-url scheme and host on startup
    // to prevent misconfig leading to api-key leakage to arbitrary hosts.
    @PostConstruct
    open fun validateBaseUrl() {
        val uri = runCatching { URI.create(baseUrl) }.getOrElse {
            throw IllegalStateException("SEC-004: gateway.anthropic.base-url is not a valid URI: '$baseUrl'", it)
        }
        val scheme = uri.scheme?.lowercase()
        val host = uri.host?.lowercase() ?: ""

        val allowedHosts = allowedHostsCsv.split(",").map { it.trim().lowercase() }.filter { it.isNotBlank() }
        val localHosts = setOf("localhost", "127.0.0.1")

        val isLocal = host in localHosts
        val isAllowed = host in allowedHosts

        if (scheme == "http" && !isLocal) {
            throw IllegalStateException(
                "SEC-004: gateway.anthropic.base-url uses http scheme for non-local host '$host'. " +
                    "Only https is allowed for non-localhost hosts.",
            )
        }
        if (scheme != "https" && scheme != "http") {
            throw IllegalStateException(
                "SEC-004: gateway.anthropic.base-url has unsupported scheme '$scheme'. Only https is allowed.",
            )
        }
        if (!isAllowed && !isLocal) {
            throw IllegalStateException(
                "SEC-004: gateway.anthropic.base-url host '$host' is not in the allowlist. " +
                    "Allowed hosts: $allowedHosts. " +
                    "Override via gateway.anthropic.allowed-hosts property.",
            )
        }
        log.info("SEC-004: upstream base-url validated: scheme=$scheme host=$host")
    }

    private fun messagesUrl(): URI {
        val base = baseUrl.trimEnd('/')
        return URI.create("$base/v1/messages")
    }

    private fun buildRequest(
        body: JsonNode,
        apiKey: String,
        anthropicVersion: String,
        beta: String?,
    ): HttpRequest {
        val bodyBytes = mapper.writeValueAsBytes(body)
        val builder = HttpRequest.newBuilder()
            .uri(messagesUrl())
            .header("x-api-key", apiKey)
            .header("anthropic-version", anthropicVersion)
            .header("content-type", "application/json")
        if (!beta.isNullOrBlank()) {
            builder.header("anthropic-beta", beta)
        }
        builder.POST(HttpRequest.BodyPublishers.ofByteArray(bodyBytes))
        return builder.build()
    }

    fun send(body: JsonNode, apiKey: String, anthropicVersion: String, beta: String?): UpstreamResult {
        return try {
            val request = buildRequest(body, apiKey, anthropicVersion, beta)
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

    fun sendStream(body: JsonNode, apiKey: String, anthropicVersion: String, beta: String?): UpstreamStreamResult {
        return try {
            val request = buildRequest(body, apiKey, anthropicVersion, beta)
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
