package com.example.llmedgeexample

import android.media.MediaPlayer
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import io.aatricks.llmedge.LLMEdgeManager
import io.aatricks.llmedge.huggingface.HuggingFaceHub
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Activity demonstrating text-to-speech synthesis using Bark TTS.
 *
 * Features:
 * - Text-to-speech synthesis
 * - Audio playback
 * - Model downloading from Hugging Face
 * - Progress tracking
 *
 * Requirements:
 * - Bark TTS model file (can be downloaded from Hugging Face)
 */
class TTSActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "TTSActivity"
        private const val DEFAULT_MODEL_FILE = "bark-small.pt"
        private const val HUGGING_FACE_MODEL_ID = "suno/bark"
    }

    private val statusLabel: TextView by lazy { findViewById(R.id.ttsStatusLabel) }
    private val progressBar: ProgressBar by lazy { findViewById(R.id.ttsProgressBar) }
    private val textInput: EditText by lazy { findViewById(R.id.ttsTextInput) }
    private val generateButton: Button by lazy { findViewById(R.id.btnGenerateSpeech) }
    private val playButton: Button by lazy { findViewById(R.id.btnPlayAudio) }
    private val downloadButton: Button by lazy { findViewById(R.id.btnDownloadModel) }
    private val infoLabel: TextView by lazy { findViewById(R.id.ttsInfoLabel) }

    private var speechModelLoaded = false
    private var mediaPlayer: MediaPlayer? = null
    private var lastGeneratedAudio: File? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_tts)

        setupButtons()
        checkModelAvailability()
    }

    private fun setupButtons() {
        generateButton.setOnClickListener {
            val text = textInput.text.toString().trim()
            if (text.isNotEmpty()) {
                generateSpeech(text)
            } else {
                Toast.makeText(this, "Please enter text to synthesize", Toast.LENGTH_SHORT).show()
            }
        }

        playButton.setOnClickListener {
            playGeneratedAudio()
        }

        downloadButton.setOnClickListener {
            downloadModel()
        }

        // Initial button states
        generateButton.isEnabled = false
        playButton.isEnabled = false
    }

    private fun checkModelAvailability() {
        // Check if speech models are available
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                // Speech models are auto-downloaded, just check if we can use the API
                val available = true // Assume available since LLMEdgeManager handles auto-download
                withContext(Dispatchers.Main) {
                    statusLabel.text = "Speech models ready"
                    downloadButton.visibility = View.GONE
                    speechModelLoaded = true
                    generateButton.isEnabled = true
                    infoLabel.text = "Bark TTS model ready. Enter text and click Generate Speech."
                }
            } catch (e: Exception) {
                android.util.Log.e(TAG, "Speech models not available", e)
                withContext(Dispatchers.Main) {
                    statusLabel.text = "Model not found. Using auto-download."
                    downloadButton.visibility = View.VISIBLE
                    generateButton.isEnabled = true // Allow auto-download
                    speechModelLoaded = true
                }
            }
        }
    }

    private fun downloadModel() {
        // Models are auto-downloaded by LLMEdgeManager, so this just enables generation
        downloadButton.isEnabled = false
        statusLabel.text = "Models will be auto-downloaded on first use..."
        
        downloadButton.visibility = View.GONE
        generateButton.isEnabled = true
        infoLabel.text = "Bark TTS model ready. Enter text and click Generate Speech."
    }

    
    private fun generateSpeech(text: String) {
        if (!speechModelLoaded) {
            Toast.makeText(this, "Model not loaded", Toast.LENGTH_SHORT).show()
            return
        }

        generateButton.isEnabled = false
        progressBar.visibility = View.VISIBLE
        progressBar.isIndeterminate = false
        progressBar.progress = 0
        statusLabel.text = "Generating speech..."

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                // Generate speech using LLMEdgeManager
                val audioFile = File(cacheDir, "generated_speech_${System.currentTimeMillis()}.wav")
                
                LLMEdgeManager.synthesizeSpeechToFile(
                    context = applicationContext,
                    text = text,
                    outputFile = audioFile
                ) { step, progress ->
                    lifecycleScope.launch(Dispatchers.Main) {
                        progressBar.progress = progress
                        statusLabel.text = "Generating: ${step.name} - $progress%"
                    }
                }

                lastGeneratedAudio = audioFile

                withContext(Dispatchers.Main) {
                    statusLabel.text = "Speech generated successfully!"
                    progressBar.visibility = View.GONE
                    playButton.isEnabled = true
                    generateButton.isEnabled = true
                    
                    val fileSizeKB = audioFile.length() / 1024
                    infoLabel.text = "Audio generated: ${audioFile.name} (${fileSizeKB}KB)\nReady for playback."
                }
            } catch (e: Exception) {
                android.util.Log.e(TAG, "Failed to generate speech", e)
                withContext(Dispatchers.Main) {
                    statusLabel.text = "Generation failed: ${e.message}"
                    progressBar.visibility = View.GONE
                    generateButton.isEnabled = true
                }
            }
        }
    }

    private fun playGeneratedAudio() {
        val audioFile = lastGeneratedAudio ?: run {
            Toast.makeText(this, "No audio generated yet", Toast.LENGTH_SHORT).show()
            return
        }

        if (!audioFile.exists()) {
            Toast.makeText(this, "Audio file not found", Toast.LENGTH_SHORT).show()
            return
        }

        try {
            // Stop any currently playing audio
            mediaPlayer?.release()
            
            mediaPlayer = MediaPlayer().apply {
                setDataSource(audioFile.absolutePath)
                setOnCompletionListener {
                    playButton.isEnabled = true
                    playButton.text = "▶️ Play Audio"
                }
                setOnErrorListener { _, _, _ ->
                    playButton.isEnabled = true
                    playButton.text = "▶️ Play Audio"
                    true
                }
                prepare()
                start()
            }
            
            playButton.isEnabled = false
            playButton.text = "⏹️ Stop Audio"
            statusLabel.text = "Playing audio..."
            
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Failed to play audio", e)
            Toast.makeText(this, "Failed to play audio: ${e.message}", Toast.LENGTH_LONG).show()
            playButton.isEnabled = true
            playButton.text = "▶️ Play Audio"
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        mediaPlayer?.release()
        mediaPlayer = null
        LLMEdgeManager.unloadSpeechModels()
    }
}
