package ru.andvl.advent.advenced.data.remote

import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin

actual fun httpClientEngine(): HttpClient = HttpClient(Darwin)
