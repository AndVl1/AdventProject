package ru.andvl.assistant.config

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration

@ConfigurationProperties(prefix = "gmail")
data class GmailProperties(
    val clientId: String = "",
    val clientSecret: String = "",
    val refreshToken: String = "",
    val userEmail: String = "",
)

@Configuration
@EnableConfigurationProperties(GmailProperties::class)
class GmailConfig
