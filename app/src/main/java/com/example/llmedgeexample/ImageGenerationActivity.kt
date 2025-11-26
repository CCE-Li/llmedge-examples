package com.example.llmedgeexample

import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ImageGenerationActivity : AppCompatActivity() {

    private lateinit var promptInput: EditText
    private lateinit var generateButton: Button
    private lateinit var cancelButton: Button
    private lateinit var progressBar: ProgressBar
    private lateinit var progressLabel: TextView
    private lateinit var previewImage: ImageView

    private var generationJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_video_generation) // Reusing layout for simplicity

        promptInput = findViewById(R.id.videoPromptInput)
        generateButton = findViewById(R.id.btnGenerateVideo)
        generateButton.text = "Generate Image"
        cancelButton = findViewById(R.id.btnCancelVideo)
        progressBar = findViewById(R.id.videoProgressBar)
        progressLabel = findViewById(R.id.videoProgressLabel)
        previewImage = findViewById(R.id.videoPreview)

        progressBar.max = 100
        progressBar.progress = 0
        progressBar.visibility = View.GONE

        generateButton.setOnClickListener { startGeneration() }
        cancelButton.setOnClickListener { cancelGeneration() }
    }

    private fun startGeneration() {
        if (generationJob?.isActive == true) {
            Toast.makeText(this, "Generation already running", Toast.LENGTH_SHORT).show()
            return
        }

        updateProgressUI(0, "Loading model...")
        progressBar.visibility = View.VISIBLE
        progressBar.isIndeterminate = true
        generateButton.isEnabled = false

        generationJob = lifecycleScope.launch(Dispatchers.Default) {
            try {
                val prompt = promptInput.text.toString().ifBlank { DEFAULT_PROMPT }
                updateProgressUI(0, "Generating image...")

                val params = io.aatricks.llmedge.LLMEdgeManager.ImageGenerationParams(
                    prompt = prompt,
                    width = 512,
                    height = 512,
                    steps = 20,
                    cfgScale = 7.0f,
                    flashAttn = true,
                    forceSequentialLoad = false // Auto-detect
                )

                val bitmap = io.aatricks.llmedge.LLMEdgeManager.generateImage(
                    context = applicationContext,
                    params = params
                ) { phase, current, total ->
                    val status = if (total > 0) "$phase ($current/$total)" else phase
                    updateProgressUI(0, status)
                }

                if (bitmap != null) {
                    withContext(Dispatchers.Main) {
                        previewImage.setImageBitmap(bitmap)
                    }
                }

                withContext(Dispatchers.Main) {
                    updateProgressUI(100, "Generation complete")
                }
            } catch (cancelled: CancellationException) {
                updateProgressUI(0, "Cancelled")
            } catch (oom: OutOfMemoryError) {
                android.util.Log.e("ImageGeneration", "Out of memory", oom)
                updateProgressUI(0, "Out of memory")
            } catch (t: Throwable) {
                android.util.Log.e("ImageGeneration", "Failed", t)
                updateProgressUI(0, "Failed: ${t.localizedMessage}")
            } finally {
                withContext(Dispatchers.Main) {
                    progressBar.visibility = View.GONE
                    generateButton.isEnabled = true
                    generationJob = null
                }
            }
        }
    }

    private fun cancelGeneration() {
        generationJob?.cancel()
        io.aatricks.llmedge.LLMEdgeManager.cancelGeneration()
        updateProgressUI(0, "Cancelled")
    }

    private fun updateProgressUI(percent: Int, status: String) {
        runOnUiThread {
            progressBar.visibility = View.VISIBLE
            progressBar.isIndeterminate = percent == 0
            if (!progressBar.isIndeterminate) {
                progressBar.progress = percent
            }
            progressLabel.text = status
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        generationJob?.cancel()
    }

    companion object {
        private const val DEFAULT_PROMPT = "A futuristic city"
    }
}
