package com.sai8151.urlai.ai

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import com.sai8151.urlai.ai.PerfMetrics
class ClaudeClient(private val apiKey: String, private val modelId: String) : AiClient {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    override suspend fun chat(
        systemPrompt: String,
        history: List<Pair<String, String>>,
        userMessage: String,
        onToken: ((String) -> Unit)?
    ): Triple<String, String?, PerfMetrics> {

        try {
            val messages = JSONArray()

            history.forEach { (role, content) ->
                messages.put(JSONObject().apply {
                    put("role", role)
                    put("content", content)
                })
            }

            messages.put(JSONObject().apply {
                put("role", "user")
                put("content", userMessage)
            })

            val body = JSONObject().apply {
                put("model", modelId)
                put("max_tokens", 1024)
                if (systemPrompt.isNotBlank()) put("system", systemPrompt)
                put("messages", messages)
            }

            val request = Request.Builder()
                .url("https://api.anthropic.com/v1/messages")
                .addHeader("x-api-key", apiKey)
                .addHeader("anthropic-version", "2023-06-01")
                .addHeader("content-type", "application/json")
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .build()

            val response = client.newCall(request).execute()
            val responseBody = response.body?.string() ?: throw Exception("Empty response")

            if (!response.isSuccessful) {
                throw Exception("Claude error ${response.code}: $responseBody")
            }

            val json = JSONObject(responseBody)

            val text = json.getJSONArray("content")
                .getJSONObject(0)
                .getString("text")

            return Triple(
                text,
                null, // no stats for cloud
                PerfMetrics(
                    tps = 0.0,
                    firstLatency = 0,
                    totalTime = 0
                )
            )

        } catch (e: Exception) {
            return Triple(
                "Claude Error: ${e.message}",
                null,
                PerfMetrics(0.0, 0, 0)
            )
        }
    }
}