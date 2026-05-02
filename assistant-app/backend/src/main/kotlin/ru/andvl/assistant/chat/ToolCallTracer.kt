package ru.andvl.assistant.chat

import ru.andvl.assistant.api.dto.ToolCallRecord

/**
 * Собирает записи о вызовах инструментов в рамках одного запроса агента.
 * Экземпляр создаётся на каждый запрос в AgentFactory.
 */
class ToolCallTracer {

    private val _records = mutableListOf<ToolCallRecord>()

    val records: List<ToolCallRecord>
        get() = _records.toList()

    fun record(
        name: String,
        args: Any?,
        result: String?,
        ok: Boolean,
    ) {
        _records.add(ToolCallRecord(name = name, args = args, result = result, ok = ok))
    }
}
