package com.example.llmedgeexample

import android.app.ActivityManager
import android.content.Context
import android.util.Log
import io.aatricks.llmedge.StableDiffusion
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.lang.ref.WeakReference

/**
 * Global singleton manager for video generation models.
 *
 * Prevents multiple model instances from being loaded simultaneously,
 * which can cause out-of-memory crashes on mobile devices.
 *
 * Usage:
 * ```
 * val model = VideoModelManager.getInstance(context).getOrLoadModel()
 * val frames = model.txt2vid(params)
 * ```
 */
object VideoModelManager {
    private const val TAG = "VideoModelManager"
    private const val BYTES_IN_MB = 1024L * 1024L
    private const val MIN_AVAILABLE_MEMORY_MB =
        2000L // Reduced from 3000L to allow running on devices with tighter memory constraints

    // Model configuration
    private const val WAN_MODEL_ID = "Comfy-Org/Wan_2.1_ComfyUI_repackaged"
    private const val WAN_MODEL_FILENAME = "wan2.1_t2v_1.3B_fp16.safetensors"
    private const val WAN_VAE_ID = "Comfy-Org/Wan_2.1_ComfyUI_repackaged"
    private const val WAN_VAE_FILENAME = "wan_2.1_vae.safetensors"
    private const val WAN_T5XXL_ID = "city96/umt5-xxl-encoder-gguf"
    private const val WAN_T5XXL_FILENAME = "umt5-xxl-encoder-Q3_K_S.gguf"

    // Cached model instance
    @Volatile
    private var cachedModel: StableDiffusion? = null

    // Mutex to prevent concurrent loads
    private val loadMutex = Mutex()

    // Track loading state
    @Volatile
    private var isLoading = false

    // Context reference for lifecycle awareness
    private var contextRef: WeakReference<Context>? = null

    /**
     * Gets or loads the video model.
     * This method is thread-safe and ensures only one model instance exists.
     *
     * @param context Application context
     * @param forceReload If true, releases existing model and reloads
     * @param onProgress Optional callback for load progress
     * @return Loaded StableDiffusion instance
     * @throws OutOfMemoryError if insufficient memory is available
     * @throws IllegalStateException if model loading fails
     */
    suspend fun getOrLoadModel(
        context: Context,
        forceReload: Boolean = false,
        onProgress: ((String, Int, Int) -> Unit)? = null
    ): StableDiffusion = loadMutex.withLock {
        // Update context reference
        contextRef = WeakReference(context.applicationContext)

        // Return cached model if available and not forcing reload
        val existing = cachedModel
        if (existing != null && !forceReload) {
            Log.d(TAG, "Returning cached model instance")
            return existing
        }

        // Release existing model if forcing reload
        if (forceReload && existing != null) {
            Log.d(TAG, "Force reload requested, releasing existing model")
            releaseModel()
        }

        // Check if already loading (shouldn't happen with mutex, but defensive check)
        if (isLoading) {
            Log.w(TAG, "Model is already being loaded by another caller")
            throw IllegalStateException("Model is currently being loaded. Please wait.")
        }

        try {
            isLoading = true
            Log.i(TAG, "Starting model load...")

            // Prepare memory
            prepareMemoryForLoading(context)

            // Load model
            val model = loadModelInternal(context, onProgress)

            // Cache the model
            cachedModel = model

            Log.i(TAG, "Model loaded and cached successfully")
            logMemoryState(context, "After model load")

            return model

        } catch (e: Exception) {
            Log.e(TAG, "Failed to load model", e)
            cachedModel = null
            throw e
        } finally {
            isLoading = false
        }
    }

    /**
     * Generates video using sequential (progressive) loading to save memory.
     * Flow: Load T5XXL -> Encode -> Free T5XXL -> Load Diffusion -> Generate -> Free Diffusion.
     */
    suspend fun generateVideoSequentially(
        context: Context,
        params: StableDiffusion.VideoGenerateParams,
        onProgress: ((String, Int, Int) -> Unit)? = null
    ): List<android.graphics.Bitmap> = loadMutex.withLock {
        // Update context reference
        contextRef = WeakReference(context.applicationContext)

        // Ensure we're not interfering with cached model
        if (cachedModel != null) {
            Log.i(TAG, "Releasing cached model for sequential generation")
            releaseModel()
        }

        try {
            isLoading = true
            Log.i(TAG, "Starting sequential video generation...")

            // 1. Ensure all files are available
            Log.d(TAG, "Checking model files...")
            onProgress?.invoke("Checking files", 0, 5)

            val modelFile = io.aatricks.llmedge.huggingface.HuggingFaceHub.ensureRepoFileOnDisk(
                context = context,
                modelId = WAN_MODEL_ID,
                revision = "main",
                filename = WAN_MODEL_FILENAME,
                allowedExtensions = listOf(".safetensors", ".gguf"),
                token = null,
                forceDownload = false,
                preferSystemDownloader = true,
                onProgress = null
            )

            val vaeFile = io.aatricks.llmedge.huggingface.HuggingFaceHub.ensureRepoFileOnDisk(
                context = context,
                modelId = WAN_VAE_ID,
                revision = "main",
                filename = WAN_VAE_FILENAME,
                allowedExtensions = listOf(".safetensors"),
                token = null,
                forceDownload = false,
                preferSystemDownloader = true,
                onProgress = null
            )

            val t5xxlFile = io.aatricks.llmedge.huggingface.HuggingFaceHub.ensureRepoFileOnDisk(
                context = context,
                modelId = WAN_T5XXL_ID,
                revision = "main",
                filename = WAN_T5XXL_FILENAME,
                allowedExtensions = listOf(".gguf", ".safetensors"),
                token = null,
                forceDownload = false,
                preferSystemDownloader = true,
                onProgress = null
            )

            // 2. Load T5XXL and Encode
            Log.i(TAG, "Step 1/2: Loading T5XXL encoder...")
            onProgress?.invoke("Loading encoder", 1, 5)
            prepareMemoryForLoading(context)

            // We pass T5XXL as the "modelPath" so stable-diffusion.cpp loads it.
            // We don't pass the actual diffusion model here to save memory.
            var t5Model: StableDiffusion? = null
            var cond: StableDiffusion.PrecomputedCondition? = null
            var uncond: StableDiffusion.PrecomputedCondition? = null

            try {
                t5Model = StableDiffusion.load(
                    context = context,
                    modelPath = t5xxlFile.file.absolutePath, // Trick: load T5 as main model
                    vaePath = null,
                    t5xxlPath = null, // Already passed as modelPath
                    nThreads = Runtime.getRuntime().availableProcessors(),
                    offloadToCpu = true, // Keep T5 on CPU to save VRAM
                    keepClipOnCpu = true,
                    keepVaeOnCpu = true
                )

                Log.i(TAG, "Encoding prompt...")
                onProgress?.invoke("Encoding prompt", 2, 5)

                cond = t5Model.precomputeCondition(
                    prompt = params.prompt,
                    negative = params.negative,
                    width = params.width,
                    height = params.height
                )

                if (params.negative.isNotEmpty()) {
                    uncond = t5Model.precomputeCondition(
                        prompt = params.negative,
                        negative = "",
                        width = params.width,
                        height = params.height
                    )
                } else {
                    uncond = t5Model.precomputeCondition(
                        prompt = "",
                        negative = "",
                        width = params.width,
                        height = params.height
                    )
                }

            } finally {
                t5Model?.close()
                t5Model = null
            }

            // 3. Free T5XXL and Prepare for Diffusion
            Log.i(TAG, "Step 1 complete. Freeing memory...")
            System.gc()
            Thread.sleep(200)
            prepareMemoryForLoading(context)

            if (cond == null || uncond == null) {
                throw IllegalStateException("Failed to precompute conditions")
            }

            // 4. Load Diffusion Model
            Log.i(TAG, "Step 2/2: Loading Diffusion model...")
            onProgress?.invoke("Loading model", 3, 5)

            var diffusionModel: StableDiffusion? = null
            try {
                diffusionModel = StableDiffusion.load(
                    context = context,
                    modelPath = modelFile.file.absolutePath,
                    vaePath = vaeFile.file.absolutePath,
                    t5xxlPath = null, // Do NOT load T5XXL
                    nThreads = Runtime.getRuntime().availableProcessors(),
                    offloadToCpu = false, // Enable GPU for speed
                    keepClipOnCpu = false,
                    keepVaeOnCpu = false
                )

                Log.i(TAG, "Generating video...")
                onProgress?.invoke("Generating", 4, 5)

                // We need to wrap the onProgress to map the steps correctly
                val progressWrapper =
                    StableDiffusion.VideoProgressCallback { step, totalSteps, currentFrame, totalFrames, timePerStep ->
                        onProgress?.invoke("Generating frame $currentFrame/$totalFrames", step, totalSteps)
                    }

                return diffusionModel.txt2VidWithPrecomputedCondition(
                    params = params,
                    cond = cond,
                    uncond = uncond,
                    onProgress = progressWrapper
                )

            } finally {
                diffusionModel?.close()
                diffusionModel = null
                // Final cleanup
                System.gc()
            }

        } catch (e: Exception) {
            Log.e(TAG, "Sequential generation failed", e)
            throw e
        } finally {
            isLoading = false
        }
    }

    /**
     * Releases the cached model and frees memory.
     * Call this when the app is backgrounded or when memory is low.
     */
    fun releaseModel() {
        loadMutex.tryLock().let { acquired ->
            if (!acquired) {
                Log.w(TAG, "Cannot release model - currently in use")
                return
            }

            try {
                cachedModel?.let { model ->
                    Log.i(TAG, "Releasing cached model")
                    try {
                        model.close()
                    } catch (e: Exception) {
                        Log.e(TAG, "Error closing model", e)
                    }
                    cachedModel = null

                    // Force GC to reclaim memory
                    System.gc()
                    Thread.sleep(100)
                    System.gc()

                    contextRef?.get()?.let { context ->
                        logMemoryState(context, "After model release")
                    }
                }
            } finally {
                loadMutex.unlock()
            }
        }
    }

    /**
     * Checks if a model is currently loaded and cached.
     */
    fun isModelLoaded(): Boolean = cachedModel != null

    /**
     * Checks if a model is currently being loaded.
     */
    fun isCurrentlyLoading(): Boolean = isLoading

    /**
     * Gets the cached model without loading. Returns null if not loaded.
     */
    fun getCachedModelOrNull(): StableDiffusion? = cachedModel

    /**
     * Internal method to load the model from disk/download.
     */
    private suspend fun loadModelInternal(
        context: Context,
        onProgress: ((String, Int, Int) -> Unit)?
    ): StableDiffusion {
        Log.d(TAG, "Loading main model file...")
        onProgress?.invoke("Loading main model", 1, 4)

        val modelFile = io.aatricks.llmedge.huggingface.HuggingFaceHub.ensureRepoFileOnDisk(
            context = context,
            modelId = WAN_MODEL_ID,
            revision = "main",
            filename = WAN_MODEL_FILENAME,
            allowedExtensions = listOf(".safetensors", ".gguf"),
            token = null,
            forceDownload = false,
            preferSystemDownloader = true,
            onProgress = null
        )
        Log.d(TAG, "Main model file: ${modelFile.file.absolutePath} (${modelFile.file.length() / BYTES_IN_MB}MB)")

        Log.d(TAG, "Loading VAE file...")
        onProgress?.invoke("Loading VAE", 2, 4)

        val vaeFile = io.aatricks.llmedge.huggingface.HuggingFaceHub.ensureRepoFileOnDisk(
            context = context,
            modelId = WAN_VAE_ID,
            revision = "main",
            filename = WAN_VAE_FILENAME,
            allowedExtensions = listOf(".safetensors"),
            token = null,
            forceDownload = false,
            preferSystemDownloader = true,
            onProgress = null
        )
        Log.d(TAG, "VAE file: ${vaeFile.file.absolutePath} (${vaeFile.file.length() / BYTES_IN_MB}MB)")

        Log.d(TAG, "Loading T5XXL encoder file...")
        onProgress?.invoke("Loading text encoder", 3, 4)

        val t5xxlFile = io.aatricks.llmedge.huggingface.HuggingFaceHub.ensureRepoFileOnDisk(
            context = context,
            modelId = WAN_T5XXL_ID,
            revision = "main",
            filename = WAN_T5XXL_FILENAME,
            allowedExtensions = listOf(".gguf", ".safetensors"),
            token = null,
            forceDownload = false,
            preferSystemDownloader = true,
            onProgress = null
        )
        Log.d(TAG, "T5XXL file: ${t5xxlFile.file.absolutePath} (${t5xxlFile.file.length() / BYTES_IN_MB}MB)")

        // One more memory check and GC before the heavy native loading
        Log.d(TAG, "Final memory preparation before native load...")
        System.gc()
        Thread.sleep(100)
        logMemoryState(context, "Before native load")

        onProgress?.invoke("Initializing model in memory", 4, 4)
        Log.i(TAG, "Calling StableDiffusion.load() - this may take 5-10 seconds...")

        return StableDiffusion.load(
            context = context,
            modelPath = modelFile.file.absolutePath,
            vaePath = vaeFile.file.absolutePath,
            t5xxlPath = t5xxlFile.file.absolutePath,
            nThreads = Runtime.getRuntime().availableProcessors(),
            offloadToCpu = true,
            keepClipOnCpu = true,
            keepVaeOnCpu = true,
        )
    }

    /**
     * Prepares memory by forcing GC and checking available memory.
     */
    private fun prepareMemoryForLoading(context: Context) {
        Log.d(TAG, "Preparing memory for model loading with ULTRA-AGGRESSIVE optimization...")

        // Ultra-aggressive garbage collection - multiple passes
        Log.d(TAG, "Running aggressive GC cycles...")
        for (i in 1..5) {
            System.gc()
            System.runFinalization()
            Thread.sleep(200)
        }

        // Clear runtime caches
        Runtime.getRuntime().gc()
        Thread.sleep(300)
        System.gc()
        System.runFinalization()

        // Request memory trim at all levels
        Log.d(TAG, "Trimming memory caches...")
        val app = context.applicationContext as? android.app.Application
        app?.onTrimMemory(android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL)

        // Final GC pass
        System.gc()
        Thread.sleep(200)

        // Log current memory state
        logMemoryState(context, "Before model load")

        // Check if we have enough available memory
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memoryInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memoryInfo)
        val availMemMB = memoryInfo.availMem / BYTES_IN_MB
        val totalMemMB = memoryInfo.totalMem / BYTES_IN_MB

        Log.i(TAG, "Memory check: ${availMemMB}MB available of ${totalMemMB}MB total")

        if (availMemMB < MIN_AVAILABLE_MEMORY_MB) {
            val message = "Insufficient memory: ${availMemMB}MB available, ${MIN_AVAILABLE_MEMORY_MB}MB required. " +
                    "Please close other apps and try again."
            Log.e(TAG, message)
            throw OutOfMemoryError(message)
        }

        if (memoryInfo.lowMemory) {
            Log.w(TAG, "System reports low memory condition. Proceeding with caution.")
        }

        // Warn if close to threshold
        if (availMemMB < MIN_AVAILABLE_MEMORY_MB + 1000) {
            Log.w(TAG, "Memory is tight (${availMemMB}MB). Close background apps for better stability.")
        }
    }

    /**
     * Logs detailed memory state for debugging.
     */
    private fun logMemoryState(context: Context, phase: String) {
        val runtime = Runtime.getRuntime()
        val heapUsed = (runtime.totalMemory() - runtime.freeMemory()) / BYTES_IN_MB
        val heapMax = runtime.maxMemory() / BYTES_IN_MB
        val heapFree = heapMax - heapUsed

        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memoryInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memoryInfo)
        val systemAvail = memoryInfo.availMem / BYTES_IN_MB
        val systemTotal = memoryInfo.totalMem / BYTES_IN_MB

        Log.i(TAG, "=== Memory State: $phase ===")
        Log.i(TAG, "  Heap: ${heapUsed}MB used / ${heapMax}MB max (${heapFree}MB free)")
        Log.i(TAG, "  System: ${systemAvail}MB available / ${systemTotal}MB total")
        Log.i(TAG, "  Low memory: ${memoryInfo.lowMemory}")
    }

    /**
     * Call this from Application.onTrimMemory() to handle memory pressure.
     */
    fun handleMemoryPressure(level: Int) {
        when (level) {
            android.content.ComponentCallbacks2.TRIM_MEMORY_BACKGROUND,
            android.content.ComponentCallbacks2.TRIM_MEMORY_MODERATE -> {
                Log.i(TAG, "Memory pressure (level=$level) - considering model release")
                // Don't release immediately, but prepare to release if needed
            }

            android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL,
            android.content.ComponentCallbacks2.TRIM_MEMORY_COMPLETE -> {
                Log.w(TAG, "Critical memory pressure (level=$level) - releasing model")
                releaseModel()
            }
        }
    }
}
