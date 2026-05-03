package ru.andvl.gateway.cost

import org.springframework.stereotype.Service

/** USD per 1K tokens, indicative — kept inline so it works offline. */
@Service
class CostTable {

    private data class Pricing(val promptPer1k: Double, val completionPer1k: Double)

    private val table = mapOf(
        "openai/gpt-4o-mini"                    to Pricing(0.00015, 0.0006),
        "openai/gpt-4o"                         to Pricing(0.005, 0.015),
        "anthropic/claude-3.5-sonnet"           to Pricing(0.003, 0.015),
        "anthropic/claude-3-haiku"              to Pricing(0.00025, 0.00125),
        "qwen/qwen-2.5-7b-instruct"             to Pricing(0.00018, 0.00018),
        "qwen/qwen3-235b-a22b-2507"             to Pricing(0.00088, 0.00088),
        // claude-4.x direct models
        "claude-opus-4-7"                       to Pricing(0.015, 0.075),
        "anthropic/claude-opus-4-7"             to Pricing(0.015, 0.075),
        "claude-sonnet-4-6"                     to Pricing(0.003, 0.015),
        "anthropic/claude-sonnet-4-6"           to Pricing(0.003, 0.015),
        "claude-haiku-4-5"                      to Pricing(0.0008, 0.004),
        "anthropic/claude-haiku-4-5"            to Pricing(0.0008, 0.004),
    )
    private val defaultClaude = Pricing(0.003, 0.015)
    private val default = Pricing(0.001, 0.002)

    fun estimateUsd(model: String, promptTokens: Int, completionTokens: Int): Double {
        val p = table[model]
            ?: table.entries.firstOrNull { model.startsWith(it.key.substringBefore('/')) }?.value
            ?: if (model.startsWith("claude-") || model.startsWith("anthropic/claude-")) defaultClaude
            else default
        return (promptTokens / 1000.0) * p.promptPer1k + (completionTokens / 1000.0) * p.completionPer1k
    }
}
