package ru.andvl.gateway.api

import jakarta.servlet.http.HttpServletRequest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class ConnectionInfoControllerTest {

    @Test
    @DisplayName("publicBaseUrl override wins over headers")
    fun publicBaseUrlOverrideWins() {
        val controller = ConnectionInfoController(publicBaseUrl = "https://gw.example.com")
        val req = fakeRequest(headers = mapOf("Host" to "ignored:1234", "X-Forwarded-Host" to "fwd:9090", "X-Forwarded-Proto" to "http"))

        val resp = controller.connectionInfo(req)

        assertEquals("https://gw.example.com", resp.baseUrl)
        assertEquals("config:public-base-url", resp.source)
        assertEquals("https://gw.example.com/v1/messages", resp.anthropicEndpoint)
        assertEquals("https://gw.example.com/v1", resp.openaiBaseUrl)
        assertTrue(resp.examples.claudeCodeEnv.contains("https://gw.example.com"))
    }

    @Test
    @DisplayName("X-Forwarded-Proto + X-Forwarded-Host used when public-base-url empty")
    fun forwardedHeadersUsedWhenOverrideEmpty() {
        val controller = ConnectionInfoController(publicBaseUrl = "")
        val req = fakeRequest(
            scheme = "http",
            headers = mapOf(
                "Host" to "internal:8091",
                "X-Forwarded-Proto" to "https",
                "X-Forwarded-Host" to "api.public.example.com",
            ),
        )

        val resp = controller.connectionInfo(req)

        assertEquals("https://api.public.example.com", resp.baseUrl)
        assertEquals("header:x-forwarded", resp.source)
    }

    @Test
    @DisplayName("Host header fallback when no override and no forwarded headers")
    fun hostHeaderFallback() {
        val controller = ConnectionInfoController(publicBaseUrl = "")
        val req = fakeRequest(scheme = "http", headers = mapOf("Host" to "localhost:8091"))

        val resp = controller.connectionInfo(req)

        assertEquals("http://localhost:8091", resp.baseUrl)
        assertEquals("header:host", resp.source)
    }

    @Test
    @DisplayName("trailing slash stripped from publicBaseUrl override")
    fun trailingSlashStripped() {
        val controller = ConnectionInfoController(publicBaseUrl = "https://gw.example.com/")
        val req = fakeRequest(headers = mapOf("Host" to "x"))

        val resp = controller.connectionInfo(req)

        assertEquals("https://gw.example.com", resp.baseUrl)
        assertEquals("https://gw.example.com/v1/messages", resp.anthropicEndpoint)
    }

    @Test
    @DisplayName("examples contain copy-pasteable snippets for all clients")
    fun examplesContainAllClients() {
        val controller = ConnectionInfoController(publicBaseUrl = "https://x.io")
        val resp = controller.connectionInfo(fakeRequest(headers = mapOf("Host" to "x")))

        assertTrue(resp.examples.claudeCodeEnv.contains("ANTHROPIC_BASE_URL=https://x.io"))
        assertTrue(resp.examples.curlAnthropic.contains("https://x.io/v1/messages"))
        assertTrue(resp.examples.openaiSdkPython.contains("base_url=\"https://x.io/v1\""))
        assertTrue(resp.examples.openaiSdkNode.contains("baseURL: \"https://x.io/v1\""))
    }

    private fun fakeRequest(scheme: String = "http", headers: Map<String, String> = emptyMap()): HttpServletRequest {
        return java.lang.reflect.Proxy.newProxyInstance(
            HttpServletRequest::class.java.classLoader,
            arrayOf(HttpServletRequest::class.java),
        ) { _, method, args ->
            when (method.name) {
                "getHeader" -> headers[args[0] as String]
                "getScheme" -> scheme
                "getServerName" -> "localhost"
                "getServerPort" -> 8091
                else -> null
            }
        } as HttpServletRequest
    }
}
