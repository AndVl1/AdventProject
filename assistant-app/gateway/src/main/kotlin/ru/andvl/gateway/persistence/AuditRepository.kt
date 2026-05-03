package ru.andvl.gateway.persistence

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.stereotype.Repository

@Repository
class AuditRepository(private val jdbc: JdbcTemplate) {

    private val mapper = RowMapper { rs, _ ->
        AuditLog(
            id = rs.getLong("id"),
            ts = rs.getLong("ts"),
            conversationId = rs.getString("conversation_id"),
            clientIp = rs.getString("client_ip"),
            model = rs.getString("model"),
            requestText = rs.getString("request_text"),
            redactedText = rs.getString("redacted_text"),
            responseText = rs.getString("response_text"),
            status = rs.getString("status"),
            blockReason = rs.getString("block_reason"),
            inputFindings = rs.getString("input_findings"),
            outputFindings = rs.getString("output_findings"),
            latencyMs = rs.getObject("latency_ms")?.let { (it as Number).toLong() },
        )
    }

    fun insert(log: AuditLog) {
        jdbc.update(
            """INSERT INTO audit_log(ts, conversation_id, client_ip, model, request_text, redacted_text,
                response_text, status, block_reason, input_findings, output_findings, latency_ms)
               VALUES (?,?,?,?,?,?,?,?,?,?,?,?)""".trimIndent(),
            log.ts, log.conversationId, log.clientIp, log.model, log.requestText, log.redactedText,
            log.responseText, log.status, log.blockReason, log.inputFindings, log.outputFindings, log.latencyMs,
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
