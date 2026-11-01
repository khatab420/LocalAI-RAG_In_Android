package com.example.localai.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.localai.AppContainer
import com.example.localai.data.ChatEntity
import com.example.localai.data.MessageEntity
import com.example.localai.llm.ChatMessage
import com.example.localai.llm.ControlMarkers
import com.example.localai.llm.StreamingCleaner
import com.example.localai.rag.Hit
import com.example.localai.rag.RagPrompt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class ChatViewModel(private val c: AppContainer) : ViewModel() {

    val messages = mutableStateListOf<ChatMessage>()
    var generating by mutableStateOf(false); private set
    var status by mutableStateOf<String?>(null); private set
    var error by mutableStateOf<String?>(null); private set
    var modelName by mutableStateOf<String?>(c.engine.loadedName); private set

    /** When on, every question is answered with passages retrieved from the knowledge base. */
    var ragEnabled by mutableStateOf(false); private set

    val chats = c.db.dao().chats()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList<ChatEntity>())

    private var job: Job? = null
    private var chatId: Long? = null

    /**
     * Identifies the turn that is allowed to write into [messages]. Bumped by Stop, New chat
     * and opening a chat, so a cancelled turn can never keep appending to (or deleting from)
     * a transcript it no longer owns. Without this, tapping Stop and sending again made the
     * dying turn clobber the new one -- which looked exactly like "Stop does not work".
     */
    private var turn = 0

    /** Call when the chat screen opens (a model may have been loaded in the meantime). */
    fun refreshModel() { modelName = c.engine.loadedName }

    fun setRag(on: Boolean) {
        viewModelScope.launch {
            if (on && c.rag.docCount() == 0) {
                error = "Your knowledge base is empty. Add files in Docs first."
                ragEnabled = false
            } else {
                error = null
                ragEnabled = on
            }
        }
    }

    fun send(text: String) {
        val prompt = text.trim()
        if (prompt.isEmpty() || generating) return
        if (!c.engine.isLoaded) {
            error = "No model loaded. Go to Models and tap Load."
            return
        }
        error = null
        messages += ChatMessage("user", prompt)
        messages += ChatMessage("assistant", "")
        startTurn()
    }

    /** Re-runs the last question, replacing the answer that was there. */
    fun regenerate() {
        if (generating) return
        if (!c.engine.isLoaded) {
            error = "No model loaded. Go to Models and tap Load."
            return
        }
        val lastUser = messages.indexOfLast { it.role == "user" }
        if (lastUser < 0) return
        while (messages.size > lastUser + 1) messages.removeAt(messages.lastIndex)
        error = null
        messages += ChatMessage("assistant", "")
        startTurn(persistQuestion = false)
    }

    fun clearError() { error = null }

    /**
     * Expects [messages] to end with the user turn and an empty assistant placeholder.
     * [persistQuestion] is false for a regeneration, where the question is already stored.
     */
    private fun startTurn(persistQuestion: Boolean = true) {
        val prompt = messages[messages.lastIndex - 1].content
        val previous = messages.dropLast(2).toList()
        val answerIndex = messages.lastIndex
        val myTurn = ++turn
        // The answer is collected here, not in the list, so a cancelled turn still knows
        // exactly what it produced for the database.
        val answer = StringBuilder()
        var sources: List<String> = emptyList()

        job = viewModelScope.launch {
            generating = true
            try {
                // 1) retrieve passages from the knowledge base (optional)
                var hits: List<Hit> = emptyList()
                if (ragEnabled) {
                    status = "Searching your documents…"
                    hits = try {
                        c.rag.search(prompt)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        error = "Document search failed: ${e.message}"
                        emptyList()
                    }
                    status = null
                }

                // 2) build the prompt
                val userContent = if (hits.isEmpty()) prompt else RagPrompt.build(prompt, hits)
                if (hits.isNotEmpty()) {
                    sources = hits.mapIndexed { n, h -> "[${n + 1}] ${h.docName}" }
                    if (myTurn == turn && answerIndex in messages.indices) {
                        messages[answerIndex] = messages[answerIndex].copy(sources = sources)
                    }
                }
                val history = trimmed(previous, if (hits.isEmpty()) 7000 else 3500)
                val request = listOf(ChatMessage("system", SYSTEM_PROMPT)) + history +
                    ChatMessage("user", userContent)

                // 3) generate. Pieces are batched (~20 UI updates a second instead of one
                //    per token) so redrawing the answer never blocks the main thread, and
                //    chat-template markers (<|im_end|>, <end_of_turn>) are stripped.
                val cleaner = StreamingCleaner()
                val pending = StringBuilder()
                var lastFlush = 0L
                fun flush() {
                    if (pending.isEmpty()) return
                    answer.append(pending)
                    pending.setLength(0)
                    // write only into this turn's own answer slot, and only while it still owns it
                    if (myTurn != turn || answerIndex !in messages.indices) return
                    val shown = messages[answerIndex]
                    if (shown.role != "assistant") return
                    messages[answerIndex] = shown.copy(content = answer.toString())
                }

                try {
                    c.engine.generate(request)
                        .catch { e ->
                            if (e is CancellationException) throw e
                            error = e.message ?: "Generation failed"
                        }
                        .collect { piece ->
                            pending.append(cleaner.accept(piece))
                            if (cleaner.hitTerminator) {
                                // the model already ended the turn, it is only rambling now
                                c.engine.cancelGeneration()
                            }
                            val now = System.nanoTime()
                            if (now - lastFlush >= FLUSH_NANOS) {
                                lastFlush = now
                                flush()
                            }
                        }
                } finally {
                    pending.append(cleaner.finish())
                    flush()
                }
            } catch (e: CancellationException) {
                throw e
            } finally {
                // a newer turn (Stop then send, new chat, opened chat) owns the UI now
                if (myTurn == turn) {
                    generating = false
                    status = null
                    if (answer.isEmpty() && answerIndex in messages.indices &&
                        messages[answerIndex].role == "assistant"
                    ) {
                        messages.removeAt(answerIndex)         // nothing was generated
                    }
                }
                // Stop cancels this job, but the partial answer is still worth keeping —
                // and it is this turn's text, not whatever happens to be on screen now.
                withContext(NonCancellable) { persistTurn(prompt, answer.toString(), persistQuestion) }
            }
        }
    }

    /** Stops the current answer and keeps whatever text was already generated. */
    fun stop() {
        if (!generating) return
        turn++                          // the running turn must not write anything else
        c.engine.cancelGeneration()      // native flag first: stops at the source, not later
        job?.cancel()
        status = null
        generating = false
        val last = messages.lastOrNull()
        if (last != null && last.role == "assistant" && last.content.isBlank()) {
            messages.removeAt(messages.lastIndex)          // nothing worth keeping
        }
    }

    fun newChat() {
        turn++
        job?.cancel()
        messages.clear()
        chatId = null
        error = null
        status = null
        generating = false
    }

    fun openChat(id: Long) {
        turn++
        job?.cancel()
        viewModelScope.launch {
            val saved = c.db.dao().messages(id)
            messages.clear()
            // older turns may have been stored with leaked template markers
            messages.addAll(saved.map { ChatMessage(it.role, ControlMarkers.clean(it.content, trim = true)) })
            chatId = id
            error = null
        }
    }

    fun deleteChat(id: Long) {
        viewModelScope.launch {
            c.db.dao().deleteMessages(id)
            c.db.dao().deleteChat(id)
            if (chatId == id) { messages.clear(); chatId = null }
        }
    }

    private fun trimmed(all: List<ChatMessage>, maxChars: Int): List<ChatMessage> {
        val out = ArrayDeque<ChatMessage>()
        var total = 0
        for (m in all.asReversed()) {
            if (out.isNotEmpty() && total + m.content.length > maxChars) break
            total += m.content.length
            out.addFirst(m)
        }
        return out.toList()
    }

    /** Saves one finished (or stopped) turn. Uses explicit text, so it cannot save the wrong one. */
    private suspend fun persistTurn(question: String, answer: String, saveQuestion: Boolean = true) {
        val dao = c.db.dao()
        val id = chatId ?: dao.insertChat(ChatEntity(title = question.take(40))).also { chatId = it }
        if (saveQuestion) {
            dao.insertMessage(
                MessageEntity(
                    chatId = id,
                    role = "user",
                    content = ControlMarkers.clean(question, trim = true)
                )
            )
        }
        if (answer.isNotBlank()) {
            dao.insertMessage(
                MessageEntity(
                    chatId = id,
                    role = "assistant",
                    content = ControlMarkers.clean(answer, trim = true)
                )
            )
        }
    }

    companion object {
        const val SYSTEM_PROMPT = "You are a helpful assistant."

        /** How often streamed text is written into the message list while generating. */
        private const val FLUSH_NANOS = 60_000_000L      // 60 ms (~16 fps of new text)
    }
}
