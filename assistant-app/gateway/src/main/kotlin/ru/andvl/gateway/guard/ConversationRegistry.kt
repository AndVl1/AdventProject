package ru.andvl.gateway.guard

import jakarta.annotation.PostConstruct
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@Service
class ConversationRegistry(
    @Value("\${gateway.redaction.conversation-ttl-minutes:60}") private val ttlMinutes: Long,
) {

    private val maps = ConcurrentHashMap<String, RedactionMap>()
    private val scheduler = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "redaction-purger").apply { isDaemon = true }
    }

    @PostConstruct
    fun start() {
        scheduler.scheduleAtFixedRate(::purgeExpired, 5, 5, TimeUnit.MINUTES)
    }

    fun forConversation(id: String): RedactionMap =
        maps.computeIfAbsent(id) { RedactionMap(id) }

    fun get(id: String): RedactionMap? = maps[id]

    fun activeConversations(): Int = maps.size

    private fun purgeExpired() {
        val cutoff = System.currentTimeMillis() - TimeUnit.MINUTES.toMillis(ttlMinutes)
        maps.entries.removeIf { it.value.lastTouchedAt < cutoff }
    }
}
