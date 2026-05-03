package ru.andvl.gateway.api

import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import ru.andvl.gateway.guard.ConversationRegistry
import ru.andvl.gateway.guard.RedactionEngine
import ru.andvl.gateway.llm.AnthropicRoutesProperties
import ru.andvl.gateway.llm.ModelEndpointRouter
import ru.andvl.gateway.persistence.AuditLog
import ru.andvl.gateway.persistence.AuditRepository
import ru.andvl.gateway.persistence.BuiltinRulesSeeder
import ru.andvl.gateway.persistence.CostRecord
import ru.andvl.gateway.persistence.CostRepository
import ru.andvl.gateway.persistence.RedactionEvent
import ru.andvl.gateway.persistence.RedactionEventRepository
import ru.andvl.gateway.persistence.RegexRule
import ru.andvl.gateway.persistence.RegexRuleRepository
import ru.andvl.gateway.ratelimit.RateLimiter

class AdminControllerTest {

    private lateinit var fakeAudit: AdminFakeAuditRepository
    private lateinit var fakeCosts: AdminFakeCostRepository
    private lateinit var fakeRedactions: AdminFakeRedactionEventRepository
    private lateinit var fakeRules: AdminFakeRuleRepository
    private lateinit var registry: ConversationRegistry
    private lateinit var rateLimiter: RateLimiter
    private lateinit var engine: RedactionEngine
    private lateinit var router: ModelEndpointRouter
    private lateinit var controller: AdminController

    @BeforeEach
    fun setUp() {
        fakeAudit = AdminFakeAuditRepository()
        fakeCosts = AdminFakeCostRepository()
        fakeRedactions = AdminFakeRedactionEventRepository()
        fakeRules = AdminFakeRuleRepository(BuiltinRulesSeeder.BUILTIN.toMutableList())
        registry = ConversationRegistry(60L)
        rateLimiter = AdminAllowAllRateLimiter()
        val repo = fakeRules
        engine = RedactionEngine(repo).also { it.reload() }
    }

    private fun buildController(
        routesProps: AnthropicRoutesProperties = AnthropicRoutesProperties(),
        defaultUrl: String = "https://api.anthropic.com",
        allowedHosts: String = "api.anthropic.com",
    ): AdminController {
        router = ModelEndpointRouter(defaultUrl, routesProps)
        return AdminController(
            rules = fakeRules,
            audit = fakeAudit,
            redactionEvents = fakeRedactions,
            costs = fakeCosts,
            registry = registry,
            rateLimiter = rateLimiter,
            engine = engine,
            router = router,
            allowedHostsRaw = allowedHosts,
        )
    }

    // --- Test 1: GET /routes returns current config ---
    @Test
    @DisplayName("routesReturnsCurrentConfig: 2 routes in config → RoutesResponse with default + 2 routes")
    fun routesReturnsCurrentConfig() {
        val routesProps = AnthropicRoutesProperties(
            routes = listOf(
                AnthropicRoutesProperties.RouteConfig(pattern = "claude-*", baseUrl = "https://api.anthropic.com"),
                AnthropicRoutesProperties.RouteConfig(pattern = "qwen-*", baseUrl = "https://qwen.example.com"),
            ),
        )
        controller = buildController(
            routesProps = routesProps,
            allowedHosts = "api.anthropic.com,qwen.example.com",
        )

        val response = controller.routes()

        assertEquals("https://api.anthropic.com", response.default)
        assertEquals(2, response.routes.size)
        assertTrue(response.routes.any { it.pattern == "claude-*" && it.baseUrl == "https://api.anthropic.com" })
        assertTrue(response.routes.any { it.pattern == "qwen-*" && it.baseUrl == "https://qwen.example.com" })
        assertTrue(response.allowedHosts.contains("api.anthropic.com"))
        assertTrue(response.allowedHosts.contains("qwen.example.com"))
    }

    // --- Test 2: GET /audit?endpointType=openai filters by endpoint type ---
    @Test
    @DisplayName("auditFilterByEndpointType: endpointType=openai returns only openai entries")
    fun auditFilterByEndpointType() {
        controller = buildController()
        fakeAudit.addLog(makeLog(endpointType = "openai", model = "gpt-4o"))
        fakeAudit.addLog(makeLog(endpointType = "anthropic", model = "claude-opus-4-7"))
        fakeAudit.addLog(makeLog(endpointType = "openai", model = "gpt-3.5"))

        val result = controller.audit(limit = 100, endpointType = "openai", model = null)

        assertEquals(2, result.size)
        assertTrue(result.all { it.endpointType == "openai" })
        assertTrue(result.none { it.endpointType == "anthropic" })
    }

    // --- Test 3: GET /audit?model=claude-* filters by glob ---
    @Test
    @DisplayName("auditFilterByModelGlob: model=claude-* returns only claude-* records")
    fun auditFilterByModelGlob() {
        controller = buildController()
        fakeAudit.addLog(makeLog(endpointType = "anthropic", model = "claude-opus-4-7"))
        fakeAudit.addLog(makeLog(endpointType = "anthropic", model = "claude-sonnet-4-6"))
        fakeAudit.addLog(makeLog(endpointType = "openai", model = "gpt-4o"))

        val result = controller.audit(limit = 100, endpointType = null, model = "claude-*")

        assertEquals(2, result.size)
        assertTrue(result.all { it.model?.startsWith("claude-") == true })
    }

    // --- Test 4: GET /stats includes byModelAudit breakdown ---
    @Test
    @DisplayName("statsIncludesByModelBreakdown: stats response contains byModelAudit section")
    fun statsIncludesByModelBreakdown() {
        controller = buildController()
        // seed audit logs for breakdown
        repeat(3) { fakeAudit.addLog(makeLog(endpointType = "anthropic", model = "claude-opus-4-7", latencyMs = 1000)) }
        repeat(2) { fakeAudit.addLog(makeLog(endpointType = "openai", model = "gpt-4o", latencyMs = 700)) }

        val stats = controller.stats()

        assertNotNull(stats.byModelAudit)
        assertTrue(stats.byModelAudit.isNotEmpty(), "byModelAudit should not be empty")

        // find claude entry
        val claudeEntry = stats.byModelAudit.firstOrNull { it["model"] == "claude-opus-4-7" }
        assertNotNull(claudeEntry, "should have claude-opus-4-7 in breakdown")
        assertEquals("anthropic", claudeEntry!!["endpointType"])
        assertEquals(3L, claudeEntry["count"])

        // find gpt entry
        val gptEntry = stats.byModelAudit.firstOrNull { it["model"] == "gpt-4o" }
        assertNotNull(gptEntry, "should have gpt-4o in breakdown")
        assertEquals("openai", gptEntry!!["endpointType"])
        assertEquals(2L, gptEntry["count"])
    }

    private fun makeLog(
        endpointType: String? = null,
        model: String? = null,
        latencyMs: Long? = null,
    ) = AuditLog(
        conversationId = null,
        clientIp = "127.0.0.1",
        model = model,
        requestText = null,
        responseText = null,
        status = "OK",
        endpointType = endpointType,
        latencyMs = latencyMs,
    )
}

// --- Fake implementations for AdminControllerTest ---

private class AdminFakeAuditRepository : AuditRepository(org.springframework.jdbc.core.JdbcTemplate()) {
    private val logs = mutableListOf<AuditLog>()

    fun addLog(log: AuditLog) = logs.add(log)

    override fun insert(audit: AuditLog) { logs.add(audit) }
    override fun migrate() { /* no-op */ }

    override fun recent(limit: Int, endpointType: String?, modelFilter: String?): List<AuditLog> {
        var result = logs.asSequence()
        if (endpointType != null) result = result.filter { it.endpointType == endpointType }
        if (modelFilter != null) {
            val pattern = modelFilter.replace("*", ".*").replace("?", ".")
            val regex = Regex(pattern)
            result = result.filter { it.model?.let { m -> regex.matches(m) } ?: false }
        }
        return result.toList().takeLast(limit)
    }

    override fun stats(): Map<String, Long> =
        logs.groupBy { it.status }.mapValues { it.value.size.toLong() }

    override fun byModelBreakdown(): List<Map<String, Any?>> {
        return logs
            .filter { it.model != null }
            .groupBy { it.model to it.endpointType }
            .map { (key, entries) ->
                mapOf(
                    "model" to key.first,
                    "endpointType" to key.second,
                    "count" to entries.size.toLong(),
                    "avgLatencyMs" to entries.mapNotNull { it.latencyMs }.takeIf { it.isNotEmpty() }
                        ?.let { it.sum() / it.size },
                )
            }
            .sortedByDescending { (it["count"] as? Long) ?: 0L }
    }
}

private class AdminFakeCostRepository : CostRepository(org.springframework.jdbc.core.JdbcTemplate()) {
    val records = mutableListOf<CostRecord>()

    override fun insert(rec: CostRecord) { records.add(rec) }

    override fun totals(): CostRepository.Totals = CostRepository.Totals(
        totalTokens = records.sumOf { it.totalTokens.toLong() },
        totalCostUsd = records.sumOf { it.costUsd },
        requests = records.size.toLong(),
    )

    override fun byModel(): List<Map<String, Any>> = emptyList()
}

private class AdminFakeRedactionEventRepository :
    RedactionEventRepository(org.springframework.jdbc.core.JdbcTemplate()) {
    private val events = mutableListOf<RedactionEvent>()

    override fun insert(ev: RedactionEvent) { events.add(ev) }
    override fun recent(limit: Int): List<RedactionEvent> = events.takeLast(limit)
    override fun countByRule(): Map<String, Long> =
        events.groupBy { it.ruleName }.mapValues { it.value.size.toLong() }
}

private class AdminFakeRuleRepository(private val rules: MutableList<RegexRule>) :
    RegexRuleRepository(org.springframework.jdbc.core.JdbcTemplate()) {
    override fun findEnabled(): List<RegexRule> = rules.filter { it.enabled }
    override fun findAll(): List<RegexRule> = rules
    override fun findByName(name: String): RegexRule? = rules.firstOrNull { it.name == name }
    override fun insert(rule: RegexRule): Long {
        val id = (rules.maxOfOrNull { it.id ?: 0 } ?: 0) + 1
        rules += rule.copy(id = id)
        return id
    }
    override fun update(rule: RegexRule): Int {
        val idx = rules.indexOfFirst { it.id == rule.id }
        if (idx < 0) return 0
        rules[idx] = rule
        return 1
    }
    override fun delete(id: Long): Int {
        val before = rules.size
        rules.removeIf { it.id == id && !it.builtin }
        return before - rules.size
    }
}

private class AdminAllowAllRateLimiter : RateLimiter(rpm = 10000) {
    override fun check(ip: String): Decision = Decision(allowed = true, remaining = 9999, resetMs = 60000)
    override fun limitPerMinute(): Int = 10000
}
