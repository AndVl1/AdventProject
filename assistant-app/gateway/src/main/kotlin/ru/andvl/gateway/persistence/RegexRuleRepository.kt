package ru.andvl.gateway.persistence

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.support.GeneratedKeyHolder
import org.springframework.stereotype.Repository
import java.sql.PreparedStatement
import java.sql.Statement

@Repository
class RegexRuleRepository(private val jdbc: JdbcTemplate) {

    private val mapper = RowMapper { rs, _ ->
        RegexRule(
            id = rs.getLong("id"),
            name = rs.getString("name"),
            pattern = rs.getString("pattern"),
            category = rs.getString("category"),
            placeholder = rs.getString("placeholder"),
            enabled = rs.getInt("enabled") == 1,
            builtin = rs.getInt("builtin") == 1,
            createdAt = rs.getLong("created_at"),
        )
    }

    fun findAll(): List<RegexRule> =
        jdbc.query("SELECT * FROM regex_rule ORDER BY id", mapper)

    fun findEnabled(): List<RegexRule> =
        jdbc.query("SELECT * FROM regex_rule WHERE enabled = 1 ORDER BY id", mapper)

    fun findByName(name: String): RegexRule? =
        jdbc.query("SELECT * FROM regex_rule WHERE name = ?", mapper, name).firstOrNull()

    fun insert(rule: RegexRule): Long {
        val kh = GeneratedKeyHolder()
        jdbc.update({ con ->
            val ps: PreparedStatement = con.prepareStatement(
                "INSERT INTO regex_rule(name, pattern, category, placeholder, enabled, builtin, created_at) VALUES (?,?,?,?,?,?,?)",
                Statement.RETURN_GENERATED_KEYS,
            )
            ps.setString(1, rule.name)
            ps.setString(2, rule.pattern)
            ps.setString(3, rule.category)
            ps.setString(4, rule.placeholder)
            ps.setInt(5, if (rule.enabled) 1 else 0)
            ps.setInt(6, if (rule.builtin) 1 else 0)
            ps.setLong(7, rule.createdAt)
            ps
        }, kh)
        return kh.key?.toLong() ?: error("no generated key")
    }

    fun update(rule: RegexRule): Int {
        val id = rule.id ?: error("id required")
        return jdbc.update(
            "UPDATE regex_rule SET name=?, pattern=?, category=?, placeholder=?, enabled=? WHERE id=?",
            rule.name, rule.pattern, rule.category, rule.placeholder, if (rule.enabled) 1 else 0, id,
        )
    }

    fun delete(id: Long): Int =
        jdbc.update("DELETE FROM regex_rule WHERE id = ? AND builtin = 0", id)
}
