package ru.andvl.gateway.llm

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.stereotype.Service
import org.springframework.web.client.RestClient
import org.springframework.web.client.body

@Service
class LlmClient(
    private val mapper: ObjectMapper,
    @Value("\${gateway.upstream.base-url}") private val baseUrl: String,
    @Value("\${gateway.upstream.api-key:}") private val apiKey: String,
) {

    private val log = LoggerFactory.getLogger(LlmClient::class.java)
    private val rc: RestClient by lazy {
        RestClient.builder()
            .baseUrl(baseUrl)
            .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
            .also { b -> if (apiKey.isNotBlank()) b.defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer $apiKey") }
            .build()
    }

    /** Forwards body to upstream /chat/completions. Returns full JSON response or null on failure. */
    fun chatCompletion(body: JsonNode): JsonNode? {
        return try {
            rc.post()
                .uri("/chat/completions")
                .body(body)
                .retrieve()
                .body<JsonNode>()
        } catch (e: Exception) {
            log.error("upstream call failed: {}", e.message)
            null
        }
    }
}
