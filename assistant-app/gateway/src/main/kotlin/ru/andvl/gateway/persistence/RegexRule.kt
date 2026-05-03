package ru.andvl.gateway.persistence

data class RegexRule(
    val id: Long? = null,
    val name: String,
    val pattern: String,
    val category: String,        // API_KEY | PII | CUSTOM
    val placeholder: String,
    val enabled: Boolean = true,
    val builtin: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
)
