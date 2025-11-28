package com.example.llmedgeexample

import android.content.Context
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
import io.aatricks.llmedge.StableDiffusion
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class VideoGenerationActivity : AppCompatActivity() {

    private val promptInput: EditText by lazy(LazyThreadSafetyMode.NONE) { findViewById(R.id.videoPromptInput) }
    private val generateButton: Button by lazy(LazyThreadSafetyMode.NONE) { findViewById(R.id.btnGenerateVideo) }
    private val cancelButton: Button by lazy(LazyThreadSafetyMode.NONE) { findViewById(R.id.btnCancelVideo) }
    private val progressBar: ProgressBar by lazy(LazyThreadSafetyMode.NONE) { findViewById(R.id.videoProgressBar) }
    private val progressLabel: TextView by lazy(LazyThreadSafetyMode.NONE) { findViewById(R.id.videoProgressLabel) }
    private val previewImage: ImageView by lazy(LazyThreadSafetyMode.NONE) { findViewById(R.id.videoPreview) }
    private val metricsLabel: TextView by lazy(LazyThreadSafetyMode.NONE) { findViewById(R.id.videoMetricsLabel) }

    private var generationJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_video_generation)

        // Views are initialized lazily via delegates

        progressBar.max = 100
        progressBar.progress = 0
        progressBar.visibility = View.GONE

        generateButton.setOnClickListener { startGeneration() }
        cancelButton.setOnClickListener { cancelGeneration() }
    }

    private fun startGeneration() {
        if (generationJob?.isActive == true) {
            Toast.makeText(this, R.string.video_status_generation_running, Toast.LENGTH_SHORT).show()
            return
        }

        // Show loading UI immediately on main thread
        updateProgressUI(0, getString(R.string.video_status_loading_model))
        progressBar.visibility = View.VISIBLE
        progressBar.isIndeterminate = true
        generateButton.isEnabled = false

        generationJob = lifecycleScope.launch(Dispatchers.Default) {
            try {
                val prompt = promptInput.text.toString().ifBlank { DEFAULT_PROMPT }
                updateProgressUI(0, getString(R.string.video_status_generating))

                val params = io.aatricks.llmedge.LLMEdgeManager.VideoGenerationParams(
                    prompt = prompt,
                    width = 256,
                    height = 256,
                    videoFrames = 16,
                    steps = 20,
                    cfgScale = 7.0f,
                    flashAttn = true, // Enable Flash Attention
                    forceSequentialLoad = false // Auto-detect
                )

                val frames = io.aatricks.llmedge.LLMEdgeManager.generateVideo(
                    context = applicationContext,
                    params = params
                ) { phase, current, total ->
                    val status = if (total > 0) "$phase ($current/$total)" else phase
                    updateProgressUI(0, status)
                }

                if (frames.isNotEmpty()) {
                    withContext(Dispatchers.Main) {
                        previewImage.setImageBitmap(frames.first())
                    }
                }

                withContext(Dispatchers.Main) {
                    updateProgressUI(100, getString(R.string.video_status_complete, frames.size))
                }
            } catch (cancelled: CancellationException) {
                updateProgressUI(0, getString(R.string.video_status_cancelled))
            } catch (oom: OutOfMemoryError) {
                android.util.Log.e("VideoGeneration", "Out of memory during generation", oom)
                updateProgressUI(
                    0,
                    getString(R.string.video_status_failed, "Out of memory. Close other apps and try again.")
                )
            } catch (t: Throwable) {
                android.util.Log.e("VideoGeneration", "Failed during generation", t)
                updateProgressUI(0, getString(R.string.video_status_failed, t.localizedMessage ?: "error"))
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
        updateProgressUI(0, getString(R.string.video_status_cancelled))
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

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        when (level) {
            android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW,
            android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL -> {
                android.util.Log.w(
                    "VideoGeneration",
                    "System memory low (level=$level), cancelling generation if active"
                )
                if (generationJob?.isActive == true) {
                    cancelGeneration()
                    runOnUiThread {
                        Toast.makeText(
                            this,
                            "Generation cancelled due to low memory",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        generationJob?.cancel()
        // Model lifecycle is managed by LLMEdgeManager
    }

    companion object {
        private const val DEFAULT_PROMPT = "A dog running in the park"
    }
}
