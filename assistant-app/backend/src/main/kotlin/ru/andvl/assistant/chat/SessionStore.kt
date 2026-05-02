package ru.andvl.assistant.chat

import org.springframework.stereotype.Component
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

data class SessionMessage(
    val role: String,
    val content: String,
)

@Component
class SessionStore {

    private val sessions = ConcurrentHashMap<UUID, MutableList<SessionMessage>>()

    fun getOrCreate(sessionId: UUID): MutableList<SessionMessage> =
        sessions.getOrPut(sessionId) { mutableListOf() }

    fun append(sessionId: UUID, message: SessionMessage) {
        sessions.getOrPut(sessionId) { mutableListOf() }.add(message)
    }

    fun remove(sessionId: UUID) {
        sessions.remove(sessionId)
    }
}
