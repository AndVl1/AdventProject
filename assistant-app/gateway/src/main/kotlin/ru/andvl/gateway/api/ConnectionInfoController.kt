package ru.andvl.gateway.api

import jakarta.servlet.http.HttpServletRequest
import org.springframework.beans.factory.annotation.Value
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

data class ConnectionExamples(
    val claudeCodeEnv: String,
    val curlAnthropic: String,
    val openaiSdkPython: String,
    val openaiSdkNode: String,
)

data class ConnectionInfoResponse(
    val baseUrl: String,
    val source: String,
    val anthropicEndpoint: String,
    val openaiBaseUrl: String,
    val examples: ConnectionExamples,
)

@RestController
@RequestMapping("/api/admin")
class ConnectionInfoController(
    @Value("\${gateway.public-base-url:}")
    private val publicBaseUrl: String,
) {

    @GetMapping("/connection-info")
    fun connectionInfo(req: HttpServletRequest): ConnectionInfoResponse {
        val (baseUrl, source) = resolveBaseUrl(req)
        return ConnectionInfoResponse(
            baseUrl = baseUrl,
            source = source,
            anthropicEndpoint = "$baseUrl/v1/messages",
            openaiBaseUrl = "$baseUrl/v1",
            examples = examples(baseUrl),
        )
    }

    private fun resolveBaseUrl(req: HttpServletRequest): Pair<String, String> {
        if (publicBaseUrl.isNotBlank()) {
            return publicBaseUrl.trimEnd('/') to "config:public-base-url"
        }
        val fwdProto = req.getHeader("X-Forwarded-Proto")?.split(",")?.firstOrNull()?.trim()
        val fwdHost = req.getHeader("X-Forwarded-Host")?.split(",")?.firstOrNull()?.trim()
        if (!fwdProto.isNullOrBlank() && !fwdHost.isNullOrBlank()) {
            return "$fwdProto://$fwdHost" to "header:x-forwarded"
        }
        val scheme = req.scheme ?: "http"
        val host = req.getHeader("Host") ?: "${req.serverName}:${req.serverPort}"
        return "$scheme://$host" to "header:host"
    }

    private fun examples(baseUrl: String) = ConnectionExamples(
        claudeCodeEnv = """
            export ANTHROPIC_BASE_URL=$baseUrl
            export ANTHROPIC_API_KEY=sk-ant-...
            claude
        """.trimIndent(),
        curlAnthropic = """
            curl -N $baseUrl/v1/messages \
              -H "x-api-key: ${'$'}ANTHROPIC_API_KEY" \
              -H "anthropic-version: 2023-06-01" \
              -H "content-type: application/json" \
              -d '{"model":"claude-sonnet-4-6","max_tokens":256,"stream":true,"messages":[{"role":"user","content":"hi"}]}'
        """.trimIndent(),
        openaiSdkPython = """
            from openai import OpenAI
            client = OpenAI(base_url="$baseUrl/v1", api_key="sk-...")
            r = client.chat.completions.create(
                model="openai/gpt-4o-mini",
                messages=[{"role":"user","content":"hi"}],
            )
        """.trimIndent(),
        openaiSdkNode = """
            import OpenAI from "openai";
            const client = new OpenAI({ baseURL: "$baseUrl/v1", apiKey: process.env.OPENAI_API_KEY });
            const r = await client.chat.completions.create({
              model: "openai/gpt-4o-mini",
              messages: [{ role: "user", content: "hi" }],
            });
        """.trimIndent(),
    )
}
