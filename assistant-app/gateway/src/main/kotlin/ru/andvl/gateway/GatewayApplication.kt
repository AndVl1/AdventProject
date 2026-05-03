package ru.andvl.gateway

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.runApplication
import ru.andvl.gateway.llm.AnthropicRoutesProperties

@SpringBootApplication
@EnableConfigurationProperties(AnthropicRoutesProperties::class)
class GatewayApplication

fun main(args: Array<String>) {
    runApplication<GatewayApplication>(*args)
}
