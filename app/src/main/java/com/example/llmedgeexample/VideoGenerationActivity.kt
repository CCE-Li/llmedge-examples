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

    private var sd: StableDiffusion? = null
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

        generationJob = lifecycleScope.launch(Dispatchers.Default) {
            try {
                updateProgressUI(0, getString(R.string.video_status_loading_model))
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
            } catch (t: Throwable) {
                updateProgressUI(0, getString(R.string.video_status_failed, t.localizedMessage ?: "error"))
            } finally {
                withContext(Dispatchers.Main) {
                    progressBar.visibility = View.GONE
                    generationJob = null
                }
            }
        }
    }

    private suspend fun ensureModelLoaded(): StableDiffusion {
        val cached = sd
        if (cached != null) return cached

        val loaded = try {
            // Download all three model files explicitly (same as HeadlessVideoTestActivity)
            val modelFile = io.aatricks.llmedge.huggingface.HuggingFaceHub.ensureRepoFileOnDisk(
                context = applicationContext,
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
                context = applicationContext,
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
            
            // Load all three models together using file paths
            StableDiffusion.load(
                context = applicationContext,
                modelPath = modelFile.file.absolutePath,
                vaePath = vaeFile.file.absolutePath,
                t5xxlPath = t5xxlFile.file.absolutePath,
                nThreads = Runtime.getRuntime().availableProcessors(),
                offloadToCpu = true,
                keepClipOnCpu = true,
                keepVaeOnCpu = true,
            )
        } catch (t: Throwable) {
            withContext(Dispatchers.Main) {
                Toast.makeText(
                    this@VideoGenerationActivity,
                    getString(R.string.video_status_failed, t.localizedMessage ?: "unknown"),
                    Toast.LENGTH_LONG,
                ).show()
            }
            throw t
        }
        sd = loaded
        return loaded
    }

    private fun cancelGeneration() {
        generationJob?.cancel()
        sd?.cancelGeneration()
        updateProgressUI(0, getString(R.string.video_status_cancelled))
    }

    private fun updateProgressUI(percent: Int, status: String) {
        progressBar.post {
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
        sd?.close()
        sd = null
    }

    companion object {
        // Wan 2.1 T2V models - Using official Comfy-Org repackaged models
        // See: https://github.com/leejet/stable-diffusion.cpp/blob/master/docs/wan.md
        private const val WAN_MODEL_ID = "Comfy-Org/Wan_2.1_ComfyUI_repackaged"
        private const val WAN_MODEL_FILENAME = "wan2.1_t2v_1.3B_fp16.safetensors"
        private const val WAN_VAE_ID = "Comfy-Org/Wan_2.1_ComfyUI_repackaged"
        private const val WAN_VAE_FILENAME = "wan_2.1_vae.safetensors"
        private const val WAN_T5XXL_ID = "city96/umt5-xxl-encoder-gguf"
        private const val WAN_T5XXL_FILENAME = "umt5-xxl-encoder-Q3_K_S.gguf"
        private const val DEFAULT_PROMPT = "A dog running in the park"
    }
}
