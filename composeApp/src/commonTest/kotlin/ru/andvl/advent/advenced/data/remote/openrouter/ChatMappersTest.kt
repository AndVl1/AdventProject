package ru.andvl.advent.advenced.data.remote.openrouter

import ru.andvl.advent.advenced.domain.model.chat.ChatMessage
import ru.andvl.advent.advenced.domain.model.chat.ChatRole
import kotlin.test.Test
import kotlin.test.assertEquals

class ChatMappersTest {

    @Test
    fun `toDomain maps user role correctly`() {
        val dto = ChatMessageDto(role = "user", content = "Привет")
        val domain = dto.toDomain()
        assertEquals(ChatRole.User, domain.role)
        assertEquals("Привет", domain.content)
    }

    @Test
    fun `toDomain maps assistant role correctly`() {
        val dto = ChatMessageDto(role = "assistant", content = "Привет, я ассистент")
        val domain = dto.toDomain()
        assertEquals(ChatRole.Assistant, domain.role)
    }

    @Test
    fun `toDomain maps system role correctly`() {
        val dto = ChatMessageDto(role = "system", content = "Ты ассистент банка")
        val domain = dto.toDomain()
        assertEquals(ChatRole.System, domain.role)
    }

    @Test
    fun `toDomain maps unknown role to Assistant`() {
        val dto = ChatMessageDto(role = "unknown_role", content = "текст")
        val domain = dto.toDomain()
        assertEquals(ChatRole.Assistant, domain.role)
    }

    @Test
    fun `toDto maps User role to user string`() {
        val message = ChatMessage(role = ChatRole.User, content = "вопрос")
        val dto = message.toDto()
        assertEquals("user", dto.role)
        assertEquals("вопрос", dto.content)
    }

    @Test
    fun `toDto maps Assistant role to assistant string`() {
        val message = ChatMessage(role = ChatRole.Assistant, content = "ответ")
        val dto = message.toDto()
        assertEquals("assistant", dto.role)
    }

    @Test
    fun `toDto maps System role to system string`() {
        val message = ChatMessage(role = ChatRole.System, content = "системный промпт")
        val dto = message.toDto()
        assertEquals("system", dto.role)
    }

    @Test
    fun `roundtrip domain to dto and back preserves content`() {
        val original = ChatMessage(role = ChatRole.User, content = "тест round-trip")
        val dto = original.toDto()
        val restored = dto.toDomain()
        assertEquals(original.role, restored.role)
        assertEquals(original.content, restored.content)
    }
}
