package ru.andvl.assistant.api

import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import ru.andvl.assistant.chat.SessionStore
import java.util.UUID

@RestController
@RequestMapping("/api/sessions")
class SessionController(
    private val sessionStore: SessionStore,
) {

    @DeleteMapping("/{id}")
    fun deleteSession(@PathVariable id: UUID): ResponseEntity<Void> {
        sessionStore.remove(id)
        return ResponseEntity.noContent().build()
    }
}
