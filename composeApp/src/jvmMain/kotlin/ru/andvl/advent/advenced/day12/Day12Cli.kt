package ru.andvl.advent.advenced.day12

import kotlinx.coroutines.runBlocking
import ru.andvl.advent.advenced.data.remote.createHttpClientWithAuth
import ru.andvl.advent.advenced.data.remote.openrouter.OpenRouterChatApi
import ru.andvl.advent.advenced.data.repository.ChatRepositoryImpl
import ru.andvl.advent.advenced.day12.agents.AgentPrompts
import ru.andvl.advent.advenced.day12.agents.Day12Agent
import ru.andvl.advent.advenced.day12.defense.DefenseConfig
import ru.andvl.advent.advenced.day12.defense.InputSanitizer
import ru.andvl.advent.advenced.day12.defense.OutputValidator
import ru.andvl.advent.advenced.day12.fixtures.DOC_ZERO_WIDTH
import ru.andvl.advent.advenced.day12.fixtures.EMAIL_HIDDEN_HTML
import ru.andvl.advent.advenced.day12.fixtures.MARKER_DOC
import ru.andvl.advent.advenced.day12.fixtures.MARKER_EMAIL
import ru.andvl.advent.advenced.day12.fixtures.MARKER_WEB
import ru.andvl.advent.advenced.day12.fixtures.WEB_INJECTED
import ru.andvl.advent.advenced.domain.repository.ChatRepository

private data class AttackCase(
    val id: String,
    val systemPrompt: String,
    val data: String,
    val marker: String,
    val userQuestion: String? = null,
)

private data class CellResult(
    val attack: String,
    val defense: String,
    val pwned: Boolean,
    val raw: String,
    val error: String? = null,
)

fun main(args: Array<String>) {
    val apiKey = System.getenv("OPENROUTER_API_KEY")
        ?: run {
            // fallback на BuildSecrets, если переменная среды не задана
            try {
                val cls = Class.forName("ru.andvl.advent.advenced.secrets.BuildSecretsKt")
                val field = cls.getDeclaredField("OPENROUTER_API_KEY")
                field.isAccessible = true
                field.get(null) as String
            } catch (t: Throwable) {
                ""
            }
        }
    if (apiKey.isBlank()) {
        System.err.println("OPENROUTER_API_KEY not set (env var or local.properties)")
        kotlin.system.exitProcess(1)
    }

    val model = args.firstOrNull() ?: "qwen/qwen-2.5-7b-instruct"
    println("=== Day 12 — Indirect Prompt Injection ===")
    println("Model: $model\n")

    val repo: ChatRepository = ChatRepositoryImpl(OpenRouterChatApi(createHttpClientWithAuth(apiKey)))

    val attacks = listOf(
        AttackCase(
            id = "EMAIL",
            systemPrompt = AgentPrompts.EMAIL_SUMMARIZER,
            data = EMAIL_HIDDEN_HTML,
            marker = MARKER_EMAIL,
        ),
        AttackCase(
            id = "DOC",
            systemPrompt = AgentPrompts.DOC_ANALYST,
            data = DOC_ZERO_WIDTH,
            marker = MARKER_DOC,
        ),
        AttackCase(
            id = "WEB",
            systemPrompt = AgentPrompts.WEB_SEARCHER,
            data = "Вопрос пользователя: какая столица Франции?\n\nКонтекст страницы:\n$WEB_INJECTED",
            marker = MARKER_WEB,
        ),
    )

    val configs = listOf(
        DefenseConfig(),
        DefenseConfig(sanitizeHtml = true),
        DefenseConfig(normalizeUnicode = true),
        DefenseConfig(sanitizeHtml = true, normalizeUnicode = true, wrapBoundary = true),
    )

    val results = mutableListOf<CellResult>()

    runBlocking {
        for (attack in attacks) {
            val agent = Day12Agent(name = attack.id, systemPrompt = attack.systemPrompt, repository = repo)
            for (cfg in configs) {
                val processed = InputSanitizer.apply(attack.data, cfg)
                print("[${attack.id} / ${cfg.label}] ... ")
                System.out.flush()
                val cell = try {
                    val raw = agent.run(model, processed)
                    val pwned = OutputValidator.containsMarker(raw, attack.marker)
                    CellResult(attack.id, cfg.label, pwned, raw)
                } catch (t: Throwable) {
                    CellResult(attack.id, cfg.label, false, "", error = t.message)
                }
                println(if (cell.error != null) "ERR ${cell.error}" else if (cell.pwned) "PWNED" else "OK")
                results.add(cell)
            }
        }
    }

    println("\n=== Result matrix ===")
    val defenseLabels = configs.map { it.label }
    print("attack".padEnd(8))
    defenseLabels.forEach { print("| ${it.padEnd(28)}") }
    println()
    println("-".repeat(8 + defenseLabels.size * 30))
    for (attack in attacks) {
        print(attack.id.padEnd(8))
        for (label in defenseLabels) {
            val r = results.first { it.attack == attack.id && it.defense == label }
            val cell = when {
                r.error != null -> "ERR"
                r.pwned -> "PWNED"
                else -> "blocked"
            }
            print("| ${cell.padEnd(28)}")
        }
        println()
    }

    println("\n=== Raw outputs ===")
    for (r in results) {
        println("\n--- ${r.attack} / ${r.defense} ---")
        if (r.error != null) {
            println("ERROR: ${r.error}")
        } else {
            println(r.raw.take(400))
        }
    }
}
