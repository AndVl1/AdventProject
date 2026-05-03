package ru.andvl.gateway.llm

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service

@Service
class ModelEndpointRouter(
    @Value("\${gateway.anthropic.base-url:https://api.anthropic.com}")
    private val defaultBaseUrl: String,
    routesProperties: AnthropicRoutesProperties,
) {

    data class Route(val pattern: String, val baseUrl: String, val regex: Regex)

    private val routes: List<Route> = routesProperties.routes.map { cfg ->
        Route(
            pattern = cfg.pattern,
            baseUrl = cfg.baseUrl,
            regex = globToRegex(cfg.pattern),
        )
    }

    fun resolve(model: String?): String {
        if (model.isNullOrBlank()) return defaultBaseUrl
        val lower = model.lowercase()
        return routes.firstOrNull { it.regex.matches(lower) }?.baseUrl ?: defaultBaseUrl
    }

    fun allBaseUrls(): Set<String> = (routes.map { it.baseUrl } + defaultBaseUrl).toSet()

    data class RouteInfo(val pattern: String, val baseUrl: String)

    fun routeInfoList(): List<RouteInfo> = routes.map { RouteInfo(it.pattern, it.baseUrl) }

    fun defaultUrl(): String = defaultBaseUrl

    companion object {
        /**
         * Converts a glob pattern to a Regex.
         * '*' → '.*', '?' → '.', everything else is quoted.
         * Comparison is case-insensitive (patterns are lowercased before compiling).
         */
        internal fun globToRegex(pattern: String): Regex {
            val sb = StringBuilder("^")
            for (ch in pattern.lowercase()) {
                when (ch) {
                    '*' -> sb.append(".*")
                    '?' -> sb.append(".")
                    else -> sb.append(Regex.escape(ch.toString()))
                }
            }
            sb.append("$")
            return Regex(sb.toString())
        }
    }
}
