package ru.andvl.advent.advenced.data.remote

import io.ktor.client.HttpClient
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.http.HttpHeaders
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

expect fun httpClientEngine(): HttpClient

fun createHttpClient(): HttpClient = httpClientEngine().config {
    install(ContentNegotiation) {
        json(
            Json {
                ignoreUnknownKeys = true
                isLenient = true
            }
        )
    }
}

fun createHttpClientWithAuth(apiKey: String): HttpClient = httpClientEngine().config {
    install(ContentNegotiation) {
        json(
            Json {
                ignoreUnknownKeys = true
                isLenient = true
            }
        )
    }
    if (apiKey.isNotBlank()) {
        defaultRequest {
            headers.append(HttpHeaders.Authorization, "Bearer $apiKey")
        }
    }
}
