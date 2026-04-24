package com.sai8151.urlai

import android.app.Application
import android.util.Log
import androidx.lifecycle.*
import com.sai8151.urlai.ai.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import java.text.SimpleDateFormat
import java.util.*

data class ChatMessage(
    val role: String,
    val content: String,
    val stats: String? = null
)

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private var aiClient: AiClient? = null
    private var activeSessionId: String = System.currentTimeMillis().toString()
    private fun newSession() {
        activeSessionId = System.currentTimeMillis().toString()
    }
    private var currentConversationId: String? = null
    private val prefs = PreferencesManager(application)
    private val poller = UrlPoller()
    private val storage = ConversationStorage(getApplication())
    private val _chatMessages = MutableLiveData<List<ChatMessage>>(emptyList())
    val chatMessages: LiveData<List<ChatMessage>> = _chatMessages

    private val _status = MutableLiveData("Idle")
    val status: LiveData<String> = _status

    private val _isPolling = MutableLiveData(false)
    val isPolling: LiveData<Boolean> = _isPolling

    private var lastContent: String = ""
    private var pollingJob: Job? = null
    private val conversationHistory = mutableListOf<ChatMessage>()
    fun clearChat() {
        newSession()

        // 🔥 generate ID ONCE for new chat
        currentConversationId = System.currentTimeMillis().toString()

        _chatMessages.value = listOf()
        conversationHistory.clear()
        lastContent = ""
    }
    fun loadConversation(conv: Conversation) {
        activeSessionId = conv.id
        currentConversationId = conv.id

        _chatMessages.value = conv.messages.toList()

        conversationHistory.clear()
        conversationHistory.addAll(conv.messages)

        lastContent = ""
    }
    private fun autoSaveConversation() {

        val messages = _chatMessages.value ?: return
        if (messages.isEmpty()) return

        if (currentConversationId == null) {
            currentConversationId = System.currentTimeMillis().toString()
        }

        val id = currentConversationId!!

        val conversation = Conversation(
            id = id,
            title = messages.firstOrNull()?.content?.take(30) ?: "New Chat",
            messages = messages,
            timestamp = System.currentTimeMillis()
        )
        Log.d("CHAT_DEBUG", "Saving ID: $id")
        storage.upsertConversation(conversation)
    }
    fun saveCurrentConversation() {

        val messages = _chatMessages.value ?: return
        if (messages.isEmpty()) return

        // 🔥 use EXISTING ID (NOT NEW)
        val id = currentConversationId ?: System.currentTimeMillis().toString()

        val conversation = Conversation(
            id = id,
            title = messages.firstOrNull()?.content?.take(30) ?: "New Chat",
            messages = messages,
            timestamp = System.currentTimeMillis()
        )

        currentConversationId = id

        storage.upsertConversation(conversation)
    }
    suspend fun sendMessage(userInput: String) {

        try {
            if (currentConversationId == null) {
                currentConversationId = System.currentTimeMillis().toString()
            }
            val sessionAtStart = activeSessionId
            val model = prefs.selectedModel.first()

            if (aiClient == null) {
                aiClient = buildClient(model)
            }

            if (aiClient == null) {
                _status.postValue("Invalid API key / model.")
                return
            }

            val systemPrompt = prefs.systemPrompt.first()

            withContext(Dispatchers.Main) {
                _status.value = "Thinking..."
                addMessage(ChatMessage("user", userInput))
            }

            val messages = _chatMessages.value?.toMutableList() ?: mutableListOf()
            val aiIndex = messages.size
            messages.add(ChatMessage("assistant", ""))
            _chatMessages.postValue(messages)

            val buffer = StringBuilder()

            val (finalText, stats, metrics) = aiClient!!.chat(
                systemPrompt = systemPrompt,
                history = conversationHistory.map { it.role to it.content },
                userMessage = userInput
            ) { token ->
                if (sessionAtStart != activeSessionId) return@chat
                buffer.append(token)

                if (buffer.length > 30) {
                    val updated = _chatMessages.value?.toMutableList() ?: return@chat

                    if (aiIndex >= updated.size) return@chat

                    val current = updated[aiIndex]

                    updated[aiIndex] = current.copy(
                        content = current.content + buffer.toString()
                    )

                    buffer.clear()
                    _chatMessages.postValue(updated)
                }
            }

            // Flush remaining
            if (buffer.isNotEmpty()) {
                val updated = _chatMessages.value?.toMutableList() ?: mutableListOf()
                val current = updated[aiIndex]

                updated[aiIndex] = current.copy(
                    content = current.content + buffer.toString()
                )
                withContext(Dispatchers.Main) {
                    _chatMessages.value = updated
                }
            }

            // Final update
            val updated = _chatMessages.value?.toMutableList() ?: mutableListOf()

            val current = updated[aiIndex]

            updated[aiIndex] = current.copy(
                content = finalText,
                stats = stats
            )

            withContext(Dispatchers.Main) {
                _chatMessages.value = updated
            }

            // Save history
            conversationHistory.add(ChatMessage("user", userInput))
            conversationHistory.add(ChatMessage("assistant", finalText))

            _status.postValue("Done")
            //autoSaveConversation()

        } catch (e: Exception) {
            _status.postValue("Error: ${e.message}")
        }
    }
    fun startPolling() {
        if (pollingJob?.isActive == true) return

        _isPolling.value = true
        _status.value = "Initializing..."

        pollingJob = viewModelScope.launch(Dispatchers.IO) {

            try {
                val url = prefs.targetUrl.first()
                if (url.isBlank()) {
                    _status.postValue("No URL set. Go to Settings.")
                    return@launch
                }

                val model = prefs.selectedModel.first()

                // ✅ Build client ONCE
                if (aiClient == null) {
                    aiClient = buildClient(model)
                }

                if (aiClient == null) {
                    _status.postValue("Invalid API key / model.")
                    return@launch
                }

                _status.postValue("Ready. Polling...")

            } catch (e: Exception) {
                _status.postValue("Init Error: ${e.message}")
                return@launch
            }

            while (isActive) {
                val sessionAtStart = activeSessionId
                try {
                    val url = prefs.targetUrl.first()
                    val content = poller.fetchContent(url)

                    if (content != lastContent) {
                        if (sessionAtStart != activeSessionId) return@launch
                        val isFirstFetch = lastContent.isEmpty()
                        lastContent = content

                        val systemPrompt = prefs.systemPrompt.first()

                        val userMessage = if (isFirstFetch)
                            "URL content loaded:\n\n$content"
                        else
                            "URL content updated:\n\n$content"

                        withContext(Dispatchers.Main) {
                            _status.value = "Processing..."
                            addMessage(ChatMessage("user", userMessage))
                        }

                        val messages = _chatMessages.value?.toMutableList() ?: mutableListOf()
                        val aiIndex = messages.size
                        messages.add(ChatMessage("assistant", ""))
                        _chatMessages.postValue(messages)

                        val buffer = StringBuilder()

                        val (finalText, stats, metrics) = aiClient!!.chat(
                            systemPrompt = systemPrompt,
                            history = conversationHistory.map { it.role to it.content },
                            userMessage = userMessage
                        ) { token ->
                            if (sessionAtStart != activeSessionId) return@chat
                            buffer.append(token)

                            if (buffer.length > 30) {
                                val updated = _chatMessages.value?.toMutableList() ?: return@chat

                                if (aiIndex >= updated.size) return@chat

                                val current = updated[aiIndex]

                                updated[aiIndex] = current.copy(
                                    content = current.content + buffer.toString()
                                )

                                buffer.clear()
                                _chatMessages.postValue(updated)
                            }
                        }

                        // Flush remaining buffer
                        // Flush remaining
                        if (buffer.isNotEmpty()) {

                            if (sessionAtStart != activeSessionId) return@launch

                            val updated = _chatMessages.value?.toMutableList() ?: return@launch

                            if (aiIndex >= updated.size) return@launch

                            val current = updated[aiIndex]

                            updated[aiIndex] = current.copy(
                                content = current.content + buffer.toString()
                            )

                            withContext(Dispatchers.Main) {
                                _chatMessages.value = updated
                            }
                        }

// Final update
                        if (sessionAtStart != activeSessionId) return@launch

                        val updated = _chatMessages.value?.toMutableList() ?: return@launch

                        if (aiIndex >= updated.size) return@launch

                        val current = updated[aiIndex]

                        updated[aiIndex] = current.copy(
                            content = finalText,
                            stats = stats
                        )

                        _chatMessages.postValue(updated)
                        // Save history
                        conversationHistory.add(ChatMessage("user", userMessage))
                        conversationHistory.add(ChatMessage("assistant", finalText))

                        // ✅ SAFE AUTO-TUNE (NO OVERWRITE BUG)
                        if (aiClient?.isLocal() == true) {

                            val currentConfig = TunedConfig(
                                useGpu = prefs.useGpu.first(),
                                topK = prefs.topK.first(),
                                topP = prefs.topP.first(),
                                temperature = prefs.temperature.first(),
                                contextSize = prefs.contextSize.first()
                            )

                            val newConfig = AutoTuner.tune(currentConfig, metrics)

                            val currentUrl = prefs.targetUrl.first()
                            val currentGemini = prefs.geminiKey.first()
                            val currentClaude = prefs.claudeKey.first()
                            val currentOpenAi = prefs.openAiKey.first()
                            val currentPrompt = prefs.systemPrompt.first()
                            val currentModel = prefs.selectedModel.first()

                            prefs.save(
                                url = currentUrl,
                                gemini = currentGemini,
                                claude = currentClaude,
                                openAi = currentOpenAi,
                                prompt = currentPrompt,
                                model = currentModel,
                                useGpu = newConfig.useGpu,
                                temperature = newConfig.temperature,
                                topK = newConfig.topK,
                                topP = newConfig.topP,
                                contextSize = newConfig.contextSize
                            )

                            prefs.saveMetrics(
                                metrics.tps,
                                metrics.firstLatency,
                                metrics.totalTime
                            )
                        }

                        _status.postValue("Updated at ${currentTime()}")
                    }

                } catch (e: Exception) {
                    _status.postValue("Error: ${e.message}")
                }

                delay(3000)
            }
        }
    }

    fun stopPolling() {
        pollingJob?.cancel()
        pollingJob = null
        _isPolling.value = false
        _status.value = "Stopped"
    }

    fun refreshChat() {
        viewModelScope.launch {

            pollingJob?.cancelAndJoin()
            pollingJob = null

            conversationHistory.clear()
            lastContent = ""

            withContext(Dispatchers.Main) {
                _chatMessages.value = emptyList()
                _status.value = "Chat refreshed. Press Start."
                _isPolling.value = false
            }

            try {
                (aiClient as? LiteRtClient)?.resetConversation()
            } catch (_: Exception) {}

            aiClient = null
        }
    }

    private fun addMessage(msg: ChatMessage) {
        val current = _chatMessages.value?.toMutableList() ?: mutableListOf()
        current.add(msg)
        _chatMessages.value = current
    }

    private suspend fun buildClient(model: String): AiClient? {

        if (LocalModelRegistry.isLocalModel(model)) {
            return LiteRtClient(
                getApplication(),
                model,
                prefs.useGpu.first(),
                prefs.temperature.first(),
                prefs.topK.first(),
                prefs.topP.first(),
                prefs.contextSize.first()
            )
        }

        return when {
            model.startsWith("gemini") -> {
                val key = prefs.geminiKey.first()
                if (key.isBlank()) null else GeminiClient(key, model)
            }
            model.startsWith("claude") -> {
                val key = prefs.claudeKey.first()
                if (key.isBlank()) null else ClaudeClient(key, model)
            }
            model.startsWith("gpt") || model.startsWith("o1") || model.startsWith("o3") -> {
                val key = prefs.openAiKey.first()
                if (key.isBlank()) null else OpenAiClient(key, model)
            }
            else -> null
        }
    }

    private fun currentTime(): String {
        return SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
    }
}