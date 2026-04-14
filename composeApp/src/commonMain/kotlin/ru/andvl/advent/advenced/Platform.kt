package ru.andvl.advent.advenced

interface Platform {
    val name: String
}

expect fun getPlatform(): Platform