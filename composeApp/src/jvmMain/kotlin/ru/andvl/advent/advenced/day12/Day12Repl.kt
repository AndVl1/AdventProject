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

private data class ReplAttack(
    val key: String,
    val title: String,
    val systemPrompt: String,
    val data: String,
    val marker: String,
)

private val ATTACKS = listOf(
    ReplAttack(
        key = "1",
        title = "EMAIL — summarizer + HTML-comment payload",
        systemPrompt = AgentPrompts.EMAIL_SUMMARIZER,
        data = EMAIL_HIDDEN_HTML,
        marker = MARKER_EMAIL,
    ),
    ReplAttack(
        key = "2",
        title = "DOC — analyst (JSON) + zero-width payload",
        systemPrompt = AgentPrompts.DOC_ANALYST,
        data = DOC_ZERO_WIDTH,
        marker = MARKER_DOC,
    ),
    ReplAttack(
        key = "3",
        title = "WEB — searcher + injected context",
        systemPrompt = AgentPrompts.WEB_SEARCHER,
        data = "Вопрос пользователя: какая столица Франции?\n\nКонтекст страницы:\n$WEB_INJECTED",
        marker = MARKER_WEB,
    ),
)

private val MODELS = listOf(
    "qwen/qwen-2.5-7b-instruct",
    "qwen/qwen3-235b-a22b-2507",
    "mistralai/mistral-nemo",
    "google/gemma-3-12b-it",
)

fun main() {
    val apiKey = resolveApiKey()
    if (apiKey.isBlank()) {
        System.err.println("OPENROUTER_API_KEY not set (env var or local.properties)")
        kotlin.system.exitProcess(1)
    }
    val repo: ChatRepository = ChatRepositoryImpl(OpenRouterChatApi(createHttpClientWithAuth(apiKey)))

    var model = MODELS.first()
    var defense = DefenseConfig()

    println("=== Day 12 — Indirect Injection REPL ===")
    println("Команды: a=атака r=run d=защиты m=модель s=show p=payload-hex q=quit")
    println()

    while (true) {
        printStatus(model, defense)
        print("> ")
        System.out.flush()
        val line = readLine()?.trim() ?: break
        when (line) {
            "q", "quit", "exit" -> {
                println("bye")
                return
            }
            "a", "attack" -> chooseAttack()?.let { runAttack(repo, model, defense, it) }
            "r", "run" -> {
                val a = chooseAttack() ?: continue
                runAttack(repo, model, defense, a)
            }
            "d", "defense", "defenses" -> defense = chooseDefense(defense)
            "m", "model" -> model = chooseModel(model)
            "s", "show" -> showProcessed(defense)
            "p", "payload-hex" -> showPayloadHex()
            "h", "help", "?" -> printHelp()
            "" -> Unit
            else -> println("неизвестная команда. h = help")
        }
        println()
    }
}

private fun printStatus(model: String, cfg: DefenseConfig) {
    println("Модель: $model | Защиты: ${cfg.label}")
}

private fun printHelp() {
    println(
        """
        a — выбрать атаку и запустить
        r — то же что a
        d — переключить защитные слои (sanitize/normalize/boundary)
        m — выбрать модель
        s — показать processed payload (после защит) для атаки
        p — показать hex невидимых символов в payload
        q — выйти
        """.trimIndent()
    )
}

private fun chooseAttack(): ReplAttack? {
    println("\nАтаки:")
    ATTACKS.forEach { println("  ${it.key}. ${it.title}") }
    print("выбор (1-3, enter=отмена): ")
    System.out.flush()
    val k = readLine()?.trim().orEmpty()
    return ATTACKS.firstOrNull { it.key == k }
}

private fun chooseModel(current: String): String {
    println("\nМодели:")
    MODELS.forEachIndexed { i, m -> println("  ${i + 1}. $m${if (m == current) "  (текущая)" else ""}") }
    print("номер (enter=без изменений): ")
    System.out.flush()
    val k = readLine()?.trim().orEmpty().toIntOrNull() ?: return current
    return MODELS.getOrNull(k - 1) ?: current
}

private fun chooseDefense(current: DefenseConfig): DefenseConfig {
    println(
        """
        Защиты (введи маску из букв, например 'sn' или 'snb' или пусто=ничего):
          s — sanitizeHtml (вырезать <!-- --> / script / style)
          n — normalizeUnicode (убрать ZWS и невидимые символы)
          b — wrapBoundary ([BEGIN/END UNTRUSTED DATA])
        Текущее: ${current.label}
        """.trimIndent()
    )
    print("маска: ")
    System.out.flush()
    val mask = readLine()?.trim()?.lowercase().orEmpty()
    return DefenseConfig(
        sanitizeHtml = 's' in mask,
        normalizeUnicode = 'n' in mask,
        wrapBoundary = 'b' in mask,
    )
}

private fun showProcessed(cfg: DefenseConfig) {
    val a = chooseAttack() ?: return
    val out = InputSanitizer.apply(a.data, cfg)
    println("\n--- processed (${cfg.label}) ---")
    println(out)
    println("--- end ---")
    println("длина: ${out.length} символов")
}

private fun showPayloadHex() {
    val a = chooseAttack() ?: return
    println("\n--- невидимые / непечатные символы в payload ---")
    val invisibleRanges = setOf(
        0x200B..0x200D,
        0x2060..0x2064,
        0xFEFF..0xFEFF,
        0x00AD..0x00AD,
        0x180E..0x180E,
    )
    val found = mutableMapOf<Int, Int>()
    a.data.forEach { c ->
        val cp = c.code
        if (invisibleRanges.any { cp in it }) {
            found.merge(cp, 1) { a, b -> a + b }
        }
    }
    if (found.isEmpty()) {
        println("невидимых символов нет (для этой атаки payload видимый — HTML/markdown)")
    } else {
        found.toSortedMap().forEach { (cp, n) ->
            println("  U+${"%04X".format(cp)}  ×$n")
        }
    }
}

private fun runAttack(
    repo: ChatRepository,
    model: String,
    cfg: DefenseConfig,
    attack: ReplAttack,
) {
    val processed = InputSanitizer.apply(attack.data, cfg)
    val agent = Day12Agent(name = attack.key, systemPrompt = attack.systemPrompt, repository = repo)
    println("\n>>> ${attack.title}")
    println(">>> defense=${cfg.label}, model=$model, marker=${attack.marker}")
    println(">>> запрос отправлен...")
    val (raw, err) = runBlocking {
        try {
            agent.run(model, processed) to null
        } catch (t: Throwable) {
            "" to (t.message ?: t::class.simpleName)
        }
    }
    if (err != null) {
        println(">>> ERROR: $err")
        return
    }
    val pwned = OutputValidator.containsMarker(raw, attack.marker)
    println("\n--- response ---")
    println(raw)
    println("--- end ---")
    println(if (pwned) ">>> RESULT: PWNED (маркер ${attack.marker} найден)" else ">>> RESULT: blocked")
}

private fun resolveApiKey(): String {
    val env = System.getenv("OPENROUTER_API_KEY")
    if (!env.isNullOrBlank()) return env
    return try {
        val cls = Class.forName("ru.andvl.advent.advenced.secrets.BuildSecretsKt")
        val field = cls.getDeclaredField("OPENROUTER_API_KEY")
        field.isAccessible = true
        (field.get(null) as? String).orEmpty()
    } catch (t: Throwable) {
        ""
    }
}
