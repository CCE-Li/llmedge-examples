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
 * Activity for video generation using Wan 2.1 model.
 * 
 * Uses sequential loading on low-memory devices (<8GB RAM):
 * 1. Load T5 encoder -> Encode prompt -> Unload T5
 * 2. Load diffusion model + VAE -> Generate frames -> Unload
 * 
 * This allows video generation on devices with limited memory.
 */
class VideoGenerationActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "VideoGenerationActivity"
        private const val DEFAULT_PROMPT = "A dog running in the park"
        private const val DEFAULT_WIDTH = 512
        private const val DEFAULT_HEIGHT = 512
        private const val DEFAULT_STEPS = 20
        private const val DEFAULT_CFG = 7.0f
        private const val DEFAULT_SEED = -1L
        private const val DEFAULT_FRAMES = 8
        private const val DEFAULT_FPS = 8
        private const val BYTES_IN_MB = 1024L * 1024L
    }

    private val promptInput: EditText by lazy(LazyThreadSafetyMode.NONE) { findViewById(R.id.videoPromptInput) }
    private val widthInput: EditText by lazy(LazyThreadSafetyMode.NONE) { findViewById(R.id.videoWidthInput) }
    private val heightInput: EditText by lazy(LazyThreadSafetyMode.NONE) { findViewById(R.id.videoHeightInput) }
    private val framesInput: EditText by lazy(LazyThreadSafetyMode.NONE) { findViewById(R.id.videoFramesInput) }
    private val fpsInput: EditText by lazy(LazyThreadSafetyMode.NONE) { findViewById(R.id.videoFpsInput) }
    private val stepsInput: EditText by lazy(LazyThreadSafetyMode.NONE) { findViewById(R.id.videoStepsInput) }
    private val cfgInput: EditText by lazy(LazyThreadSafetyMode.NONE) { findViewById(R.id.videoCfgInput) }
    private val seedInput: EditText by lazy(LazyThreadSafetyMode.NONE) { findViewById(R.id.videoSeedInput) }
    private val flowShiftInput: EditText by lazy(LazyThreadSafetyMode.NONE) { findViewById(R.id.videoFlowShiftInput) }
    private val generateButton: Button by lazy(LazyThreadSafetyMode.NONE) { findViewById(R.id.btnGenerateVideo) }
    private val cancelButton: Button by lazy(LazyThreadSafetyMode.NONE) { findViewById(R.id.btnCancelVideo) }
    private val progressBar: ProgressBar by lazy(LazyThreadSafetyMode.NONE) { findViewById(R.id.videoProgressBar) }
    private val progressLabel: TextView by lazy(LazyThreadSafetyMode.NONE) { findViewById(R.id.videoProgressLabel) }
    private val previewImage: ImageView by lazy(LazyThreadSafetyMode.NONE) { findViewById(R.id.videoPreview) }
    private val metricsLabel: TextView by lazy(LazyThreadSafetyMode.NONE) { findViewById(R.id.videoMetricsLabel) }

    private var generationJob: Job? = null
    private var animationJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_video_generation)

        // Prefer performance mode during interactive examples to favor throughput.
        // For memory-constrained devices (e.g. 8GB), disable to avoid OOMs during sequential
        // model loads where CPU offload is required to reduce peak memory usage.
        val isLowMem = isLowMemoryDevice()
        io.aatricks.llmedge.LLMEdgeManager.preferPerformanceMode = !isLowMem

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
            Toast.makeText(this, R.string.video_status_generation_running, Toast.LENGTH_SHORT).show()
            return
        }
        
        // Stop any previous animation
        animationJob?.cancel()

        val width = parseDimensionField(widthInput, DEFAULT_WIDTH, "Width") ?: return
        val height = parseDimensionField(heightInput, DEFAULT_HEIGHT, "Height") ?: return
        val framesCount = parseFramesField() ?: return
        val fps = parseFpsField() ?: return
        val steps = parseStepsField() ?: return
        val cfg = parseCfgField() ?: return
        val seed = parseSeedField() ?: return
        val flowShift = parseFlowShiftField() ?: return

        // Check if we have enough memory
        val isLowMem = isLowMemoryDevice()
        val availMemMB = getAvailableMemoryMB()
        
        android.util.Log.i(TAG, "Starting generation: isLowMem=$isLowMem, availMem=${availMemMB}MB")
        
        if (availMemMB < 1500) {
            Toast.makeText(
                this,
                "Low memory (${availMemMB}MB). Close other apps for better results.",
                Toast.LENGTH_LONG
            ).show()
        }

        updateProgressUI(0, getString(R.string.video_status_loading_model))
        progressBar.visibility = View.VISIBLE
        progressBar.isIndeterminate = true
        generateButton.isEnabled = false

        // Use Dispatchers.IO for native JNI operations - it has more threads for blocking operations
        // Dispatchers.Default is CPU-bound and has limited parallelism (core count)
        generationJob = lifecycleScope.launch(Dispatchers.IO) {
            try {
                val prompt = promptInput.text.toString().ifBlank { DEFAULT_PROMPT }
                
                // Log memory before generation
                logMemoryState("Before video generation")

                // Use sequential loading on low-memory devices (auto-detected)
                // Width must be between 256-960 for Wan 2.1
                val params = LLMEdgeManager.VideoGenerationParams(
                    prompt = prompt,
                    width = width,
                    height = height,
                    videoFrames = framesCount,
                    steps = steps,
                    cfgScale = cfg,
                    seed = seed,
                    flowShift = flowShift,
                    flashAttn = true,
                    forceSequentialLoad = true  // Always use sequential for safety
                    ,
                    // Demonstrate easy cache usage (improves perf for repeated text conditions)
                    easyCache = io.aatricks.llmedge.StableDiffusion.EasyCacheParams(enabled = true, reuseThreshold = 0.2f, startPercent = 0.15f, endPercent = 0.95f),
                    // Example LoRA: provide a local folder on the device containing LoRA weights
                    loraModelDir = getExternalFilesDir("loras")?.absolutePath,
                    loraApplyMode = io.aatricks.llmedge.StableDiffusion.LoraApplyMode.AUTO
                )

                updateProgressUI(0, "Preparing model...")

                val frames = LLMEdgeManager.generateVideo(
                    context = applicationContext,
                    params = params
                ) { phase, current, total ->
                    val status = if (total > 0) "$phase ($current/$total)" else phase
                    updateProgressUI(0, status)
                }

                // Log memory after generation
                logMemoryState("After video generation")

                if (frames.isNotEmpty()) {
                    withContext(Dispatchers.Main) {
                        // Show metrics if available
                        val metrics = LLMEdgeManager.getLastDiffusionMetrics()
                        metrics?.let {
                            metricsLabel.text = "Generated ${frames.size} frames in ${String.format("%.1f", it.totalTimeSeconds)}s"
                            metricsLabel.visibility = View.VISIBLE
                        }
                        
                        // Start animation
                        val frameDuration = 1000L / fps
                        animationJob = lifecycleScope.launch {
                            while (true) {
                                frames.forEach { frame ->
                                    previewImage.setImageBitmap(frame)
                                    kotlinx.coroutines.delay(frameDuration)
                                }
                            }
                        }
                    }
                }
                // Log a performance snapshot for debugging purposes
                LLMEdgeManager.logPerformanceSnapshot()
                withContext(Dispatchers.Main) {
                    updateProgressUI(100, getString(R.string.video_status_complete, frames.size))
                }
            } catch (cancelled: CancellationException) {
                updateProgressUI(0, getString(R.string.video_status_cancelled))
            } catch (oom: OutOfMemoryError) {
                android.util.Log.e(TAG, "Out of memory during generation", oom)
                logMemoryState("OOM error")
                updateProgressUI(
                    0,
                    getString(R.string.video_status_failed, "Out of memory. Close other apps and try again.")
                )
            } catch (t: Throwable) {
                android.util.Log.e(TAG, "Failed during generation", t)
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
        animationJob?.cancel()
        LLMEdgeManager.cancelGeneration()
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

    private fun parseDimensionField(field: EditText, defaultValue: Int, label: String): Int? {
        val value = field.text.toString().ifBlank { defaultValue.toString() }.toIntOrNull()
        return if (value == null || value !in 256..960 || value % 64 != 0) {
            field.error = "$label must be a multiple of 64 between 256 and 960"
            field.requestFocus()
            null
        } else {
            field.error = null
            value
        }
    }

    private fun parseFramesField(): Int? {
        val value = framesInput.text.toString().ifBlank { DEFAULT_FRAMES.toString() }.toIntOrNull()
        return if (value == null || value !in 4..64) {
            framesInput.error = "Frames must be between 4 and 64"
            framesInput.requestFocus()
            null
        } else {
            framesInput.error = null
            value
        }
    }

    private fun parseFpsField(): Int? {
        val value = fpsInput.text.toString().ifBlank { DEFAULT_FPS.toString() }.toIntOrNull()
        return if (value == null || value !in 1..30) {
            fpsInput.error = "FPS must be between 1 and 30"
            fpsInput.requestFocus()
            null
        } else {
            fpsInput.error = null
            value
        }
    }

    private fun parseStepsField(): Int? {
        val value = stepsInput.text.toString().ifBlank { DEFAULT_STEPS.toString() }.toIntOrNull()
        return if (value == null || value !in 10..50) {
            stepsInput.error = "Steps must be between 10 and 50"
            stepsInput.requestFocus()
            null
        } else {
            stepsInput.error = null
            value
        }
    }

    private fun parseCfgField(): Float? {
        val value = cfgInput.text.toString().ifBlank { DEFAULT_CFG.toString() }.toFloatOrNull()
        return if (value == null || value !in 1.0f..15.0f) {
            cfgInput.error = "CFG must be between 1.0 and 15.0"
            cfgInput.requestFocus()
            null
        } else {
            cfgInput.error = null
            value
        }
    }

    private fun parseSeedField(): Long? {
        val value = seedInput.text.toString().ifBlank { DEFAULT_SEED.toString() }.toLongOrNull()
        return if (value == null || value < -1L) {
            seedInput.error = "Seed must be -1 or non-negative"
            seedInput.requestFocus()
            null
        } else {
            seedInput.error = null
            value
        }
    }

    private fun parseFlowShiftField(): Float? {
        val raw = flowShiftInput.text.toString().trim()
        if (raw.isBlank()) {
            flowShiftInput.error = null
            return Float.POSITIVE_INFINITY
        }
        val value = raw.toFloatOrNull()
        return if (value == null || value <= 0f) {
            flowShiftInput.error = "Flow shift must be greater than 0"
            flowShiftInput.requestFocus()
            null
        } else {
            flowShiftInput.error = null
            value
        }
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        when (level) {
            android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW,
            android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL -> {
                android.util.Log.w(TAG, "System memory low (level=$level), cancelling if active")
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

    private fun isLowMemoryDevice(): Boolean {
        val activityManager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memInfo)
        val totalRamGB = memInfo.totalMem / (1024L * 1024L * 1024L)
        return totalRamGB < 8
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
