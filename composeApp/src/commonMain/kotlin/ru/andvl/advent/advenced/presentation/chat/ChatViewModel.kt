package ru.andvl.advent.advenced.presentation.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import ru.andvl.advent.advenced.domain.model.chat.ChatMessage
import ru.andvl.advent.advenced.domain.model.chat.ChatRole
import ru.andvl.advent.advenced.domain.usecase.SendChatMessageUseCase

class ChatViewModel(
    private val sendChatMessage: SendChatMessageUseCase,
) : ViewModel() {

    private val _state = MutableStateFlow<ChatUiState>(
        ChatUiState.Active(
            systemPrompt = DEFAULT_SYSTEM_PROMPT,
            model = AVAILABLE_MODELS.first(),
            history = emptyList(),
            isSending = false,
            error = null,
        )
    )
    val state = _state.asStateFlow()

    fun onSystemPromptChange(text: String) {
        updateActive { copy(systemPrompt = text, error = null) }
    }

    fun onModelChange(model: String) {
        updateActive { copy(model = model, error = null) }
    }

    fun onUserMessageSend(text: String) {
        val active = _state.value as? ChatUiState.Active ?: return
        if (text.isBlank() || active.isSending) return

        val userMessage = ChatMessage(role = ChatRole.User, content = text.trim())
        val newHistory = active.history + userMessage

        _state.value = active.copy(
            history = newHistory,
            isSending = true,
            error = null,
        )

        viewModelScope.launch {
            val messagesForApi = buildList {
                add(ChatMessage(role = ChatRole.System, content = active.systemPrompt))
                addAll(newHistory)
            }
            try {
                val reply = sendChatMessage(active.model, messagesForApi)
                updateActive { copy(history = history + reply, isSending = false) }
            } catch (t: Throwable) {
                updateActive {
                    copy(
                        isSending = false,
                        error = t.message ?: "Неизвестная ошибка",
                    )
                }
            }
        }
    }

    fun onReset() {
        updateActive { copy(history = emptyList(), isSending = false, error = null) }
    }

    private inline fun updateActive(transform: ChatUiState.Active.() -> ChatUiState.Active) {
        val current = _state.value as? ChatUiState.Active ?: return
        _state.value = current.transform()
    }
}
