package com.sai8151.urlai

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.sai8151.urlai.ai.LocalModelRegistry
import com.sai8151.urlai.databinding.ActivitySettingsBinding
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import com.sai8151.urlai.ai.DeviceRecommender
import com.sai8151.urlai.ai.ModelManager
import com.sai8151.urlai.ai.PerfMetrics
import android.graphics.Color

import androidx.core.view.WindowInsetsControllerCompat
class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var prefs: PreferencesManager

    private val models = listOf(
        "gemma-4-e2b-q4"
//        "gemini-2.5-pro",
//        "gemini-2.5-flash",
//        "claude-opus-4-5",
//        "claude-sonnet-4-5"
    )

    @SuppressLint("UseKtx")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.topBar.setNavigationIcon(R.drawable.ic_arrow_back)

        binding.topBar.setNavigationOnClickListener {
            finish()
        }
        prefs = PreferencesManager(this)

        // Setup dropdown (AutoCompleteTextView)
        val adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, models)
        binding.spinnerModel.setAdapter(adapter)

        // Safe model check
        try {
            if (ModelManager.isModelDownloaded(this)) {
                binding.btnDownloadModel.text = "Model Downloaded"
                binding.btnDownloadModel.isEnabled = false
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // Download button
        binding.btnDownloadModel.setOnClickListener {
            binding.progressDownload.visibility = View.VISIBLE

            lifecycleScope.launch {
                try {
                    ModelManager.downloadModel(this@SettingsActivity) { progress ->
                        binding.progressDownload.progress = progress
                    }

                    binding.btnDownloadModel.text = "Downloaded"
                    binding.btnDownloadModel.isEnabled = false

                    Toast.makeText(this@SettingsActivity, "Model ready!", Toast.LENGTH_SHORT).show()

                } catch (e: Exception) {
                    Toast.makeText(this@SettingsActivity, "Download failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }

        // Load settings safely
        lifecycleScope.launch {
            try {
                binding.etUrl.setText(prefs.targetUrl.first())
                binding.etGeminiKey.setText(prefs.geminiKey.first())
                binding.etClaudeKey.setText(prefs.claudeKey.first())
                binding.etOpenAiKey.setText(prefs.openAiKey.first())
                binding.etSystemPrompt.setText(prefs.systemPrompt.first())

                val savedModel = prefs.selectedModel.first()
                binding.spinnerModel.setText(savedModel, false)

                val isLocal = LocalModelRegistry.isLocalModel(savedModel)

                binding.switchGpu.visibility = if (isLocal) View.VISIBLE else View.GONE
                binding.etTemperature.visibility = if (isLocal) View.VISIBLE else View.GONE
                binding.etTopK.visibility = if (isLocal) View.VISIBLE else View.GONE
                binding.etTopP.visibility = if (isLocal) View.VISIBLE else View.GONE
                binding.etContext.visibility = if (isLocal) View.VISIBLE else View.GONE

                if (isLocal) {
                    val tps = prefs.lastTps.first()
                    val latency = prefs.lastLatency.first()
                    val total = prefs.lastTotal.first()

                    if (tps > 0 && latency > 0 && total > 0) {

                        binding.tvPerformance.text = """
        Performance:
        TPS: %.2f
        First Token: %d ms
        Total: %d ms
    """.trimIndent().format(tps, latency, total)

                        val metrics = PerfMetrics(
                            tps = tps.toDouble(),
                            firstLatency = latency,
                            totalTime = total
                        )

                        val rec = DeviceRecommender.getRecommendation(metrics)

                        binding.tvRecommendation.text =
                            "${rec.label} → ${rec.description}"

                    } else {
                        binding.tvPerformance.text =
                            "Performance: Not measured yet"

                        binding.tvRecommendation.text =
                            "Recommendation: Run local model once to generate metrics"
                    }

                } else {
                    binding.tvPerformance.text = "Performance: N/A (Cloud model)"
                    binding.tvRecommendation.text = "Recommendation: Not applicable"
                }

            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        // Save
        binding.btnSave.setOnClickListener {
            lifecycleScope.launch {
                val temperature = binding.etTemperature.text.toString().toFloatOrNull() ?: 0.7f
                val topK = binding.etTopK.text.toString().toIntOrNull() ?: 40
                val topP = binding.etTopP.text.toString().toFloatOrNull() ?: 0.9f
                val contextSize = binding.etContext.text.toString().toIntOrNull() ?: 3

                val model = binding.spinnerModel.text.toString()
                val isLocal = LocalModelRegistry.isLocalModel(model)

                prefs.save(
                    url = binding.etUrl.text.toString().trim(),
                    gemini = binding.etGeminiKey.text.toString().trim(),
                    claude = binding.etClaudeKey.text.toString().trim(),
                    openAi = binding.etOpenAiKey.text.toString().trim(),
                    prompt = binding.etSystemPrompt.text.toString().trim(),
                    model = model,

                    useGpu = if (isLocal) binding.switchGpu.isChecked else false,
                    temperature = if (isLocal) temperature else 0.7f,
                    topK = if (isLocal) topK else 40,
                    topP = if (isLocal) topP else 0.9f,
                    contextSize = if (isLocal) contextSize else 3
                )

                Toast.makeText(this@SettingsActivity, "Settings saved", Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }
}