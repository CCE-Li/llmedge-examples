package com.example.llmedgeexample

import android.graphics.Bitmap
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.ProgressBar
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import io.aatricks.llmedge.StableDiffusion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class StableDiffusionActivity : AppCompatActivity() {
    private lateinit var imageView: ImageView
    private lateinit var promptInput: EditText
    private lateinit var generateBtn: Button
    private lateinit var progress: ProgressBar
    private var sd: StableDiffusion? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_stable_diffusion)

        imageView = findViewById(R.id.imageView)
        promptInput = findViewById(R.id.promptInput)
        generateBtn = findViewById(R.id.generateBtn)
        progress = findViewById(R.id.progress)

        // Preload model lazily on first click
        generateBtn.setOnClickListener {
            lifecycleScope.launch {
                // disable button to avoid concurrent presses which can lead to concurrent native calls
                generateBtn.isEnabled = false
                progress.visibility = android.view.View.VISIBLE
                var localSd: StableDiffusion? = null
                try {
                    // Ensure model GGUF is downloaded by StableDiffusion.load
                    // and separately ensure the VAE safetensors is present.
                    // Use the system downloader (DownloadManager) for large files to avoid allocating buffers
                    // on the app heap which can trigger OOM on memory-constrained devices.
                    val vaeDownload = try {
                        io.aatricks.llmedge.huggingface.HuggingFaceHub.ensureRepoFileOnDisk(
                            context = this@StableDiffusionActivity,
                            modelId = "Meina/MeinaMix",
                            filename = "MeinaPastel - baked VAE.safetensors",
                            token = null,
                            forceDownload = false,
                            preferSystemDownloader = true,
                            onProgress = { downloaded, total ->
                                // crude progress: show indeterminate spinner only
                            }
                        )
                    } catch (e: OutOfMemoryError) {
                        // If streaming still OOMs for any reason, fall back to system downloader explicitly.
                        e.printStackTrace()
                        io.aatricks.llmedge.huggingface.HuggingFaceHub.ensureRepoFileOnDisk(
                            context = this@StableDiffusionActivity,
                            modelId = "Meina/MeinaMix",
                            filename = "MeinaPastel - baked VAE.safetensors",
                            token = null,
                            forceDownload = false,
                            preferSystemDownloader = true,
                            onProgress = null
                        )
                    }

                    localSd = try {
                        StableDiffusion.load(
                            context = this@StableDiffusionActivity,
                            modelId = "Meina/MeinaMix",
                            filename = null,
                            nThreads = Runtime.getRuntime().availableProcessors(),
                            offloadToCpu = true,
                            keepClipOnCpu = true,
                            keepVaeOnCpu = false,
                            vaePath = vaeDownload.file.absolutePath
                        )
                    } catch (iae: IllegalArgumentException) {
                        // Common case: the model repo doesn't contain a .gguf model file.
                        iae.printStackTrace()
                        withContext(Dispatchers.Main) {
                            android.widget.Toast.makeText(
                                this@StableDiffusionActivity,
                                "Model repo has no GGUF model file: ${iae.message}. Provide a GGUF modelPath or choose a different repo.",
                                android.widget.Toast.LENGTH_LONG
                            ).show()
                        }
                        progress.visibility = android.view.View.GONE
                        return@launch
                    }

                    // Use a smaller default resolution during initial tests to reduce memory pressure.
                    val bmp: Bitmap = try {
                        localSd.txt2img(
                        StableDiffusion.GenerateParams(
                            prompt = promptInput.text.toString().ifBlank { "a cute pastel anime cat, soft colors, high quality" },
                            steps = 20,
                            cfgScale = 7.0f,
                            width = 128,
                            height = 128,
                            seed = 42L
                        )
                        )
                    } catch (oom: OutOfMemoryError) {
                        // Log and fallback: try an even smaller size
                        oom.printStackTrace()
                        localSd.txt2img(
                            StableDiffusion.GenerateParams(
                                prompt = promptInput.text.toString().ifBlank { "a cute pastel anime cat, soft colors, high quality" },
                                steps = 10,
                                cfgScale = 7.0f,
                                width = 64,
                                height = 64,
                                seed = 42L
                            )
                        )
                    }
                    imageView.setImageBitmap(bmp)
                } catch (e: Throwable) {
                    e.printStackTrace()
                } finally {
                    localSd?.close()
                    progress.visibility = android.view.View.GONE
                    generateBtn.isEnabled = true
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        sd?.close()
        sd = null
    }
}
