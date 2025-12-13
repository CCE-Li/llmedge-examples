package com.example.llmedgeexample

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import io.aatricks.llmedge.BarkTTS
import io.aatricks.llmedge.huggingface.HuggingFaceHub
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Activity demonstrating text-to-speech synthesis using Bark.
 *
 * Features:
 * - Text input for speech synthesis
 * - Progress tracking during generation
 * - Audio playback of generated speech
 * - Save to WAV file
 *
 * Requirements:
 * - Bark model files (can be downloaded from Hugging Face)
 */
class TTSActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "TTSActivity"
        private const val HUGGING_FACE_MODEL_ID = "PABannier/bark.cpp"
        private const val MODEL_DIR = "bark_model"
    }

    private val statusLabel: TextView by lazy { findViewById(R.id.ttsStatusLabel) }
    private val textInput: EditText by lazy { findViewById(R.id.ttsTextInput) }
    private val logOutput: TextView by lazy { findViewById(R.id.ttsLogOutput) }
    private val logScroll: ScrollView by lazy { findViewById(R.id.ttsLogScroll) }
    private val progressBar: ProgressBar by lazy { findViewById(R.id.ttsProgressBar) }
    private val progressLabel: TextView by lazy { findViewById(R.id.ttsProgressLabel) }
    private val generateButton: Button by lazy { findViewById(R.id.btnGenerate) }
    private val playButton: Button by lazy { findViewById(R.id.btnPlay) }
    private val saveButton: Button by lazy { findViewById(R.id.btnSave) }
    private val downloadButton: Button by lazy { findViewById(R.id.btnDownloadBarkModel) }
    private val timingLabel: TextView by lazy { findViewById(R.id.ttsTiming) }

    private var barkTTS: BarkTTS? = null
    private var generationJob: Job? = null
    private var audioTrack: AudioTrack? = null
    private var lastAudioResult: BarkTTS.AudioResult? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_tts)

        setupButtons()
        checkModelAvailability()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopPlayback()
        barkTTS?.close()
        barkTTS = null
    }

    private fun setupButtons() {
        generateButton.setOnClickListener {
            val text = textInput.text.toString().trim()
            if (text.isEmpty()) {
                Toast.makeText(this, "Please enter some text", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            generateSpeech(text)
        }

        playButton.setOnClickListener {
            lastAudioResult?.let { playAudio(it) }
                ?: Toast.makeText(this, "No audio generated yet", Toast.LENGTH_SHORT).show()
        }

        saveButton.setOnClickListener {
            lastAudioResult?.let { saveAudio(it) }
                ?: Toast.makeText(this, "No audio generated yet", Toast.LENGTH_SHORT).show()
        }

        downloadButton.setOnClickListener { downloadModel() }

        // Initial button states
        playButton.isEnabled = false
        saveButton.isEnabled = false
        generateButton.isEnabled = false
    }

    private fun log(message: String) {
        runOnUiThread {
            logOutput.append("$message\n")
            logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
        }
    }

    private fun checkModelAvailability() {
        lifecycleScope.launch(Dispatchers.IO) {
            val modelDir = File(filesDir, MODEL_DIR)
            val vocabFile = File(modelDir, "ggml_vocab.bin")
            val modelExists = modelDir.exists() && vocabFile.exists()

            withContext(Dispatchers.Main) {
                if (modelExists) {
                    statusLabel.text = "Model ready: ${modelDir.name}"
                    downloadButton.visibility = View.GONE
                    loadModel(modelDir.absolutePath)
                } else {
                    statusLabel.text = "Model not found. Please download."
                    downloadButton.visibility = View.VISIBLE
                    generateButton.isEnabled = false
                }
            }
        }
    }

    private fun downloadModel() {
        downloadButton.isEnabled = false
        progressBar.visibility = View.VISIBLE
        progressBar.isIndeterminate = true
        statusLabel.text = "Downloading model files..."

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val modelDir = File(filesDir, MODEL_DIR)
                modelDir.mkdirs()

                val modelFiles = listOf(
                    "ggml_vocab.bin",
                    "ggml_weights_text.bin",
                    "ggml_weights_coarse.bin",
                    "ggml_weights_fine.bin",
                    "ggml_weights_codec.bin"
                )

                var downloadedCount = 0
                for (filename in modelFiles) {
                    withContext(Dispatchers.Main) {
                        statusLabel.text = "Downloading $filename (${downloadedCount + 1}/${modelFiles.size})..."
                        progressBar.progress = (downloadedCount * 100) / modelFiles.size
                    }

                    val result = HuggingFaceHub.ensureModelOnDisk(
                        context = applicationContext,
                        modelId = HUGGING_FACE_MODEL_ID,
                        filename = filename
                    )

                    // Copy to model directory
                    val destFile = File(modelDir, filename)
                    if (!destFile.exists()) {
                        result.file.copyTo(destFile)
                    }

                    downloadedCount++
                    log("Downloaded: $filename")
                }

                withContext(Dispatchers.Main) {
                    progressBar.visibility = View.GONE
                    statusLabel.text = "Model downloaded successfully"
                    downloadButton.visibility = View.GONE
                    loadModel(modelDir.absolutePath)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    progressBar.visibility = View.GONE
                    statusLabel.text = "Download failed: ${e.message}"
                    downloadButton.isEnabled = true
                    log("Error: ${e.message}")
                }
            }
        }
    }

    private fun loadModel(modelPath: String) {
        statusLabel.text = "Loading model..."
        progressBar.visibility = View.VISIBLE
        progressBar.isIndeterminate = true

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                log("Loading Bark model from: $modelPath")
                val startTime = System.currentTimeMillis()

                barkTTS = BarkTTS.load(
                    modelPath = modelPath,
                    seed = 0,  // Random seed
                    temperature = 0.7f,
                    fineTemperature = 0.5f,
                    verbosity = 0
                )

                val loadTime = System.currentTimeMillis() - startTime

                withContext(Dispatchers.Main) {
                    progressBar.visibility = View.GONE
                    statusLabel.text = "Model loaded (${loadTime}ms)"
                    generateButton.isEnabled = true
                    log("Model loaded in ${loadTime}ms")
                    log("Sample rate: ${barkTTS?.getSampleRate()}Hz")
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    progressBar.visibility = View.GONE
                    statusLabel.text = "Failed to load model: ${e.message}"
                    log("Error loading model: ${e.message}")
                }
            }
        }
    }

    private fun generateSpeech(text: String) {
        val bark = barkTTS ?: run {
            Toast.makeText(this, "Model not loaded", Toast.LENGTH_SHORT).show()
            return
        }

        // Cancel any existing generation
        generationJob?.cancel()
        stopPlayback()

        generateButton.isEnabled = false
        playButton.isEnabled = false
        saveButton.isEnabled = false
        progressBar.visibility = View.VISIBLE
        progressBar.progress = 0
        progressBar.isIndeterminate = false
        progressLabel.text = "Starting..."

        log("Generating speech for: \"$text\"")

        // Set progress callback
        bark.setProgressCallback { step, progress ->
            runOnUiThread {
                val stepName = when (step) {
                    BarkTTS.EncodingStep.SEMANTIC -> "Semantic"
                    BarkTTS.EncodingStep.COARSE -> "Coarse"
                    BarkTTS.EncodingStep.FINE -> "Fine"
                }
                progressLabel.text = "$stepName: $progress%"
                // Map total progress (3 steps, each 100%)
                val totalProgress = when (step) {
                    BarkTTS.EncodingStep.SEMANTIC -> progress / 3
                    BarkTTS.EncodingStep.COARSE -> 33 + progress / 3
                    BarkTTS.EncodingStep.FINE -> 66 + progress / 3
                }
                progressBar.progress = totalProgress
            }
        }

        generationJob = lifecycleScope.launch(Dispatchers.IO) {
            try {
                val startTime = System.currentTimeMillis()

                val result = bark.generate(
                    text = text,
                    params = BarkTTS.GenerateParams(
                        nThreads = Runtime.getRuntime().availableProcessors().coerceAtMost(4)
                    )
                )

                val genTime = System.currentTimeMillis() - startTime

                withContext(Dispatchers.Main) {
                    lastAudioResult = result
                    progressBar.visibility = View.GONE
                    progressLabel.text = "Complete"
                    generateButton.isEnabled = true
                    playButton.isEnabled = true
                    saveButton.isEnabled = true

                    val timing = "Generated ${result.samples.size} samples (${String.format("%.2f", result.durationSeconds)}s) in ${genTime}ms"
                    timingLabel.text = timing
                    log(timing)
                    log("Real-time factor: ${String.format("%.2f", result.durationSeconds * 1000 / genTime)}x")
                }
            } catch (e: CancellationException) {
                withContext(Dispatchers.Main) {
                    progressBar.visibility = View.GONE
                    generateButton.isEnabled = true
                    log("Generation cancelled")
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    progressBar.visibility = View.GONE
                    generateButton.isEnabled = true
                    statusLabel.text = "Generation failed: ${e.message}"
                    log("Error: ${e.message}")
                }
            }
        }
    }

    private fun playAudio(audio: BarkTTS.AudioResult) {
        stopPlayback()

        log("Playing audio (${String.format("%.2f", audio.durationSeconds)}s)...")

        // Convert float samples to 16-bit PCM
        val pcmData = ShortArray(audio.samples.size)
        for (i in audio.samples.indices) {
            val sample = audio.samples[i].coerceIn(-1.0f, 1.0f)
            pcmData[i] = (sample * 32767).toInt().toShort()
        }

        val bufferSize = AudioTrack.getMinBufferSize(
            audio.sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )

        audioTrack = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(audio.sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build()
            )
            .setBufferSizeInBytes(bufferSize.coerceAtLeast(pcmData.size * 2))
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()

        audioTrack?.write(pcmData, 0, pcmData.size)
        audioTrack?.play()

        playButton.text = "Stop"
        playButton.setOnClickListener {
            stopPlayback()
            playButton.text = "Play"
            playButton.setOnClickListener {
                lastAudioResult?.let { playAudio(it) }
            }
        }
    }

    private fun stopPlayback() {
        audioTrack?.stop()
        audioTrack?.release()
        audioTrack = null
    }

    private fun saveAudio(audio: BarkTTS.AudioResult) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val outputFile = File(getExternalFilesDir(null), "bark_output_${System.currentTimeMillis()}.wav")
                barkTTS?.saveAsWav(audio, outputFile.absolutePath)

                withContext(Dispatchers.Main) {
                    log("Saved to: ${outputFile.name}")
                    Toast.makeText(this@TTSActivity, "Saved to ${outputFile.name}", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    log("Error saving: ${e.message}")
                    Toast.makeText(this@TTSActivity, "Failed to save: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }
}
