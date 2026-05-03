package ru.andvl.gateway.llm

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class ModelEndpointRouterTest {

    private val defaultUrl = "https://api.anthropic.com"

    private fun router(vararg routes: Pair<String, String>): ModelEndpointRouter {
        val props = AnthropicRoutesProperties(
            routes = routes.map { (pattern, url) ->
                AnthropicRoutesProperties.RouteConfig(pattern = pattern, baseUrl = url)
            },
        )
        return ModelEndpointRouter(defaultUrl, props)
    }

    @Test
    @DisplayName("resolveDefaultWhenNoRoutes: empty routes → returns default")
    fun resolveDefaultWhenNoRoutes() {
        val r = router()
        assertEquals(defaultUrl, r.resolve("claude-opus-4-7"))
    }

    @Test
    @DisplayName("resolveExactMatch: exact pattern matches precisely")
    fun resolveExactMatch() {
        val target = "https://exact.example.com"
        val r = router("claude-3.5-sonnet" to target)
        assertEquals(target, r.resolve("claude-3.5-sonnet"))
    }

    @Test
    @DisplayName("resolveGlobMatch: claude-* matches claude-opus-4-7")
    fun resolveGlobMatch() {
        val target = "https://claude.example.com"
        val r = router("claude-*" to target)
        assertEquals(target, r.resolve("claude-opus-4-7"))
    }

    @Test
    @DisplayName("resolveFirstWinsOnMultipleMatches: order in config decides")
    fun resolveFirstWinsOnMultipleMatches() {
        val first = "https://first.example.com"
        val second = "https://second.example.com"
        val r = router("claude-*" to first, "claude-opus*" to second)
        // claude-opus-4-7 matches both, first wins
        assertEquals(first, r.resolve("claude-opus-4-7"))
    }

    @Test
    @DisplayName("resolveFallbackToDefaultWhenNoMatch: unknown model → default")
    fun resolveFallbackToDefaultWhenNoMatch() {
        val r = router("claude-*" to "https://claude.example.com")
        assertEquals(defaultUrl, r.resolve("gpt-4"))
    }

    @Test
    @DisplayName("resolveNullOrBlankModel: null → default")
    fun resolveNullModel() {
        val r = router("claude-*" to "https://claude.example.com")
        assertEquals(defaultUrl, r.resolve(null))
    }

    @Test
    @DisplayName("resolveNullOrBlankModel: blank → default")
    fun resolveBlankModel() {
        val r = router("claude-*" to "https://claude.example.com")
        assertEquals(defaultUrl, r.resolve("   "))
    }

    @Test
    @DisplayName("allBaseUrlsIncludesDefaultAndRoutes")
    fun allBaseUrlsIncludesDefaultAndRoutes() {
        val qwenUrl = "https://qwen.example.com"
        val claudeUrl = "https://claude.example.com"
        val r = router("qwen-*" to qwenUrl, "claude-*" to claudeUrl)
        val all = r.allBaseUrls()
        assertTrue(all.contains(defaultUrl), "should contain default url")
        assertTrue(all.contains(qwenUrl), "should contain qwen url")
        assertTrue(all.contains(claudeUrl), "should contain claude url")
        assertEquals(3, all.size, "should have 3 distinct urls")
    }

    @Test
    @DisplayName("globQuestionMarkMatchesSingleChar: ? matches exactly one character")
    fun globQuestionMarkMatchesSingleChar() {
        val target = "https://target.example.com"
        val r = router("claude-?" to target)
        assertEquals(target, r.resolve("claude-3"), "single char should match")
        assertEquals(defaultUrl, r.resolve("claude-35"), "two chars should NOT match")
    }

    @Test
    @DisplayName("resolve is case-insensitive: Claude-Opus matches claude-*")
    fun resolveIsCaseInsensitive() {
        val target = "https://claude.example.com"
        val r = router("claude-*" to target)
        assertEquals(target, r.resolve("Claude-Opus-4-7"))
    }

    @Test
    @DisplayName("globToRegex: * matches empty string")
    fun globStarMatchesEmpty() {
        val regex = ModelEndpointRouter.globToRegex("claude-*")
        assertTrue(regex.matches("claude-"))
    }
}
