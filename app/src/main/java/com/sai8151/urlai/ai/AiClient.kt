package com.sai8151.urlai.ai

data class PerfMetrics(
    val tps: Double,
    val firstLatency: Long,
    val totalTime: Long
)

interface AiClient {

    suspend fun chat(
        systemPrompt: String,
        history: List<Pair<String, String>>,
        userMessage: String,
        onToken: ((String) -> Unit)? = null
    ): Triple<String, String?, PerfMetrics>

    fun isLocal(): Boolean = false
}