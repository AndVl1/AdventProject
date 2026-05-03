package ru.andvl.gateway.api

import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import ru.andvl.gateway.guard.ConversationRegistry
import ru.andvl.gateway.guard.RedactionEngine
import ru.andvl.gateway.persistence.AuditLog
import ru.andvl.gateway.persistence.AuditRepository
import ru.andvl.gateway.persistence.CostRepository
import ru.andvl.gateway.persistence.RedactionEvent
import ru.andvl.gateway.persistence.RedactionEventRepository
import ru.andvl.gateway.persistence.RegexRule
import ru.andvl.gateway.persistence.RegexRuleRepository
import ru.andvl.gateway.ratelimit.RateLimiter

data class StatsResponse(
    val requests: Map<String, Long>,
    val totalCostUsd: Double,
    val totalTokens: Long,
    val byModel: List<Map<String, Any>>,
    val redactionsByRule: Map<String, Long>,
    val activeConversations: Int,
    val rateLimitRpm: Int,
)

data class RuleUpsertRequest(
    val name: String,
    val pattern: String,
    val category: String,
    val placeholder: String,
    val enabled: Boolean = true,
)

@RestController
@RequestMapping("/api/admin")
class AdminController(
    private val rules: RegexRuleRepository,
    private val audit: AuditRepository,
    private val redactionEvents: RedactionEventRepository,
    private val costs: CostRepository,
    private val registry: ConversationRegistry,
    private val rateLimiter: RateLimiter,
    private val engine: RedactionEngine,
) {

    @GetMapping("/stats")
    fun stats(): StatsResponse {
        val totals = costs.totals()
        return StatsResponse(
            requests = audit.stats(),
            totalCostUsd = totals.totalCostUsd,
            totalTokens = totals.totalTokens,
            byModel = costs.byModel(),
            redactionsByRule = redactionEvents.countByRule(),
            activeConversations = registry.activeConversations(),
            rateLimitRpm = rateLimiter.limitPerMinute(),
        )
    }

    @GetMapping("/rules")
    fun listRules(): List<RegexRule> = rules.findAll()

    @PostMapping("/rules")
    fun createRule(@RequestBody body: RuleUpsertRequest): ResponseEntity<RegexRule> {
        validatePattern(body.pattern)?.let { return ResponseEntity.badRequest().build() }
        val id = rules.insert(
            RegexRule(
                name = body.name, pattern = body.pattern, category = body.category,
                placeholder = body.placeholder, enabled = body.enabled, builtin = false,
            ),
        )
        engine.reload()
        return ResponseEntity.status(HttpStatus.CREATED).body(rules.findAll().first { it.id == id })
    }

    @PutMapping("/rules/{id}")
    fun updateRule(@PathVariable id: Long, @RequestBody body: RuleUpsertRequest): ResponseEntity<Void> {
        validatePattern(body.pattern)?.let { return ResponseEntity.badRequest().build() }
        val n = rules.update(
            RegexRule(
                id = id, name = body.name, pattern = body.pattern, category = body.category,
                placeholder = body.placeholder, enabled = body.enabled,
            ),
        )
        if (n == 0) return ResponseEntity.notFound().build()
        engine.reload()
        return ResponseEntity.noContent().build()
    }

    @DeleteMapping("/rules/{id}")
    fun deleteRule(@PathVariable id: Long): ResponseEntity<Void> {
        val n = rules.delete(id)
        if (n == 0) return ResponseEntity.status(HttpStatus.CONFLICT).build()  // builtin or not found
        engine.reload()
        return ResponseEntity.noContent().build()
    }

    @GetMapping("/audit")
    fun audit(@RequestParam(defaultValue = "100") limit: Int): List<AuditLog> = audit.recent(limit)

    @GetMapping("/redactions")
    fun redactions(@RequestParam(defaultValue = "100") limit: Int): List<RedactionEvent> =
        redactionEvents.recent(limit)

    private fun validatePattern(pattern: String): String? = try {
        Regex(pattern); null
    } catch (e: Exception) {
        e.message ?: "invalid regex"
    }
}
