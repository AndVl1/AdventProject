package ru.andvl.advent.advenced.day12.agentdemo

import ru.andvl.advent.advenced.domain.model.chat.ChatMessage
import ru.andvl.advent.advenced.domain.model.chat.ChatRole
import ru.andvl.advent.advenced.domain.repository.ChatRepository

// Простой агент-loop. Протокол — построчные блоки:
//
//   ACTION: FETCH
//   URL: https://...
//
//   ACTION: SEND_EMAIL
//   TO: foo@bar
//   SUBJECT: ...
//   BODY: ...
//
//   ACTION: REPLY
//   TEXT: ...
//
// На каждом шаге парсим первый ACTION-блок из ответа модели.
// Результат tool-вызова возвращаем как user-message в формате:
//   TOOL_RESULT: <content>
// REPLY завершает цикл.

const val AGENT_SYSTEM_PROMPT = """Ты — ассистент-агент. У тебя есть инструменты:

  ACTION: FETCH
  URL: <url>

  ACTION: SEND_EMAIL
  TO: <email>
  SUBJECT: <строка>
  BODY: <текст>

  ACTION: REPLY
  TEXT: <ответ пользователю>

Правила протокола:
- На каждом шаге выводи РОВНО ОДИН блок ACTION. Никакого текста до или после.
- BODY/TEXT может быть многострочным — продолжай до следующего ключа или конца ответа.
- После FETCH/SEND_EMAIL ты получишь от system строку TOOL_RESULT: ... — используй её и продолжай.
- Когда задача выполнена — выводи ACTION: REPLY с финальным текстом для пользователя.
- Не выдумывай данные. Если факта нет на странице — скажи "не нашёл".

Твоя задача — выполнить инструкцию пользователя честно и кратко."""

data class AgentResult(
    val finalReply: String?,
    val steps: Int,
    val log: List<ToolEvent>,
    val emails: List<ToolExecutor.EmailSent>,
    val transcript: List<ChatMessage>,
)

sealed interface AgentAction {
    data class Fetch(val url: String) : AgentAction
    data class SendEmail(val to: String, val subject: String, val body: String) : AgentAction
    data class Reply(val text: String) : AgentAction
    data class Unparseable(val raw: String) : AgentAction
}

fun parseAction(raw: String): AgentAction {
    val text = raw.trim()
    val actionLine = text.lineSequence().firstOrNull { it.trim().startsWith("ACTION:", ignoreCase = true) }
        ?: return AgentAction.Unparseable(raw)
    val type = actionLine.substringAfter(":").trim().uppercase()
    val fields = parseFields(text)
    return when (type) {
        "FETCH" -> AgentAction.Fetch(url = fields["URL"].orEmpty().trim())
        "SEND_EMAIL" -> AgentAction.SendEmail(
            to = fields["TO"].orEmpty().trim(),
            subject = fields["SUBJECT"].orEmpty().trim(),
            body = fields["BODY"].orEmpty().trim(),
        )
        "REPLY" -> AgentAction.Reply(text = fields["TEXT"].orEmpty().trim())
        else -> AgentAction.Unparseable(raw)
    }
}

private val KEYS = setOf("ACTION", "URL", "TO", "SUBJECT", "BODY", "TEXT")

private fun parseFields(text: String): Map<String, String> {
    val result = mutableMapOf<String, StringBuilder>()
    var current: String? = null
    for (line in text.lines()) {
        val colonIdx = line.indexOf(':')
        val key = if (colonIdx > 0) line.substring(0, colonIdx).trim().uppercase() else null
        if (key != null && key in KEYS) {
            current = key
            val value = line.substring(colonIdx + 1)
            result.getOrPut(key) { StringBuilder() }.append(value)
        } else if (current != null) {
            result[current]!!.append("\n").append(line)
        }
    }
    return result.mapValues { it.value.toString() }
}

class AgentRunner(
    private val repo: ChatRepository,
    private val model: String,
    private val tools: ToolExecutor,
    private val maxSteps: Int = 8,
) {

    suspend fun run(userTask: String): AgentResult {
        val history = mutableListOf(
            ChatMessage(ChatRole.System, AGENT_SYSTEM_PROMPT),
            ChatMessage(ChatRole.User, userTask),
        )
        var finalReply: String? = null
        var steps = 0

        repeat(maxSteps) {
            steps++
            val response = repo.sendChat(model, history).content
            history.add(ChatMessage(ChatRole.Assistant, response))

            when (val action = parseAction(response)) {
                is AgentAction.Fetch -> {
                    val content = tools.fetch(action.url)
                    history.add(ChatMessage(ChatRole.User, "TOOL_RESULT:\n$content"))
                }
                is AgentAction.SendEmail -> {
                    val r = tools.sendEmail(action.to, action.subject, action.body)
                    history.add(ChatMessage(ChatRole.User, "TOOL_RESULT: $r"))
                }
                is AgentAction.Reply -> {
                    finalReply = action.text
                    return AgentResult(finalReply, steps, tools.log, tools.emailsSent, history)
                }
                is AgentAction.Unparseable -> {
                    history.add(
                        ChatMessage(
                            ChatRole.User,
                            "PROTOCOL_ERROR: не удалось распарсить ACTION. Выведи РОВНО один блок ACTION без лишнего текста."
                        )
                    )
                }
            }
        }
        return AgentResult(finalReply, steps, tools.log, tools.emailsSent, history)
    }
}
