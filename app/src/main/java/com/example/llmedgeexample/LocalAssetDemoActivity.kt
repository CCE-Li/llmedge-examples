package com.example.llmedgeexample

import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import io.aatricks.llmedge.LLMEdgeManager
import io.aatricks.llmedge.SmolLM
import io.aatricks.llmedge.util.MemoryMetrics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.Locale

class LocalAssetDemoActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_local_asset_demo)

        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        val output = findViewById<TextView>(R.id.output)

        lifecycleScope.launch {
            val before = MemoryMetrics.snapshot(this@LocalAssetDemoActivity)
            withContext(Dispatchers.Main) {
                output.text = "Memory before:\n" +
                    before.toPretty(this@LocalAssetDemoActivity) + "\n\n"
            }

            val modelPath = copyAssetIfNeeded("YourModel.gguf")
            val modelFile = File(modelPath)
            withContext(Dispatchers.Main) {
                output.append(
                    "Preparing model...\nPath: $modelPath\nExists: ${modelFile.exists()}\nSize: ${if (modelFile.exists()) "${modelFile.length() / (1024 * 1024)} MB" else "n/a"}\n\n",
                )
            }

            val params = LLMEdgeManager.TextGenerationParams(
                prompt = "Say 'hello from llmedge'.",
                modelPath = modelPath
            )

            try {
                val blocking = LLMEdgeManager.generateText(this@LocalAssetDemoActivity, params)
                val blockingMetrics = LLMEdgeManager.getLastTextGenerationMetrics()
                val afterBlocking = MemoryMetrics.snapshot(this@LocalAssetDemoActivity)
                withContext(Dispatchers.Main) {
                    output.append("Blocking response:\n\n$blocking\n\n")
                    blockingMetrics?.let { output.append("Blocking metrics: ${formatMetrics(it)}\n\n") }
                    output.append("After blocking:\n" + afterBlocking.toPretty(this@LocalAssetDemoActivity) + "\n\nStreaming response:\n\n")
                }
            } catch (t: Throwable) {
                withContext(Dispatchers.Main) {
                    output.append("\nBlocking failed: ${t.message}\n")
                }
            }

            val sb = StringBuilder()
            val streamParams = LLMEdgeManager.TextGenerationParams(
                prompt = "Write a short haiku about Android.",
                modelPath = modelPath
            )
            
            val ok = withContext(Dispatchers.Default) {
                withTimeoutOrNull(30000L) {
                    LLMEdgeManager.generateText(this@LocalAssetDemoActivity, streamParams) { piece ->
                         sb.append(piece)
                         runOnUiThread {
                             val currentText = output.text.toString()
                             val prefix = currentText.substringBefore("Streaming response:") + "Streaming response:\n\n"
                             output.text = prefix + sb.toString()
                         }
                    }
                    true
                } != null
            }
            
            val streamingMetrics = if (ok) LLMEdgeManager.getLastTextGenerationMetrics() else null
            val afterStream = MemoryMetrics.snapshot(this@LocalAssetDemoActivity)
            withContext(Dispatchers.Main) {
                output.append(if (ok) "\n\n[done]\n\n" else "\n\n[stream timed out]\n\n")
                streamingMetrics?.let { output.append("Streaming metrics: ${formatMetrics(it)}\n\n") }
                output.append("After streaming:\n" + afterStream.toPretty(this@LocalAssetDemoActivity))
            }
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun copyAssetIfNeeded(assetName: String): String {
        val outFile = File(filesDir, assetName)
        if (!outFile.exists()) {
            assets.open(assetName).use { input ->
                outFile.outputStream().use { output -> input.copyTo(output) }
            }
        }
        return outFile.absolutePath
    }

    private fun formatMetrics(metrics: SmolLM.GenerationMetrics): String {
        val throughput = String.format(Locale.US, "%.2f", metrics.tokensPerSecond)
        val duration = String.format(Locale.US, "%.2f", metrics.elapsedSeconds)
        return "tokens=${metrics.tokenCount} | $throughput tok/s | $duration s"
    }
}
