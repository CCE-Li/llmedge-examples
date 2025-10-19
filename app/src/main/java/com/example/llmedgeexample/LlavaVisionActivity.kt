package com.example.llmedgeexample

import android.app.Activity
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.net.toFile
import java.io.File
import io.aatricks.llmedge.SmolLM
import io.aatricks.llmedge.vision.Projector
import io.aatricks.llmedge.vision.ImageSource
import io.aatricks.llmedge.vision.SmolLMVisionAdapter
import io.aatricks.llmedge.vision.VisionParams
import io.aatricks.llmedge.vision.ocr.MlKitOcrEngine
import io.aatricks.llmedge.vision.OcrParams
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class LlavaVisionActivity : AppCompatActivity() {
    private val TAG = "LlavaVisionActivity"

    private val scope = MainScope()

    private lateinit var btnPick: Button
    private lateinit var btnTake: Button
    private lateinit var btnRun: Button
    private lateinit var btnDescribeLocal: Button
    private lateinit var etPrompt: EditText
    private lateinit var tvResult: TextView
    private lateinit var imagePreview: ImageView
    private lateinit var progress: ProgressBar

    private var imageUri: Uri? = null

    private val pickImageLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            imageUri = uri
            // load & downsample on background coroutine to avoid OOM when decoding large images
            scope.launch {
                try {
                    val bmp = io.aatricks.llmedge.vision.ImageUtils.imageToBitmap(this@LlavaVisionActivity, io.aatricks.llmedge.vision.ImageSource.UriSource(uri))
                    // downscale for display to a safe dimension
                    val displayBmp = io.aatricks.llmedge.vision.ImageUtils.preprocessImage(bmp, correctOrientation = true, maxDimension = 1024, enhance = false)
                    runOnUiThread {
                        imagePreview.setImageBitmap(displayBmp)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to load image for preview: ${e.message}")
                }
            }
        }
    }

    private val takePictureLauncher = registerForActivityResult(
        ActivityResultContracts.TakePicturePreview()
    ) { bitmap ->
        if (bitmap != null) {
            // downscale the preview bitmap for display and saving
            val safeBmp = io.aatricks.llmedge.vision.ImageUtils.preprocessImage(bitmap, correctOrientation = true, maxDimension = 1600, enhance = false)
            imagePreview.setImageBitmap(safeBmp)
            // save to cache and create Uri for adapter
            val file = File.createTempFile("llava_input", ".jpg", cacheDir)
            // use ImageUtils to save via compress path
            try {
                // Save the (possibly downscaled) bitmap to a file
                file.outputStream().use { out ->
                    safeBmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, out)
                }
                imageUri = Uri.fromFile(file)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to save captured image: ${e.message}")
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_llava_vision)

        btnPick = findViewById(R.id.btnPickImage)
        btnTake = findViewById(R.id.btnTakePicture)
        btnRun = findViewById(R.id.btnRun)
        btnDescribeLocal = findViewById(R.id.btnDescribeLocal)
        etPrompt = findViewById(R.id.etPrompt)
        tvResult = findViewById(R.id.tvResult)
        imagePreview = findViewById(R.id.imagePreview)
        progress = findViewById(R.id.progress)

        btnPick.setOnClickListener {
            pickImageLauncher.launch("image/*")
        }

        btnTake.setOnClickListener {
            takePictureLauncher.launch(null)
        }

        btnRun.setOnClickListener {
            runVisionQuery()
        }

        btnDescribeLocal.setOnClickListener {
            runLocalDescribe()
        }
    }

    private fun runLocalDescribe() {
        val uri = imageUri
        if (uri == null) {
            tvResult.text = "Pick or take an image first"
            return
        }

        progress.visibility = View.VISIBLE
        tvResult.text = ""

        val exceptionHandler = CoroutineExceptionHandler { _, ex ->
            Log.e(TAG, "Unhandled coroutine error", ex)
            runOnUiThread {
                progress.visibility = View.GONE
                tvResult.text = "Error: ${ex.message}"
            }
        }

        scope.launch(exceptionHandler) {
            try {
                // Prepare a local, downscaled file for analysis
                val localInput = File.createTempFile("llava_input", ".jpg", cacheDir)
                try {
                    val bmp = io.aatricks.llmedge.vision.ImageUtils.imageToBitmap(this@LlavaVisionActivity, io.aatricks.llmedge.vision.ImageSource.UriSource(uri))
                    val scaled = io.aatricks.llmedge.vision.ImageUtils.preprocessImage(bmp, correctOrientation = true, maxDimension = 1600, enhance = false)
                    localInput.outputStream().use { out -> scaled.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, out) }
                } catch (e: Exception) {
                    contentResolver.openInputStream(uri)?.use { ins ->
                        localInput.outputStream().use { out -> ins.copyTo(out) }
                    }
                }

                // OCR (existing)
                val ocrEngine = MlKitOcrEngine(this@LlavaVisionActivity)
                val ocrResult = try {
                    ocrEngine.extractText(io.aatricks.llmedge.vision.ImageSource.FileSource(localInput), OcrParams())
                } catch (e: Exception) {
                    Log.w(TAG, "OCR failed in local describe", e)
                    null
                }
                try { ocrEngine.close() } catch (_: Exception) {}

                // Local description
                val desc = io.aatricks.llmedge.vision.LocalImageDescriber.describe(
                    this@LlavaVisionActivity,
                    io.aatricks.llmedge.vision.ImageSource.FileSource(localInput)
                )

                runOnUiThread {
                    progress.visibility = View.GONE
                    val sb = StringBuilder()
                    sb.appendLine("Local description: ${desc.summary}")
                    if (desc.labels.isNotEmpty()) sb.appendLine("Labels: ${desc.labels.joinToString(", ")}")
                    val size = desc.size
                    if (size != null) sb.appendLine("Size: ${size.first}x${size.second}")
                    if (desc.dominantColor != null) sb.appendLine("Dominant color: ${desc.dominantColor}")
                    if (ocrResult != null && ocrResult.text.isNotBlank()) {
                        val ocrSnippet = ocrResult.text.take(500)
                        sb.appendLine("OCR: $ocrSnippet")
                    }
                    tvResult.text = sb.toString().trim()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Local describe failed", e)
                runOnUiThread {
                    progress.visibility = View.GONE
                    tvResult.text = "Error: ${e.message}"
                }
            }
        }
    }

    private fun runVisionQuery() {
        val promptText = etPrompt.text.toString().ifBlank { "Describe the image" }
        val uri = imageUri
        if (uri == null) {
            tvResult.text = "Pick or take an image first"
            return
        }

        progress.visibility = View.VISIBLE
        tvResult.text = ""

        val exceptionHandler = CoroutineExceptionHandler { _, ex ->
            Log.e(TAG, "Unhandled coroutine error", ex)
            runOnUiThread {
                progress.visibility = View.GONE
                tvResult.text = "Error: ${ex.message}"
            }
        }

        scope.launch(exceptionHandler) {
            try {
                // Create and configure SmolLM
                val smol = SmolLM()

                // Example LLaVA model id; you may replace with a smaller or local model
                val modelId = "xtuner/llava-phi-3-mini-gguf" // placeholder; may be large
                val filename = "llava-phi-3-mini-int4.gguf"
                runOnUiThread { tvResult.text = "Loading model (this may take a while)..." }

                // Use conservative inference params to reduce hallucination
                val loadParams = SmolLM.InferenceParams(
                    numThreads = 2,
                    useMmap = true,
                    temperature = 0.0f,
                    storeChats = false,
                    thinkingMode = SmolLM.ThinkingMode.DISABLED
                )

                val downloadResult = smol.loadFromHuggingFace(
                    this@LlavaVisionActivity,
                    modelId,
                    filename = filename,
                    params = loadParams,
                    onProgress = { downloaded: Long, total: Long? ->
                        runOnUiThread {
                            tvResult.text = "Downloading model: $downloaded / ${total ?: "?"}"
                        }
                    }
                )

                // Download mmproj (projector) file from the same repo if available.
                // This example uses a specific filename that may exist in the model repo.
                val mmprojFilename = "llava-phi-3-mini-mmproj-f16.gguf"
                var mmprojFile: File? = null
                try {
                    runOnUiThread { tvResult.text = "Downloading mmproj (projector)..." }
                    val mmprojDownload = io.aatricks.llmedge.huggingface.HuggingFaceHub.ensureModelOnDisk(
                        context = this@LlavaVisionActivity,
                        modelId = modelId,
                        filename = mmprojFilename,
                        preferSystemDownloader = true,
                    )
                    mmprojFile = mmprojDownload.file
                } catch (e: Exception) {
                    Log.w(TAG, "mmproj not found in repo or failed to download: ${e.message}")
                }

                // Prepare local image file for OCR and (separately) prepared embeddings for the model
                val localInput = File.createTempFile("llava_input", ".jpg", cacheDir)
                val preparedImageFile = File.createTempFile("llava_prepared", ".bin", cacheDir)

                // Ensure we always have a local image saved (downscaled) for OCR and display
                try {
                    // Use ImageUtils to load and preprocess (applies EXIF and scaling)
                    val bmp = io.aatricks.llmedge.vision.ImageUtils.imageToBitmap(this@LlavaVisionActivity, io.aatricks.llmedge.vision.ImageSource.UriSource(uri))
                    val scaled = io.aatricks.llmedge.vision.ImageUtils.preprocessImage(bmp, correctOrientation = true, maxDimension = 1600, enhance = false)
                    localInput.outputStream().use { out -> scaled.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, out) }
                } catch (e: Exception) {
                    // fallback to raw copy if preprocessing fails
                    contentResolver.openInputStream(uri)?.use { ins ->
                        localInput.outputStream().use { out -> ins.copyTo(out) }
                    }
                }

                // If mmproj was downloaded, initialize Projector, encode image, then close projector
                if (mmprojFile != null) {
                    runOnUiThread { tvResult.text = "Preparing image with mmproj projector..." }
                    val projector = Projector()
                    // Provide the native model pointer so the native mtmd init
                    // can validate embedding dimensions against the text model
                    // if desired. This avoids concurrently loading both models
                    // in Java/Kotlin memory.
                    val nativeModelPtr = smol.getNativeModelPointer()
                    // Use the overload that accepts a native model pointer so the
                    // native layer can validate embedding dims.
                    projector.init(mmprojFile.absolutePath, nativeModelPtr)

                    val ok = projector.encodeImageToFile(localInput.absolutePath, preparedImageFile.absolutePath)
                    projector.close()
                    if (!ok) {
                        Log.w(TAG, "Projector failed; falling back to using raw image file")
                        // fallback: copy input to preparedImageFile so adapter can still read an image file
                        localInput.copyTo(preparedImageFile, overwrite = true)
                    }
                } else {
                    // No mmproj available; just use the local image as the prepared file (adapter will receive an image file)
                    localInput.copyTo(preparedImageFile, overwrite = true)
                }

                // Now load the text model (ensuring mmproj and projector are closed)
                val adapter = io.aatricks.llmedge.vision.SmolLMVisionAdapter(this@LlavaVisionActivity, smol)
                adapter.loadVisionModel(downloadResult.file.absolutePath)

                // For OCR we must always use the actual image file (localInput).
                val ocrImageSource = io.aatricks.llmedge.vision.ImageSource.FileSource(localInput)
                // For the model adapter: pass the prepared embeddings (.bin) when projector exists; otherwise pass the image file.
                val modelImageSource = io.aatricks.llmedge.vision.ImageSource.FileSource(preparedImageFile)

                runOnUiThread { tvResult.text = "Running vision analysis..." }

                // Run ML Kit OCR on the image to provide textual context for the model
                val ocrEngine = MlKitOcrEngine(this@LlavaVisionActivity)
                val ocrResult = try {
                    ocrEngine.extractText(ocrImageSource, OcrParams())
                } catch (e: Exception) {
                    Log.w(TAG, "OCR failed, continuing without OCR", e)
                    null
                }

                // Compute simple image metadata (dimensions, average color)
                var dimsText = ""
                var avgColorHex: String? = null
                try {
                    contentResolver.openInputStream(uri)?.use { stream ->
                        val opts = android.graphics.BitmapFactory.Options()
                        opts.inJustDecodeBounds = true
                        android.graphics.BitmapFactory.decodeStream(stream, null, opts)
                        val width = opts.outWidth
                        val height = opts.outHeight
                        dimsText = "${width}x${height}"
                    }

                    // For average color we decode a small scaled bitmap
                    contentResolver.openInputStream(uri)?.use { stream2 ->
                        val decodeOpts = android.graphics.BitmapFactory.Options()
                        decodeOpts.inSampleSize = 8 // small thumbnail
                        val bmp = android.graphics.BitmapFactory.decodeStream(stream2, null, decodeOpts)
                        if (bmp != null) {
                            var rSum = 0L
                            var gSum = 0L
                            var bSum = 0L
                            val w = bmp.width
                            val h = bmp.height
                            val total = w * h
                            val pixels = IntArray(total)
                            bmp.getPixels(pixels, 0, w, 0, 0, w, h)
                            for (p in pixels) {
                                rSum += (p shr 16) and 0xFF
                                gSum += (p shr 8) and 0xFF
                                bSum += p and 0xFF
                            }
                            val rAvg = (rSum / total).toInt()
                            val gAvg = (gSum / total).toInt()
                            val bAvg = (bSum / total).toInt()
                            avgColorHex = String.format("#%02X%02X%02X", rAvg, gAvg, bAvg)
                            bmp.recycle()
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to compute image metadata", e)
                }

                // Build a stronger, grounded augmented prompt containing OCR and concise guidance
                val sb = StringBuilder()
                sb.appendLine("SYSTEM: You are an assistant that answers questions using ONLY the provided image context and OCR text. Do NOT invent facts or guess. If the information is not present in the image or OCR, respond exactly: 'I don't know based on the image.' Be concise (max 150 words). When relevant, transcribe OCR text verbatim and cite it.")
                sb.appendLine()
                sb.appendLine("Context (image + OCR):")
                if (!dimsText.isBlank()) sb.appendLine("- Image size (informational): $dimsText")
                if (avgColorHex != null) sb.appendLine("- Dominant color (approx): $avgColorHex")
                if (ocrResult != null && ocrResult.text.isNotBlank()) {
                    sb.appendLine("- OCR_TEXT_START")
                    // keep OCR to a reasonable length
                    val ocrSnippet = ocrResult.text.take(2000)
                    sb.appendLine(ocrSnippet)
                    sb.appendLine("- OCR_TEXT_END")
                } else {
                    sb.appendLine("- OCR_TEXT_START\n<no OCR text available>\n- OCR_TEXT_END")
                }
                sb.appendLine()
                sb.appendLine("Task: Answer the user's question about the image. Prefer short direct answers. When asked to describe, list visible objects, notable attributes (color, count, relative position), and any readable text. Do not mention file names, pixel dimensions, or internal metadata unless asked.")
                sb.appendLine("Return format: Provide a single-line JSON object with these keys: 'objects' (array of short object descriptions), 'attributes' (short comma-separated notable attributes), 'text' (OCR transcription or empty string). If you cannot answer, return exactly: {\"objects\": [], \"attributes\": \"\", \"text\": \"I don't know based on the image.\"}.")
                sb.appendLine()
                // Few-shot examples demonstrating desired brevity and grounding
                sb.appendLine("EXAMPLES:")
                sb.appendLine("Image: [photo of a storefront with a sign reading 'Cafe Luna']\nQ: What does the sign say?\nA: The sign reads 'Cafe Luna.'")
                sb.appendLine("Image: [photo of a soccer ball next to a red backpack]\nQ: What objects are in the image?\nA: A soccer ball (black/white) and a red backpack to its right.")
                sb.appendLine()
                sb.appendLine("User question: $promptText")
                sb.appendLine()
                sb.appendLine("Answer:")

                val augmentedPrompt = sb.toString()

                // Use the adapter for inference (it currently forwards to SmolLM.getResponse). Passing the augmented prompt
                val result = try {
                    adapter.analyze(modelImageSource, augmentedPrompt, VisionParams())
                } catch (e: Exception) {
                    // If adapter fails, fall back to asking SmolLM directly with the augmented prompt
                    Log.w(TAG, "Adapter analyze failed, falling back to direct getResponse", e)
                    val resp = smol.getResponse(augmentedPrompt)
                    io.aatricks.llmedge.vision.VisionResult(
                        text = resp,
                        durationMs = 0L,
                        modelId = "local-fallback",
                        tokensIn = 0,
                        tokensOut = 0
                    )
                }

                runOnUiThread {
                    progress.visibility = View.GONE
                    // Try to parse model output as the structured JSON we requested
                    val outText = result.text
                    val pretty = try {
                        val obj = org.json.JSONObject(outText.trim())
                        val objects = if (obj.has("objects")) {
                            val a = obj.getJSONArray("objects")
                            val list = mutableListOf<String>()
                            for (i in 0 until a.length()) list.add(a.optString(i))
                            if (list.isEmpty()) "No obvious objects detected." else "Objects: ${list.joinToString(", ")}."
                        } else ""
                        val attrs = if (obj.has("attributes")) {
                            val s = obj.optString("attributes")
                            if (s.isNullOrBlank()) "" else "Attributes: $s."
                        } else ""
                        val textField = if (obj.has("text")) {
                            val t = obj.optString("text")
                            if (t.isNullOrBlank()) "" else "Text (OCR): $t"
                        } else ""
                        val parts = listOf(objects, attrs, textField).filter { it.isNotBlank() }
                        if (parts.isEmpty()) "Model (${result.modelId}) response:\n$outText" else "Model (${result.modelId}) response:\n" + parts.joinToString("\n")
                    } catch (e: Exception) {
                        // Not JSON; show raw text
                        "Model (${result.modelId}) response:\n$outText"
                    }
                    tvResult.text = pretty
                }

                // Close OCR engine resources
                try { ocrEngine.close() } catch (_: Exception) {}

                adapter.close()
                smol.close()

            } catch (e: Exception) {
                Log.e(TAG, "Vision demo failed", e)
                runOnUiThread {
                    progress.visibility = View.GONE
                    tvResult.text = "Error: ${e.message}"
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }
}
