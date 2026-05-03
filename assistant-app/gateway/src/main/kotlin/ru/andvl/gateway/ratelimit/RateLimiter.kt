package ru.andvl.gateway.ratelimit

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap

/**
 * Simple sliding-window per-IP rate limiter. Thread-safe via per-IP synchronized block on
 * the deque (cheap; each IP gets its own monitor).
 */
@Service
class RateLimiter(
    @Value("\${gateway.ratelimit.requests-per-minute:60}") private val rpm: Int,
) {

    private val windowMs = 60_000L
    private val timestamps = ConcurrentHashMap<String, ArrayDeque<Long>>()

    data class Decision(val allowed: Boolean, val remaining: Int, val resetMs: Long)

    fun check(ip: String): Decision {
        val now = System.currentTimeMillis()
        val q = timestamps.computeIfAbsent(ip) { ArrayDeque() }
        synchronized(q) {
            while (q.isNotEmpty() && now - q.peekFirst() > windowMs) q.pollFirst()
            return if (q.size < rpm) {
                q.addLast(now)
                Decision(true, rpm - q.size, windowMs)
            } else {
                val resetMs = windowMs - (now - q.peekFirst())
                Decision(false, 0, resetMs)
            }
        }
    }

    fun limitPerMinute(): Int = rpm
}
