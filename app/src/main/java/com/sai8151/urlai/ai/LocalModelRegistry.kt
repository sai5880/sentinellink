package com.sai8151.urlai.ai

data class LocalModelInfo(
    val id: String,
    val displayName: String,
    val sizeGB: Double,
    val ramRequiredGB: Double
)

object LocalModelRegistry {

    val models = listOf(
        LocalModelInfo("gemma-4-e2b-q4", "Gemma 4 E2B (Fast • 3.2GB)", 3.2, 4.0),
        LocalModelInfo("gemma-4-e2b-sfp8", "Gemma 4 E2B (Balanced • 4.6GB)", 4.6, 6.0),
        LocalModelInfo("gemma-4-e2b-bf16", "Gemma 4 E2B (High • 9.6GB)", 9.6, 12.0),

        LocalModelInfo("gemma-4-e4b-q4", "Gemma 4 E4B (Fast • 5GB)", 5.0, 6.0),
        LocalModelInfo("gemma-4-e4b-sfp8", "Gemma 4 E4B (Balanced • 7.5GB)", 7.5, 10.0),
        LocalModelInfo("gemma-4-e4b-bf16", "Gemma 4 E4B (High • 15GB)", 15.0, 18.0),
    )

    fun isLocalModel(model: String): Boolean {
        return models.any { it.id == model }
    }
}