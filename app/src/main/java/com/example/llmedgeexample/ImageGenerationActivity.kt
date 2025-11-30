package com.example.llmedgeexample

import android.app.ActivityManager
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
import io.aatricks.llmedge.LLMEdgeManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Activity for image generation using MeinaMix SD 1.5 model.
 * 
 * Optimized for memory efficiency with:
 * - Proper model loading/unloading via LLMEdgeManager
 * - Memory state logging for debugging
 * - Graceful error handling for OOM
 */
class ImageGenerationActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "ImageGenerationActivity"
        private const val DEFAULT_PROMPT = "A futuristic city"
        private const val BYTES_IN_MB = 1024L * 1024L
    }

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
        setContentView(R.layout.activity_image_generation) // Use image-specific layout

        // Prefer performance mode during interactive examples to favor throughput (disable for memory-constrained devices)
        io.aatricks.llmedge.LLMEdgeManager.preferPerformanceMode = true

        generateButton.text = "Generate Image"

        progressBar.max = 100
        progressBar.progress = 0
        progressBar.visibility = View.GONE

        generateButton.setOnClickListener { startGeneration() }
        cancelButton.setOnClickListener { cancelGeneration() }

        // Log initial memory state
        logMemoryState("Activity created")
    }

    private fun startGeneration() {
        if (generationJob?.isActive == true) {
            Toast.makeText(this, "Generation already running", Toast.LENGTH_SHORT).show()
            return
        }

        // Check available memory
        val availMemMB = getAvailableMemoryMB()
        android.util.Log.i(TAG, "Starting generation with ${availMemMB}MB available")
        
        if (availMemMB < 2000) {
            Toast.makeText(
                this,
                "Low memory (${availMemMB}MB). Close other apps for better results.",
                Toast.LENGTH_LONG
            ).show()
        }

        updateProgressUI(0, "Loading model...")
        progressBar.visibility = View.VISIBLE
        progressBar.isIndeterminate = true
        generateButton.isEnabled = false

        // Use Dispatchers.IO for native JNI operations - it has more threads for blocking operations
        // Dispatchers.Default is CPU-bound and has limited parallelism (core count)
        generationJob = lifecycleScope.launch(Dispatchers.IO) {
            try {
                val prompt = promptInput.text.toString().ifBlank { DEFAULT_PROMPT }
                
                // Log memory before generation
                logMemoryState("Before image generation")

                updateProgressUI(0, "Preparing...")

                
                val params = LLMEdgeManager.ImageGenerationParams(
                    prompt = prompt,
                    width = 128,
                    height = 128,
                    steps = 15,
                    cfgScale = 7.0f,
                    // Disable flash attention explicitly for small images to avoid
                    // inefficiencies on mobile GPUs; LLMEdgeManager will also auto-disable
                    // based on dimensions, but we enforce it here to ensure the correct path.
                    flashAttn = false,
                    forceSequentialLoad = false
                )

                val bitmap = LLMEdgeManager.generateImage(
                    context = applicationContext,
                    params = params
                ) { phase, current, total ->
                    val status = if (total > 0) "$phase ($current/$total)" else phase
                    updateProgressUI(0, status)
                }

                // Log memory after generation
                logMemoryState("After image generation")

                if (bitmap != null) {
                    withContext(Dispatchers.Main) {
                        previewImage.setImageBitmap(bitmap)
                    }
                }

                // Log performance information for easier debugging
                LLMEdgeManager.logPerformanceSnapshot()

                // Show metrics
                // Show metrics in the dedicated metrics label and progress status
                val metrics = LLMEdgeManager.getLastDiffusionMetrics()
                withContext(Dispatchers.Main) {
                    val metricsText = metrics?.let {
                        "Generated in ${String.format("%.1f", it.totalTimeSeconds)}s"
                    } ?: ""
                    metricsLabel.text = metricsText.ifBlank { "No metrics available" }
                    metricsLabel.visibility = View.VISIBLE
                    updateProgressUI(100, "Complete. $metricsText")
                }
            } catch (cancelled: CancellationException) {
                updateProgressUI(0, "Cancelled")
            } catch (oom: OutOfMemoryError) {
                android.util.Log.e(TAG, "Out of memory", oom)
                logMemoryState("OOM error")
                updateProgressUI(0, "Out of memory. Close other apps and try again.")
            } catch (t: Throwable) {
                android.util.Log.e(TAG, "Failed", t)
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
        LLMEdgeManager.cancelGeneration()
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

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        when (level) {
            android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW,
            android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL -> {
                android.util.Log.w(TAG, "System memory low (level=$level)")
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
    }

    private fun getAvailableMemoryMB(): Long {
        val activityManager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memInfo)
        return memInfo.availMem / BYTES_IN_MB
    }

    private fun logMemoryState(phase: String) {
        val runtime = Runtime.getRuntime()
        val heapUsed = (runtime.totalMemory() - runtime.freeMemory()) / BYTES_IN_MB
        val heapMax = runtime.maxMemory() / BYTES_IN_MB

        val activityManager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memoryInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memoryInfo)
        val systemAvail = memoryInfo.availMem / BYTES_IN_MB
        val systemTotal = memoryInfo.totalMem / BYTES_IN_MB

        android.util.Log.i(TAG, "=== Memory: $phase ===")
        android.util.Log.i(TAG, "  Heap: ${heapUsed}MB / ${heapMax}MB max")
        android.util.Log.i(TAG, "  System: ${systemAvail}MB / ${systemTotal}MB total")

        // Log Vulkan memory if available
        LLMEdgeManager.getVulkanDeviceInfo()?.let { vulkan ->
            android.util.Log.i(TAG, "  Vulkan: ${vulkan.freeMemoryMB}MB / ${vulkan.totalMemoryMB}MB")
        }
    }
}
