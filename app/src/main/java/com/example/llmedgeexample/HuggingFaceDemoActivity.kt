package com.example.llmedgeexample

import android.os.Bundle
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import io.aatricks.llmedge.SmolLM
import io.aatricks.llmedge.SmolLM.ThinkingMode
import java.util.Locale
import kotlinx.coroutines.launch

class HuggingFaceDemoActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_hugging_face_demo)

        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        val inputModelId = findViewById<EditText>(R.id.inputModelId)
        val inputRevision = findViewById<EditText>(R.id.inputRevision)
        val inputFilename = findViewById<EditText>(R.id.inputFilename)
        val forceDownload = findViewById<CheckBox>(R.id.checkboxForceDownload)
        val disableThinking = findViewById<CheckBox>(R.id.checkboxDisableThinking)
        val inputReasoningBudget = findViewById<EditText>(R.id.inputReasoningBudget)
        val textStatus = findViewById<TextView>(R.id.textStatus)
        val textOutput = findViewById<TextView>(R.id.textOutput)
        val button = findViewById<Button>(R.id.btnDownloadAndRun)

        button.setOnClickListener {
            val modelId = inputModelId.text.toString().trim()
            val revision = inputRevision.text.toString().takeIf { it.isNotBlank() } ?: "main"
            val filename = inputFilename.text.toString().trim().takeIf { it.isNotEmpty() }
            val disableThinkingChecked = disableThinking.isChecked
            val reasoningBudgetText = inputReasoningBudget.text.toString().trim()
            val parsedReasoningBudget =
                    reasoningBudgetText.takeIf { it.isNotEmpty() }?.toIntOrNull()

            if (modelId.isEmpty()) {
                if (isUiActive()) {
                    textStatus.text = "Please provide a model repository name."
                }
                return@setOnClickListener
            }

            if (reasoningBudgetText.isNotEmpty() && parsedReasoningBudget == null) {
                if (isUiActive()) {
                    textStatus.text = "Reasoning budget must be an integer (e.g. 0, -1)."
                }
                return@setOnClickListener
            }

            if (isUiActive()) {
                textStatus.text = "Starting download..."
                textOutput.text = ""
                button.isEnabled = false
            }

            lifecycleScope.launch {
                try {
                    // 1. Download/Ensure model is available
                    val modelFile =
                            io.aatricks.llmedge.LLMEdgeManager.downloadModel(
                                    context = this@HuggingFaceDemoActivity,
                                    modelId = modelId,
                                    filename = filename,
                                    revision = revision,
                                    onProgress = { downloaded, total ->
                                        runOnUiThread {
                                            if (isUiActive()) {
                                                textStatus.text = formatProgress(downloaded, total)
                                            }
                                        }
                                    }
                            )

                    if (isUiActive()) {
                        textStatus.text = buildString {
                            append("Model ready: ${modelFile.name}\n")
                            append("Path: ${modelFile.absolutePath}\n")
                        }
                    }

                    // 2. Generate Text
                    val thinkingMode =
                            if (disableThinkingChecked)
                                    io.aatricks.llmedge.SmolLM.ThinkingMode.DISABLED
                            else io.aatricks.llmedge.SmolLM.ThinkingMode.DEFAULT

                    val params =
                            io.aatricks.llmedge.LLMEdgeManager.TextGenerationParams(
                                    prompt =
                                            "List two quick facts about running GGUF models on Android.",
                                    systemPrompt = "You are a concise assistant running on-device.",
                                    modelPath = modelFile.absolutePath,
                                    modelId = modelId, // Pass ID for tracking
                                    modelFilename = filename
                                                    ?: modelFile.name, // Pass filename for tracking
                                    revision = revision,
                                    thinkingMode = thinkingMode,
                                    reasoningBudget = parsedReasoningBudget
                            )

                    // We use generateText which handles loading internally
                    val response =
                            io.aatricks.llmedge.LLMEdgeManager.generateText(
                                    context = this@HuggingFaceDemoActivity,
                                    params = params
                            )

                    val metrics = io.aatricks.llmedge.LLMEdgeManager.getLastTextGenerationMetrics()

                    if (isUiActive()) {
                        textOutput.text = buildString {
                            appendLine("Response:")
                            appendLine()
                            appendLine(response.trim())
                            appendLine()
                            metrics?.let {
                                appendLine(
                                        "Metrics: tokens=${it.tokenCount}, " +
                                                "throughput=${"%.2f".format(Locale.US, it.tokensPerSecond)} tok/s, " +
                                                "duration=${"%.2f".format(Locale.US, it.elapsedSeconds)} s",
                                )
                            }
                            appendLine(
                                    "Thinking mode: $thinkingMode (budget=$parsedReasoningBudget)"
                            )
                            appendLine()
                            append("Stored at: ${modelFile.absolutePath}")
                        }
                    }
                } catch (t: Throwable) {
                    if (isUiActive()) {
                        textStatus.text = "Failed: ${t.message}"
                        textOutput.text = ""
                    }
                } finally {
                    if (isUiActive()) {
                        button.isEnabled = true
                    }
                }
            }
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun formatProgress(downloaded: Long, total: Long?): String {
        val downloadedMb = downloaded / (1024.0 * 1024.0)
        val totalMb = total?.div(1024.0 * 1024.0)
        return if (totalMb != null && totalMb > 0) {
            "Downloading: ${String.format(Locale.US, "%.2f", downloadedMb)} MB / ${String.format(Locale.US, "%.2f", totalMb)} MB"
        } else {
            "Downloading: ${String.format(Locale.US, "%.2f", downloadedMb)} MB"
        }
    }

    private fun isUiActive(): Boolean =
            lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) &&
                    !isFinishing &&
                    !isDestroyed
}
