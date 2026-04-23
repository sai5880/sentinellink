package com.sai8151.urlai

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

// ✅ MUST be top-level
val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "url_ai_prefs")

class PreferencesManager(private val context: Context) {

    companion object {
        val KEY_LAST_TPS = floatPreferencesKey("last_tps")
        val KEY_LAST_LATENCY = longPreferencesKey("last_latency")
        val KEY_LAST_TOTAL = longPreferencesKey("last_total")

        val KEY_URL = stringPreferencesKey("target_url")
        val KEY_GEMINI_KEY = stringPreferencesKey("gemini_api_key")
        val KEY_CLAUDE_KEY = stringPreferencesKey("claude_api_key")
        val KEY_OPENAI_KEY = stringPreferencesKey("openai_api_key")
        val KEY_SYSTEM_PROMPT = stringPreferencesKey("system_prompt")
        val KEY_SELECTED_MODEL = stringPreferencesKey("selected_model")

        // 🔥 NEW KEYS (for LiteRT controls)
        val KEY_USE_GPU = booleanPreferencesKey("use_gpu")
        val KEY_TEMP = floatPreferencesKey("temperature")
        val KEY_TOPK = intPreferencesKey("top_k")
        val KEY_TOPP = floatPreferencesKey("top_p")
        val KEY_CONTEXT = intPreferencesKey("context_size")
    }

    // ===== EXISTING =====
    val targetUrl: Flow<String> = context.dataStore.data.map { it[KEY_URL] ?: "" }
    val geminiKey: Flow<String> = context.dataStore.data.map { it[KEY_GEMINI_KEY] ?: "" }
    val claudeKey: Flow<String> = context.dataStore.data.map { it[KEY_CLAUDE_KEY] ?: "" }
    val openAiKey: Flow<String> = context.dataStore.data.map { it[KEY_OPENAI_KEY] ?: "" }
    val systemPrompt: Flow<String> = context.dataStore.data.map { it[KEY_SYSTEM_PROMPT] ?: "" }
    val selectedModel: Flow<String> = context.dataStore.data.map { it[KEY_SELECTED_MODEL] ?: "" }
    val lastTps: Flow<Float> = context.dataStore.data.map { it[KEY_LAST_TPS] ?: 0f }
    val lastLatency: Flow<Long> = context.dataStore.data.map { it[KEY_LAST_LATENCY] ?: 0L }
    val lastTotal: Flow<Long> = context.dataStore.data.map { it[KEY_LAST_TOTAL] ?: 0L }
    // ===== NEW SETTINGS =====
    val useGpu: Flow<Boolean> = context.dataStore.data.map { it[KEY_USE_GPU] ?: false }
    val temperature: Flow<Float> = context.dataStore.data.map { it[KEY_TEMP] ?: 0.7f }
    val topK: Flow<Int> = context.dataStore.data.map { it[KEY_TOPK] ?: 40 }
    val topP: Flow<Float> = context.dataStore.data.map { it[KEY_TOPP] ?: 0.9f }
    val contextSize: Flow<Int> = context.dataStore.data.map { it[KEY_CONTEXT] ?: 3 }

    // ===== SAVE ALL =====
    suspend fun save(
        url: String,
        gemini: String,
        claude: String,
        openAi: String,
        prompt: String,
        model: String,

        // 🔥 new params
        useGpu: Boolean,
        temperature: Float,
        topK: Int,
        topP: Float,
        contextSize: Int
    ) {
        context.dataStore.edit { prefs ->
            prefs[KEY_URL] = url
            prefs[KEY_GEMINI_KEY] = gemini
            prefs[KEY_CLAUDE_KEY] = claude
            prefs[KEY_OPENAI_KEY] = openAi
            prefs[KEY_SYSTEM_PROMPT] = prompt
            prefs[KEY_SELECTED_MODEL] = model

            prefs[KEY_USE_GPU] = useGpu
            prefs[KEY_TEMP] = temperature
            prefs[KEY_TOPK] = topK
            prefs[KEY_TOPP] = topP
            prefs[KEY_CONTEXT] = contextSize
        }
    }
    suspend fun saveMetrics(
        tps: Double,
        latency: Long,
        total: Long
    ) {
        context.dataStore.edit { prefs ->
            prefs[KEY_LAST_TPS] = tps.toFloat()
            prefs[KEY_LAST_LATENCY] = latency
            prefs[KEY_LAST_TOTAL] = total
        }
    }
    suspend fun saveSelectedModel(model: String) {
        context.dataStore.edit { it[KEY_SELECTED_MODEL] = model }
    }
}