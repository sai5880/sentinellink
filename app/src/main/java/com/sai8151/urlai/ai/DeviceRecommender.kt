package com.sai8151.urlai.ai

data class Recommendation(
    val label: String,
    val description: String
)

object DeviceRecommender {

    fun getRecommendation(metrics: PerfMetrics?): Recommendation {

        if (metrics == null || metrics.tps == 0.0) {
            return Recommendation(
                "No Data",
                "Run model once to get recommendation"
            )
        }

        return when {
            metrics.tps < 2.5 -> Recommendation(
                "Low-End Mode",
                "Use CPU, Q4 model, context=2-3"
            )

            metrics.tps < 6.0 -> Recommendation(
                "Balanced Mode",
                "Use CPU/GPU, context=3-5"
            )

            else -> Recommendation(
                "High Performance",
                "Use GPU, higher context (5-8), higher topK"
            )
        }
    }
}