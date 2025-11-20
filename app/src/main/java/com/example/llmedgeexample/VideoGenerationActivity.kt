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

    private lateinit var promptInput: EditText
    private lateinit var generateButton: Button
    private lateinit var cancelButton: Button
    private lateinit var progressBar: ProgressBar
    private lateinit var progressLabel: TextView
    private lateinit var previewImage: ImageView
    private lateinit var metricsLabel: TextView

    private var generationJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_video_generation)

        promptInput = findViewById(R.id.videoPromptInput)
        generateButton = findViewById(R.id.btnGenerateVideo)
        cancelButton = findViewById(R.id.btnCancelVideo)
        progressBar = findViewById(R.id.videoProgressBar)
        progressLabel = findViewById(R.id.videoProgressLabel)
        previewImage = findViewById(R.id.videoPreview)
        metricsLabel = findViewById(R.id.videoMetricsLabel)

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
                // Aggressive memory management before loading
                prepareMemoryForLoading()

                val model = ensureModelLoaded()
                val prompt = promptInput.text.toString().ifBlank { DEFAULT_PROMPT }
                updateProgressUI(0, getString(R.string.video_status_generating))

                val params = StableDiffusion.VideoGenerateParams(
                    prompt = prompt,
                    width = 256,
                    height = 256,
                    videoFrames = 16,
                    steps = 20,
                    cfgScale = 7.0f,
                )

                val frames = model.txt2vid(params) { step, totalSteps, currentFrame, totalFrames, _ ->
                    val percent = ((currentFrame.toFloat() / totalFrames.toFloat()) * 100f).toInt().coerceIn(0, 100)
                    val status = getString(
                        R.string.video_status_progress_format,
                        currentFrame,
                        totalFrames,
                        step,
                        totalSteps,
                    )
                    updateProgressUI(percent, status)
                }

                if (frames.isNotEmpty()) {
                    withContext(Dispatchers.Main) {
                        previewImage.setImageBitmap(frames.first())
                    }
                }

                val metrics = model.getLastGenerationMetrics()
                withContext(Dispatchers.Main) {
                    metricsLabel.text = metrics?.let {
                        getString(
                            R.string.video_status_metrics_template,
                            frames.size,
                            "%.2f".format(it.framesPerSecond),
                            "%.2f".format(it.totalTimeSeconds),
                            it.peakMemoryUsageMb,
                        )
                    } ?: getString(R.string.video_status_metrics_pending)
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

    /**
     * Prepares memory for model loading by requesting GC and trimming caches
     */
    private fun prepareMemoryForLoading() {
        android.util.Log.d("VideoGeneration", "Preparing memory for model loading")

        // Force garbage collection multiple times
        System.gc()
        Thread.sleep(100)
        System.gc()

        // Trim memory caches
        onTrimMemory(android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW)

        // Log memory state
        val runtime = Runtime.getRuntime()
        val usedMemMB = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024)
        val maxMemMB = runtime.maxMemory() / (1024 * 1024)
        val freeMemMB = maxMemMB - usedMemMB

        android.util.Log.d(
            "VideoGeneration",
            "Memory before loading: ${usedMemMB}MB used, ${freeMemMB}MB free of ${maxMemMB}MB max"
        )

        // Check if we have enough memory
        val activityManager = getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        val memoryInfo = android.app.ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memoryInfo)
        val availMemMB = memoryInfo.availMem / (1024 * 1024)

        android.util.Log.d("VideoGeneration", "System available memory: ${availMemMB}MB")

        if (availMemMB < 2000) {
            android.util.Log.w(
                "VideoGeneration",
                "Low memory warning: only ${availMemMB}MB available. Model loading may fail."
            )
        }
    }

    private suspend fun ensureModelLoaded(): StableDiffusion {
        return VideoModelManager.getOrLoadModel(
            context = applicationContext,
            forceReload = false
        ) { phase, current, total ->
            // updateProgressUI already uses runOnUiThread internally
            updateProgressUI(0, "$phase ($current/$total)")
        }
    }

    private fun cancelGeneration() {
        generationJob?.cancel()
        VideoModelManager.getCachedModelOrNull()?.cancelGeneration()
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
        // Model lifecycle is managed by VideoModelManager
        // Don't close the model here as it may be reused
    }

    companion object {
        private const val DEFAULT_PROMPT = "A dog running in the park"
    }
}
