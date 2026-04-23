package com.sai8151.urlai.ai

data class TunedConfig(
    val useGpu: Boolean,
    val topK: Int,
    val topP: Float,
    val temperature: Float,
    val contextSize: Int
)

object AutoTuner {

    fun tune(
        current: TunedConfig,
        metrics: PerfMetrics
    ): TunedConfig {

        var newConfig = current.copy()

        // VERY SLOW
        if (metrics.tps < 2.0 || metrics.firstLatency > 4000) {
            newConfig = newConfig.copy(
                useGpu = false,
                topK = (current.topK - 10).coerceAtLeast(20),
                contextSize = (current.contextSize - 1).coerceAtLeast(2),
                temperature = 0.6f
            )
        }

        // MEDIUM
        else if (metrics.tps < 5.0) {
            newConfig = newConfig.copy(
                topK = (current.topK - 5).coerceAtLeast(25),
                contextSize = (current.contextSize - 1).coerceAtLeast(3)
            )
        }

        // FAST DEVICE
        else if (metrics.tps > 8.0 && metrics.firstLatency < 1500) {
            newConfig = newConfig.copy(
                topK = (current.topK + 5).coerceAtMost(80),
                contextSize = (current.contextSize + 1).coerceAtMost(8),
                temperature = 0.75f
            )
        }
        return newConfig
    }
}