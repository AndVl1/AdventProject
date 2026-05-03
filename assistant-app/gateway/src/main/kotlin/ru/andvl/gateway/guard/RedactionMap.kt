package ru.andvl.gateway.guard

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Per-conversation map of REDACTED_<type>_<n> placeholders to their original values.
 *
 * Thread-safety: backing maps are concurrent; counters are atomic. Multiple goroutines
 * (well, threads) can call [redact] / [reverse] in parallel for the same conversation.
 *
 * No reuse across conversations: a fresh map per conversationId.
 */
class RedactionMap(val conversationId: String) {

    private data class Entry(val ruleName: String, val placeholder: String, val original: String)

    private val byPlaceholder = ConcurrentHashMap<String, Entry>()
    private val byOriginal = ConcurrentHashMap<String, String>() // original -> placeholder (dedup)
    private val counters = ConcurrentHashMap<String, AtomicInteger>()
    @Volatile var lastTouchedAt: Long = System.currentTimeMillis()
        private set

    /**
     * Returns existing placeholder if [original] was already redacted in this conversation,
     * or allocates a new one of the form `${baseTag}_<n>`.
     */
    fun redact(ruleName: String, baseTag: String, original: String): String {
        lastTouchedAt = System.currentTimeMillis()
        byOriginal[original]?.let { return it }
        // Atomically allocate next number for this baseTag
        val n = counters.computeIfAbsent(baseTag) { AtomicInteger(0) }.incrementAndGet()
        val placeholder = "${baseTag}_$n"
        // Race: two threads with same `original` may reach here simultaneously. Resolve by
        // using putIfAbsent on byOriginal — the loser releases its placeholder slot.
        val winning = byOriginal.putIfAbsent(original, placeholder)
        return if (winning == null) {
            byPlaceholder[placeholder] = Entry(ruleName, placeholder, original)
            placeholder
        } else {
            // Another thread already mapped this original; return its placeholder.
            // (We "leak" our reserved counter — acceptable; counts stay monotonic.)
            winning
        }
    }

    /** Replaces every known placeholder in [text] with its original. Unknown placeholders untouched. */
    fun reverse(text: String): String {
        if (byPlaceholder.isEmpty()) return text
        var out = text
        for ((ph, entry) in byPlaceholder) {
            if (ph in out) out = out.replace(ph, entry.original)
        }
        return out
    }

    fun snapshot(): List<Triple<String, String, String>> =
        byPlaceholder.values.map { Triple(it.ruleName, it.placeholder, it.original) }

    fun size(): Int = byPlaceholder.size
}
