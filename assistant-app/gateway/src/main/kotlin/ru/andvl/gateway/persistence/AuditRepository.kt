package ru.andvl.gateway.persistence

import jakarta.annotation.PostConstruct
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.stereotype.Repository

@Repository
class AuditRepository(private val jdbc: JdbcTemplate) {

    private val log = LoggerFactory.getLogger(AuditRepository::class.java)

    @PostConstruct
    fun migrate() {
        // SQLite не умеет ALTER TABLE ADD COLUMN IF NOT EXISTS — гоняем под try/catch.
        // Идемпотентно: на свежей БД колонки уже есть из schema.sql, ALTER упадёт → ловим.
        ensureColumn("audit_log", "upstream_request_json", "TEXT")
        ensureColumn("audit_log", "upstream_response_json", "TEXT")
    }

    private fun ensureColumn(table: String, col: String, type: String) {
        runCatching { jdbc.execute("ALTER TABLE $table ADD COLUMN $col $type") }
            .onSuccess { log.info("migrated: added column {}.{}", table, col) }
            .onFailure { /* колонка уже есть */ }
    }

    private val mapper = RowMapper { rs, _ ->
        AuditLog(
            id = rs.getLong("id"),
            ts = rs.getLong("ts"),
            conversationId = rs.getString("conversation_id"),
            clientIp = rs.getString("client_ip"),
            model = rs.getString("model"),
            requestText = rs.getString("request_text"),
            responseText = rs.getString("response_text"),
            status = rs.getString("status"),
            blockReason = rs.getString("block_reason"),
            inputFindings = rs.getString("input_findings"),
            outputFindings = rs.getString("output_findings"),
            latencyMs = rs.getObject("latency_ms")?.let { (it as Number).toLong() },
            upstreamRequestJson = rs.getString("upstream_request_json"),
            upstreamResponseJson = rs.getString("upstream_response_json"),
        )
    }

    fun insert(audit: AuditLog) {
        // redacted_text — deprecated колонка, оставлена в schema.sql для совместимости старых строк, не пишется.
        jdbc.update(
            """INSERT INTO audit_log(ts, conversation_id, client_ip, model, request_text,
                response_text, status, block_reason, input_findings, output_findings, latency_ms,
                upstream_request_json, upstream_response_json)
               VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)""".trimIndent(),
            audit.ts, audit.conversationId, audit.clientIp, audit.model, audit.requestText,
            audit.responseText, audit.status, audit.blockReason, audit.inputFindings, audit.outputFindings,
            audit.latencyMs, audit.upstreamRequestJson, audit.upstreamResponseJson,
        )
    }

    fun recent(limit: Int = 100): List<AuditLog> =
        jdbc.query("SELECT * FROM audit_log ORDER BY ts DESC LIMIT ?", mapper, limit)

    fun stats(): Map<String, Long> {
        val counts = jdbc.queryForList(
            "SELECT status, COUNT(*) AS c FROM audit_log GROUP BY status",
        ).associate { (it["status"] as String) to (it["c"] as Number).toLong() }
        return counts
    }
}

@Repository
class RedactionEventRepository(private val jdbc: JdbcTemplate) {

    private val mapper = RowMapper { rs, _ ->
        RedactionEvent(
            id = rs.getLong("id"),
            ts = rs.getLong("ts"),
            conversationId = rs.getString("conversation_id"),
            direction = rs.getString("direction"),
            ruleName = rs.getString("rule_name"),
            placeholder = rs.getString("placeholder"),
            originalHash = rs.getString("original_hash"),
        )
    }

    fun insert(ev: RedactionEvent) {
        jdbc.update(
            "INSERT INTO redaction_event(ts, conversation_id, direction, rule_name, placeholder, original_hash) VALUES (?,?,?,?,?,?)",
            ev.ts, ev.conversationId, ev.direction, ev.ruleName, ev.placeholder, ev.originalHash,
        )
    }

    fun recent(limit: Int = 100): List<RedactionEvent> =
        jdbc.query("SELECT * FROM redaction_event ORDER BY ts DESC LIMIT ?", mapper, limit)

    fun countByRule(): Map<String, Long> =
        jdbc.queryForList("SELECT rule_name, COUNT(*) AS c FROM redaction_event GROUP BY rule_name")
            .associate { (it["rule_name"] as String) to (it["c"] as Number).toLong() }
}

@Repository
class CostRepository(private val jdbc: JdbcTemplate) {

    fun insert(rec: CostRecord) {
        jdbc.update(
            "INSERT INTO cost_record(ts, conversation_id, model, prompt_tokens, completion_tokens, total_tokens, cost_usd) VALUES (?,?,?,?,?,?,?)",
            rec.ts, rec.conversationId, rec.model, rec.promptTokens, rec.completionTokens, rec.totalTokens, rec.costUsd,
        )
    }

    data class Totals(val totalTokens: Long, val totalCostUsd: Double, val requests: Long)

    fun totals(): Totals {
        val row = jdbc.queryForMap(
            "SELECT COALESCE(SUM(total_tokens),0) AS tt, COALESCE(SUM(cost_usd),0.0) AS tc, COUNT(*) AS rc FROM cost_record",
        )
        return Totals(
            totalTokens = (row["tt"] as Number).toLong(),
            totalCostUsd = (row["tc"] as Number).toDouble(),
            requests = (row["rc"] as Number).toLong(),
        )
    }

    fun byModel(): List<Map<String, Any>> =
        jdbc.queryForList(
            """SELECT model, COUNT(*) AS requests, SUM(total_tokens) AS tokens, SUM(cost_usd) AS cost
               FROM cost_record GROUP BY model ORDER BY cost DESC""".trimIndent(),
        )
}
