package ru.andvl.gateway.guard

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Stress-test the per-conversation map under concurrent input. Same secret across
 * threads must collapse to one placeholder; distinct secrets must each get distinct ones.
 */
class RedactionMapConcurrencyTest {

    @Test
    fun `same value across threads collapses to one placeholder`() {
        val map = RedactionMap("conv")
        val pool = Executors.newFixedThreadPool(16)
        try {
            val tasks = (1..1000).map {
                Runnable { map.redact("rule", "REDACTED_X", "same-secret-value") }
            }
            tasks.forEach { pool.submit(it) }
        } finally {
            pool.shutdown()
            pool.awaitTermination(10, TimeUnit.SECONDS)
        }
        assertEquals(1, map.size(), "exactly one placeholder for one unique secret")
    }

    @Test
    fun `distinct values get distinct placeholders`() {
        val map = RedactionMap("conv")
        val pool = Executors.newFixedThreadPool(16)
        try {
            val tasks = (1..500).map { i ->
                Runnable { map.redact("rule", "REDACTED_X", "secret-$i") }
            }
            tasks.forEach { pool.submit(it) }
        } finally {
            pool.shutdown()
            pool.awaitTermination(10, TimeUnit.SECONDS)
        }
        assertEquals(500, map.size())
        // verify all placeholders are unique
        val placeholders = map.snapshot().map { it.second }.toSet()
        assertEquals(500, placeholders.size, "all placeholders unique")
        // and all start with the base tag
        assertTrue(placeholders.all { it.startsWith("REDACTED_X_") })
    }

    @Test
    fun `reverse round-trip restores the original`() {
        val map = RedactionMap("conv")
        val ph = map.redact("rule", "REDACTED_X", "AKIA-FOO-BAR")
        val text = "the value $ph was here"
        assertEquals("the value AKIA-FOO-BAR was here", map.reverse(text))
    }
}
