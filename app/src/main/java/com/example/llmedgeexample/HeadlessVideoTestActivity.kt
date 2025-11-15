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
        
        // Model config - Using official Comfy-Org repackaged models
        // NOTE: fp16 models require ~14GB RAM (10.8GB T5XXL + 2.7GB model + 0.14GB VAE)
        // Devices with <8GB RAM will OOM. For production, use quantized GGUF models.
        // See: https://github.com/leejet/stable-diffusion.cpp/blob/master/docs/wan.md
        private const val WAN_MODEL_ID = "Comfy-Org/Wan_2.1_ComfyUI_repackaged"
        private const val WAN_MODEL_FILENAME = "wan2.1_t2v_1.3B_fp16.safetensors"
        private const val WAN_VAE_FILENAME = "wan_2.1_vae.safetensors"
        private const val WAN_T5XXL_ID = "Comfy-Org/Wan_2.1_ComfyUI_repackaged"
        private const val WAN_T5XXL_FILENAME = "umt5_xxl_fp16.safetensors"
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
        var sd: StableDiffusion? = null
        
        try {
            // 1. Load model
            android.util.Log.e(TAG, "Loading model: $WAN_MODEL_ID")
            android.util.Log.e(TAG, "Filename: $WAN_MODEL_FILENAME")
            
            // Load VAE first
            android.util.Log.e(TAG, "Loading VAE: $WAN_VAE_FILENAME")
            val vaeFile = io.aatricks.llmedge.huggingface.HuggingFaceHub.ensureRepoFileOnDisk(
                context = applicationContext,
                modelId = WAN_MODEL_ID,
                revision = "main",
                filename = WAN_VAE_FILENAME,
                allowedExtensions = listOf(".safetensors"),
                token = null,
                forceDownload = false,
                preferSystemDownloader = true,
                onProgress = null
            )
            
            // Load T5XXL encoder
            android.util.Log.e(TAG, "Loading T5XXL encoder: $WAN_T5XXL_FILENAME")
            val t5xxlFile = io.aatricks.llmedge.huggingface.HuggingFaceHub.ensureRepoFileOnDisk(
                context = applicationContext,
                modelId = WAN_T5XXL_ID,
                revision = "main",
                filename = WAN_T5XXL_FILENAME,
                allowedExtensions = listOf(".gguf", ".safetensors"),
                token = null,
                forceDownload = false,
                preferSystemDownloader = true,
                onProgress = null
            )
            
            sd = StableDiffusion.load(
                context = applicationContext,
                modelId = WAN_MODEL_ID,
                filename = WAN_MODEL_FILENAME,
                vaePath = vaeFile.file.absolutePath,
                t5xxlPath = t5xxlFile.file.absolutePath,
                nThreads = Runtime.getRuntime().availableProcessors(),
                offloadToCpu = true,
                keepClipOnCpu = true,
                keepVaeOnCpu = true,
            )
            
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
                android.util.Log.e(TAG, "Progress: Step $step/$totalSteps, Frame $currentFrame/$totalFrames ($percent%) - ${String.format("%.2f", timePerStep)}s/step")
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
            sd?.close()
            
            // Give logs time to flush
            withContext(Dispatchers.Main) {
                finish()
            }
        }
    }
}
