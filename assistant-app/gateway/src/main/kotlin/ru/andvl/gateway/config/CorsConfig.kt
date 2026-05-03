package ru.andvl.gateway.config

import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Configuration
import org.springframework.web.servlet.config.annotation.CorsRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer

@Configuration
class CorsConfig(
    @Value("\${gateway.cors.allowed-origins:http://localhost:5173}") private val origins: String,
) : WebMvcConfigurer {
    override fun addCorsMappings(registry: CorsRegistry) {
        registry.addMapping("/**")
            .allowedOrigins(*origins.split(',').map { it.trim() }.toTypedArray())
            .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
            .allowedHeaders("*")
            .exposedHeaders(
                "X-Conversation-Id",
                "X-Gateway-Input-Redactions",
                "X-Gateway-Output-Redactions",
                "X-Gateway-System-Prompt-Leak",
                "X-RateLimit-Reset-Ms",
            )
    }
}
