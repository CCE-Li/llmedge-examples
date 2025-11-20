package com.example.llmedgeexample

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.util.Log
import io.aatricks.llmedge.StableDiffusion
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Headless activity for E2E video generation testing.
 * Launches programmatically, generates video, logs results, and exits.
 *
 * Usage:
 * adb shell am start -n com.example.llmedgeexample/.HeadlessVideoTestActivity \
 *   --es prompt "a cat walking" \
 *   --ei frames 8 \
 *   --ei width 256 \
 *   --ei height 256 \
 *   --ei steps 10 \
 *   --ef cfg_scale 7.0 \
 *   --el seed 42
 */
class HeadlessVideoTestActivity : Activity() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    companion object {
        private const val TAG = "VideoE2E"
        private const val DEFAULT_PROMPT = "a cat walking in a garden, high quality"
        private const val DEFAULT_FRAMES = 8
        private const val DEFAULT_WIDTH = 256
        private const val DEFAULT_HEIGHT = 256
        private const val DEFAULT_STEPS = 10
        private const val DEFAULT_CFG_SCALE = 7.0f
        private const val DEFAULT_SEED = 42L

        // Model config - Mixed precision (quantized encoder + fp16 main model)
        // GGUF main models (samuelchristlie) lack SD version metadata → initialization fails
        // Must use safetensors for main model until GGUF metadata issue is resolved
        // RAM: ~8.75GB (5.9GB T5XXL Q3_K_S + 2.7GB model fp16 + 0.14GB VAE)
        // REQUIRES: Device with 12GB+ RAM (Galaxy S23 Ultra, Pixel 8 Pro, etc.)
        // See E2E-TEST-FINDINGS.md for full analysis
        private const val WAN_MODEL_ID = "Comfy-Org/Wan_2.1_ComfyUI_repackaged"
        private const val WAN_MODEL_FILENAME = "wan2.1_t2v_1.3B_fp16.safetensors"
        private const val WAN_VAE_ID = "Comfy-Org/Wan_2.1_ComfyUI_repackaged"
        private const val WAN_VAE_FILENAME = "wan_2.1_vae.safetensors"
        private const val WAN_T5XXL_ID = "city96/umt5-xxl-encoder-gguf"
        private const val WAN_T5XXL_FILENAME = "umt5-xxl-encoder-Q4_K_M.gguf"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Set a minimal view to prevent immediate finish
        setContentView(android.R.layout.simple_list_item_1)

        android.util.Log.e(TAG, "========================================")
        android.util.Log.e(TAG, "Headless Video Generation E2E Test")
        android.util.Log.e(TAG, "========================================")

        // Extract parameters from intent
        val prompt = intent.getStringExtra("prompt") ?: DEFAULT_PROMPT
        val frames = intent.getIntExtra("frames", DEFAULT_FRAMES)
        val width = intent.getIntExtra("width", DEFAULT_WIDTH)
        val height = intent.getIntExtra("height", DEFAULT_HEIGHT)
        val steps = intent.getIntExtra("steps", DEFAULT_STEPS)
        val cfgScale = intent.getFloatExtra("cfg_scale", DEFAULT_CFG_SCALE)
        val seed = intent.getLongExtra("seed", DEFAULT_SEED)

        android.util.Log.e(TAG, "Parameters:")
        android.util.Log.e(TAG, "  Prompt: $prompt")
        android.util.Log.e(TAG, "  Frames: $frames")
        android.util.Log.e(TAG, "  Resolution: ${width}x${height}")
        android.util.Log.e(TAG, "  Steps: $steps")
        android.util.Log.e(TAG, "  CFG Scale: $cfgScale")
        android.util.Log.e(TAG, "  Seed: $seed")
        android.util.Log.e(TAG, "")

        // Start generation (don't finish until complete)
        scope.launch {
            runTest(prompt, frames, width, height, steps, cfgScale, seed)
            // Finish activity after test completes
            withContext(Dispatchers.Main) {
                finish()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        android.util.Log.e(TAG, "Activity destroying, cancelling scope")
    }

    private suspend fun runTest(
        prompt: String,
        frames: Int,
        width: Int,
        height: Int,
        steps: Int,
        cfgScale: Float,
        seed: Long
    ) {
        val startTime = System.currentTimeMillis()

        try {
            android.util.Log.e(TAG, "Loading model using VideoModelManager...")

            // Use VideoModelManager to load the model
            val sd = VideoModelManager.getOrLoadModel(
                context = applicationContext,
                forceReload = false
            ) { phase, current, total ->
                android.util.Log.e(TAG, "[$current/$total] $phase")
            }

            val loadTime = System.currentTimeMillis() - startTime
            android.util.Log.e(TAG, "✓ Model loaded in ${loadTime}ms")
            android.util.Log.e(TAG, "")

            // 2. Verify video model
            if (sd.isVideoModel()) {
                android.util.Log.e(TAG, "✓ Video model detected")
            } else {
                Log.w(TAG, "⚠ Model not detected as video model")
            }
            android.util.Log.e(TAG, "")

            // 3. Configure generation
            val params = StableDiffusion.VideoGenerateParams(
                prompt = prompt,
                videoFrames = frames,
                width = width,
                height = height,
                steps = steps,
                cfgScale = cfgScale,
                seed = seed,
                scheduler = StableDiffusion.Scheduler.EULER_A
            )

            android.util.Log.e(TAG, "Starting video generation...")
            val genStartTime = System.currentTimeMillis()

            // 4. Generate with progress tracking
            var lastProgressTime = genStartTime
            val generatedFrames = sd.txt2vid(params) { step, totalSteps, currentFrame, totalFrames, timePerStep ->
                val now = System.currentTimeMillis()
                val elapsed = (now - lastProgressTime) / 1000.0
                lastProgressTime = now

                val percent = (currentFrame.toFloat() / totalFrames * 100).toInt()
                android.util.Log.e(
                    TAG,
                    "Progress: Step $step/$totalSteps, Frame $currentFrame/$totalFrames ($percent%) - ${
                        String.format(
                            "%.2f",
                            timePerStep
                        )
                    }s/step"
                )
            }

            val genTime = System.currentTimeMillis() - genStartTime
            android.util.Log.e(TAG, "")
            android.util.Log.e(TAG, "✓ Video generation completed in ${genTime}ms")
            android.util.Log.e(TAG, "")

            // 5. Extract metrics
            val metrics = sd.getLastGenerationMetrics()

            android.util.Log.e(TAG, "========================================")
            android.util.Log.e(TAG, "Results Summary")
            android.util.Log.e(TAG, "========================================")
            android.util.Log.e(TAG, "Status: SUCCESS")
            android.util.Log.e(TAG, "Generated frames: ${generatedFrames.size}")
            android.util.Log.e(TAG, "Resolution: ${width}x${height}")
            android.util.Log.e(TAG, "Total time: ${(System.currentTimeMillis() - startTime) / 1000.0}s")

            if (metrics != null) {
                android.util.Log.e(TAG, "Generation time: ${String.format("%.2f", metrics.totalTimeSeconds)}s")
                android.util.Log.e(TAG, "Frames/sec: ${String.format("%.2f", metrics.framesPerSecond)}")
                android.util.Log.e(TAG, "Memory used: ${metrics.peakMemoryUsageMb}MB")
            }

            android.util.Log.e(TAG, "========================================")
            android.util.Log.e(TAG, "")

            // Validate frames
            if (generatedFrames.size != frames) {
                Log.e(TAG, "❌ Frame count mismatch: expected $frames, got ${generatedFrames.size}")
            } else {
                android.util.Log.e(TAG, "✓ Frame count validated")
            }

            generatedFrames.forEachIndexed { index, bitmap ->
                if (bitmap.width != width || bitmap.height != height) {
                    Log.e(TAG, "❌ Frame $index resolution mismatch: ${bitmap.width}x${bitmap.height}")
                } else if (index == 0) {
                    android.util.Log.e(TAG, "✓ Frame resolution validated")
                }
            }

        } catch (e: Exception) {
            Log.e(TAG, "========================================")
            Log.e(TAG, "Test FAILED")
            Log.e(TAG, "========================================")
            Log.e(TAG, "Error: ${e.message}", e)
            Log.e(TAG, "========================================")
        } finally {
            // Model lifecycle is managed by VideoModelManager
            // Don't close it here as it may be reused

            // Give logs time to flush
            withContext(Dispatchers.Main) {
                finish()
            }
        }
    }
}
