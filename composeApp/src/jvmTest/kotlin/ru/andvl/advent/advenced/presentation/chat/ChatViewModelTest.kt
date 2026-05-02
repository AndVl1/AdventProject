package ru.andvl.advent.advenced.presentation.chat

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import ru.andvl.advent.advenced.domain.model.chat.ChatMessage
import ru.andvl.advent.advenced.domain.model.chat.ChatRole
import ru.andvl.advent.advenced.domain.repository.ChatRepository
import ru.andvl.advent.advenced.domain.usecase.SendChatMessageUseCase
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `initial state is Active with empty history`() {
        val viewModel = createViewModel(FakeSuccessRepository())
        val state = viewModel.state.value as ChatUiState.Active
        assertTrue(state.history.isEmpty())
        assertEquals(DEFAULT_SYSTEM_PROMPT, state.systemPrompt)
        assertEquals(AVAILABLE_MODELS.first(), state.model)
    }

    @Test
    fun `onUserMessageSend happy path adds user and assistant messages to history`() = runTest {
        val assistantReply = ChatMessage(ChatRole.Assistant, "Чем могу помочь?")
        val viewModel = createViewModel(FakeSuccessRepository(reply = assistantReply))

        viewModel.onUserMessageSend("Привет")
        testDispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.state.value as ChatUiState.Active
        assertEquals(2, state.history.size)
        assertEquals(ChatRole.User, state.history[0].role)
        assertEquals("Привет", state.history[0].content)
        assertEquals(assistantReply, state.history[1])
        assertNull(state.error)
    }

    @Test
    fun `onUserMessageSend error path sets error in state`() = runTest {
        val viewModel = createViewModel(FakeErrorRepository("Сеть недоступна"))

        viewModel.onUserMessageSend("Привет")
        testDispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.state.value as ChatUiState.Active
        // Сообщение пользователя добавляется, ответа нет
        assertEquals(1, state.history.size)
        assertNotNull(state.error)
        assertTrue(state.error.orEmpty().contains("Сеть недоступна"))
    }

    @Test
    fun `onReset clears history and error`() = runTest {
        val viewModel = createViewModel(FakeErrorRepository("ошибка"))
        viewModel.onUserMessageSend("тест")
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.onReset()

        val state = viewModel.state.value as ChatUiState.Active
        assertTrue(state.history.isEmpty())
        assertNull(state.error)
    }

    @Test
    fun `onSystemPromptChange updates system prompt`() {
        val viewModel = createViewModel(FakeSuccessRepository())
        viewModel.onSystemPromptChange("Новый системный промпт")
        val state = viewModel.state.value as ChatUiState.Active
        assertEquals("Новый системный промпт", state.systemPrompt)
    }

    @Test
    fun `onModelChange updates selected model`() {
        val viewModel = createViewModel(FakeSuccessRepository())
        val newModel = AVAILABLE_MODELS[1]
        viewModel.onModelChange(newModel)
        val state = viewModel.state.value as ChatUiState.Active
        assertEquals(newModel, state.model)
    }

    // ---------- helpers ----------

    private fun createViewModel(repository: ChatRepository): ChatViewModel =
        ChatViewModel(SendChatMessageUseCase(repository))

    private class FakeSuccessRepository(
        private val reply: ChatMessage = ChatMessage(ChatRole.Assistant, "ok"),
    ) : ChatRepository {
        override suspend fun sendChat(model: String, messages: List<ChatMessage>): ChatMessage = reply
    }

    private class FakeErrorRepository(private val errorMessage: String) : ChatRepository {
        override suspend fun sendChat(model: String, messages: List<ChatMessage>): ChatMessage =
            throw RuntimeException(errorMessage)
    }
}
