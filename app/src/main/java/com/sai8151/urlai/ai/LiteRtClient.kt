package com.sai8151.urlai.ai

import android.content.Context
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.SamplerConfig
import com.sai8151.urlai.PreferencesManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext

class LiteRtClient(
    private val context: Context,
    private val modelName: String,
    private val useGpu: Boolean,
    private val temperature: Float,
    private val topK: Int,
    private val topP: Float,
    private val maxHistory: Int
) : AiClient {

    override fun isLocal(): Boolean = true

    companion object {
        private var engine: Engine? = null
        private var loadedModelPath: String? = null
        private var conversation: Conversation? = null
        private var pdfConversionJob: Job? = null
        /**
         * EASY SWITCH
         *
         * true  -> streaming ON
         * false -> streaming OFF
         */
        private const val ENABLE_STREAMING = false
    }

    override suspend fun chat(
        systemPrompt: String,
        history: List<Pair<String, String>>,
        userMessage: String,
        onToken: ((String) -> Unit)?
    ): Triple<String, String?, PerfMetrics> = withContext(Dispatchers.Default) {

        try {
            val file = ModelManager.getModelFile(context)

            if (!file.exists() || file.length() < 1_000_000_000) {
                return@withContext Triple(
                    "Model not downloaded. Please download from settings.",
                    null,
                    PerfMetrics(0.0, 0, 0)
                )
            }

            val modelPath = file.absolutePath

            // Reload engine only if needed
            if (engine == null || loadedModelPath != modelPath) {
                engine?.close()

                val backend = if (useGpu) {
                    Backend.GPU()
                } else {
                    Backend.CPU()
                }

                val config = EngineConfig(
                    modelPath = modelPath,
                    backend = backend,
                    visionBackend = Backend.CPU(),
                    cacheDir = context.cacheDir.absolutePath
                )

                engine = Engine(config)
                engine!!.initialize()

                loadedModelPath = modelPath
                conversation = null
            }

            val activeEngine = engine!!

            // Create conversation only once
            if (conversation == null) {
                conversation = activeEngine.createConversation(
                    ConversationConfig(
                        systemInstruction = Contents.of(systemPrompt),
                        samplerConfig = SamplerConfig(
                            topK = topK,
                            topP = topP.toDouble(),
                            temperature = temperature.toDouble()
                        )
                    )
                )
            }

            val prompt = buildPrompt(history, userMessage)

            val responseBuilder = StringBuilder()

            val startTime = System.currentTimeMillis()
            var firstTokenTime: Long? = null
            var tokenCount = 0

            /**
             * STREAMING MODE
             */
            if (ENABLE_STREAMING && onToken != null) {

                conversation!!
                    .sendMessageAsync(prompt)
                    .collect { msg ->

                        val text = msg.toString()

                        if (text.isNotBlank()) {

                            if (firstTokenTime == null) {
                                firstTokenTime = System.currentTimeMillis()
                            }

                            responseBuilder.append(text)
                            tokenCount++

                            // Send token to UI
                            onToken.invoke(text)
                        }
                    }

            } else {

                /**
                 * NON-STREAMING MODE
                 *
                 * Wait for full response first
                 * Better markdown rendering
                 */
                val fullResponse = conversation!!
                    .sendMessage(prompt)
                    .toString()

                if (fullResponse.isNotBlank()) {

                    firstTokenTime = System.currentTimeMillis()

                    responseBuilder.append(fullResponse)

                    // rough estimate
                    tokenCount = fullResponse.length

                    // Optional: send final full response once
                    onToken?.invoke(fullResponse)
                }
            }

            val endTime = System.currentTimeMillis()

            val firstLatency =
                firstTokenTime?.minus(startTime) ?: -1

            val totalTime =
                endTime - startTime

            val tps =
                if (totalTime > 0)
                    (tokenCount * 1000.0 / totalTime)
                else
                    0.0

            val stats = """
                ⚡ ${"%.2f".format(tps)} tokens/sec
                ⏱ First token: ${firstLatency} ms
                ⏳ Total: ${totalTime} ms
            """.trimIndent()

            val metrics = PerfMetrics(
                tps = tps,
                firstLatency = firstLatency,
                totalTime = totalTime
            )

            val prefs = PreferencesManager(context)

            prefs.saveMetrics(
                tps = metrics.tps,
                latency = metrics.firstLatency.toLong(),
                total = metrics.totalTime.toLong()
            )

            return@withContext Triple(
                responseBuilder.toString(),
                stats,
                metrics
            )

        } catch (e: Exception) {
            return@withContext Triple(
                "LiteRT Error: ${e.message}",
                null,
                PerfMetrics(0.0, -1, 0)
            )
        }
    }
    suspend fun chatWithImage(
        systemPrompt: String,
        imageBytes: ByteArray,
        userMessage: String,
        onToken: ((String) -> Unit)? = null
    ): Triple<String, String?, PerfMetrics> = withContext(Dispatchers.Default) {
        try {
            val file = ModelManager.getModelFile(context)
            if (!file.exists() || file.length() < 1_000_000_000) {
                return@withContext Triple("Model not downloaded.", null, PerfMetrics(0.0, 0, 0))
            }

            val modelPath = file.absolutePath

            if (engine == null || loadedModelPath != modelPath) {
                engine?.close()
                val backend = if (useGpu) Backend.GPU() else Backend.CPU()
                val config = EngineConfig(
                    modelPath = modelPath,
                    backend = backend,
                    visionBackend = Backend.CPU(),
                    cacheDir = context.cacheDir.absolutePath
                )
                engine = Engine(config)
                engine!!.initialize()
                loadedModelPath = modelPath
                conversation = null
            }

            // ✅ Close existing conversation before creating vision one
            conversation?.close()
            conversation = null

            val imageConversation = engine!!.createConversation(
                ConversationConfig(
                    systemInstruction = Contents.of(systemPrompt),
                    samplerConfig = SamplerConfig(
                        topK = topK,
                        topP = topP.toDouble(),
                        temperature = temperature.toDouble()
                    )
                )
            )

            val message = Message.of(
                Content.ImageBytes(imageBytes),
                Content.Text(userMessage)
            )

            val responseBuilder = StringBuilder()
            val startTime = System.currentTimeMillis()
            var firstTokenTime: Long? = null
            var tokenCount = 0

            imageConversation.sendMessageAsync(message).collect { msg ->
                val text = msg.toString()
                if (text.isNotBlank()) {
                    if (firstTokenTime == null) firstTokenTime = System.currentTimeMillis()
                    responseBuilder.append(text)
                    tokenCount++
                    onToken?.invoke(text)
                }
            }

            // ✅ Close vision conversation and restore regular conversation
            imageConversation.close()
            conversation = engine!!.createConversation(
                ConversationConfig(
                    systemInstruction = Contents.of(systemPrompt),
                    samplerConfig = SamplerConfig(
                        topK = topK,
                        topP = topP.toDouble(),
                        temperature = temperature.toDouble()
                    )
                )
            )

            val endTime = System.currentTimeMillis()
            val firstLatency = firstTokenTime?.minus(startTime) ?: -1
            val totalTime = endTime - startTime
            val tps = if (totalTime > 0) tokenCount * 1000.0 / totalTime else 0.0

            Triple(
                responseBuilder.toString(),
                "⚡ ${"%.2f".format(tps)} t/s  ⏱ ${firstLatency}ms  ⏳ ${totalTime}ms",
                PerfMetrics(tps, firstLatency, totalTime)
            )
        } catch (e: Exception) {
            // ✅ On error, also clean up so next call doesn't inherit broken state
            conversation?.close()
            conversation = null
            Triple("LiteRT Vision Error: ${e.message}", null, PerfMetrics(0.0, -1, 0))
        }
    }


    fun resetConversation() {
        conversation?.close()
        conversation = null
    }

    private fun buildPrompt(
        history: List<Pair<String, String>>,
        userMessage: String
    ): String {
        val sb = StringBuilder()

        history
            .takeLast(maxHistory)
            .forEach {
                sb.append(it.first)
                    .append(": ")
                    .append(it.second)
                    .append("\n")
            }

        sb.append("User: ")
            .append(userMessage)
            .append("\nAssistant:")

        return sb.toString()
    }

    private fun getModelFileName(): String {
        return "models/gemma-4-e2b-it.litertlm"
    }
}