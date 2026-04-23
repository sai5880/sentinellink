package com.sai8151.urlai.ai

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class GeminiClient(private val apiKey: String, private val modelId: String) : AiClient {

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
            val contents = JSONArray()

            history.forEach { (role, content) ->
                val geminiRole = if (role == "user") "user" else "model"
                contents.put(JSONObject().apply {
                    put("role", geminiRole)
                    put("parts", JSONArray().put(JSONObject().put("text", content)))
                })
            }

            contents.put(JSONObject().apply {
                put("role", "user")
                put("parts", JSONArray().put(JSONObject().put("text", userMessage)))
            })

            val body = JSONObject().apply {
                if (systemPrompt.isNotBlank()) {
                    put("system_instruction", JSONObject().apply {
                        put("parts", JSONArray().put(JSONObject().put("text", systemPrompt)))
                    })
                }
                put("contents", contents)
            }

            val url = "https://generativelanguage.googleapis.com/v1beta/models/$modelId:generateContent?key=$apiKey"

            val request = Request.Builder()
                .url(url)
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .build()

            val response = client.newCall(request).execute()
            val responseBody = response.body?.string() ?: throw Exception("Empty response")

            if (!response.isSuccessful) {
                throw Exception("Gemini error ${response.code}: $responseBody")
            }

            val json = JSONObject(responseBody)

            val text = json.getJSONArray("candidates")
                .getJSONObject(0)
                .getJSONObject("content")
                .getJSONArray("parts")
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
                "Gemini Error: ${e.message}",
                null,
                PerfMetrics(0.0, 0, 0)
            )
        }
    }
}