package ru.andvl.gateway.llm

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "gateway.anthropic")
data class AnthropicRoutesProperties(
    var routes: List<RouteConfig> = emptyList(),
) {
    data class RouteConfig(
        var pattern: String = "",
        var baseUrl: String = "",
    )
}
