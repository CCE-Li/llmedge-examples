# llmedge Examples

Comprehensive demonstration applications for the llmedge Android library, showcasing on-device language model inference, RAG pipelines, image generation, and video synthesis capabilities.

**Main Library Repository**: https://github.com/Aatricks/llmedge

## Overview

This example application provides production-ready demonstrations of llmedge's core features. Each activity is designed to illustrate best practices for model loading, memory management, and efficient on-device inference.

## Included Demonstrations

### Language Model Inference

**Local Asset Demo** (`LocalAssetDemoActivity.kt`)
- Demonstrates loading GGUF models bundled within the APK
- Illustrates asset extraction to app-private storage
- Shows both blocking and streaming inference patterns
- Suitable for offline-first applications

**Hugging Face Demo** (`HuggingFaceDemoActivity.kt`)
- Automated model download from Hugging Face Hub
- Progress monitoring and cache management
- Demonstrates proper error handling for network operations
- Shows model reuse across application sessions

### Retrieval-Augmented Generation

**RAG Demo** (`RagActivity.kt`)
- Complete on-device RAG pipeline implementation
- Document indexing with ONNX embeddings
- Vector similarity search and context retrieval
- Integration with SmolLM for answer generation
- Demonstrates PDF parsing and text chunking strategies

### Vision and Multimodal Processing

**Image Text Extraction** (`ImageToTextActivity.kt`)
- Google ML Kit OCR integration
- Batch image processing capabilities
- Error handling for unsupported image formats
- Demonstrates preprocessing for vision models

**Vision Model Demo** (`LlavaVisionActivity.kt`)
- Vision-capable language model integration
- Image-to-text description generation
- Multimodal input preparation
- Demonstrates vision model inference patterns

### Generative Media

**Image Generation** (`StableDiffusionActivity.kt`)
- Text-to-image synthesis using Stable Diffusion
- Memory-aware configuration options
- Progressive generation with cancellation support
- Demonstrates VAE loading and tensor offloading strategies

**Video Generation** (`VideoGenerationActivity.kt`)
- Text-to-video synthesis using Wan models
- Multi-file model loading (main + VAE + T5XXL)
- Device capability detection (12GB+ RAM required)
- Frame-by-frame progress monitoring
- Demonstrates proper resource cleanup

**Headless Video Testing** (`HeadlessVideoTestActivity.kt`)
- Automated E2E testing infrastructure
- Programmatic model validation
- Performance benchmarking utilities
- Command-line invocation support

### Speech Processing

**Speech E2E Test** (`SpeechE2ETest.kt`)
- Whisper STT model download and transcription
- Bark TTS model download and loading
- End-to-end speech synthesis pipeline testing
- Audio sample generation and transcription verification

**Status:**
- ✅ **Whisper STT**: Fully functional on Android with tiny/base models
- ⚠️ **Bark TTS**: Model loads successfully, but f16 inference is too slow for real-time use on mobile (10+ minutes vs 5 seconds on desktop)

## System Requirements

### Minimum Requirements
- Android SDK 21+ (Lollipop)
- 3GB RAM for basic LLM inference
- 500MB free storage for model caching
- 1GB+ free storage for speech models

### Recommended Configuration
- Android 11+ (API 30) for Vulkan acceleration
- 8GB RAM for Stable Diffusion
- 12GB+ RAM for video generation (Wan models)
- 5GB free storage for video model pipeline

### Speech Model Requirements
- **Whisper STT**: 75MB-500MB depending on model size (tiny to small)
- **Bark TTS**: 843MB+ for f16 models (quantized not yet available)

### Development Environment
- Android SDK with NDK r27+
- CMake 3.22+
- Java 17+
- Gradle 8.0+ (wrapper included)

## Building the Application

### Standard Build Process

From the repository root directory:

1. Build the llmedge library:
```bash
./gradlew :llmedge:assembleRelease
```

2. Copy the AAR to the examples project:
```bash
cp llmedge/build/outputs/aar/llmedge-release.aar llmedge-examples/app/libs/llmedge-release.aar
```

3. Build the example application:
```bash
cd llmedge-examples
./gradlew :app:assembleDebug
```

4. Install to device:
```bash
./gradlew :app:installDebug
```

### Vulkan-Enabled Build

For GPU-accelerated inference on Android 11+ devices:

```bash
./gradlew :llmedge:assembleRelease \
  -Pandroid.jniCmakeArgs="-DGGML_VULKAN=ON -DSD_VULKAN=ON"

cp llmedge/build/outputs/aar/llmedge-release.aar llmedge-examples/app/libs/llmedge-release.aar

cd llmedge-examples
./gradlew :app:assembleDebug :app:installDebug
```

**Note**: Vulkan builds require devices with Vulkan 1.2 support (Android 11+).

## Asset Configuration

### Bundled GGUF Models

Place small GGUF models in `app/src/main/assets/` for offline-first demos:

```
app/src/main/assets/
              └── models/
                  └── smolm2-360M-instruct.gguf
```

Recommended models for bundling:
- SmolLM2-360M-Instruct (~200MB)
- Qwen2-0.5B-Instruct (~300MB)
- TinyLlama-1.1B (~600MB)

### RAG Embeddings

The RAG demo requires ONNX embedding models:

```
app/src/main/assets/
              └── embeddings/
                  └── all-minilm-l6-v2/
                      ├── model.onnx
                      └── tokenizer.json
```

Download from: `sentence-transformers/all-MiniLM-L6-v2` on Hugging Face

### Runtime Model Cache

Models downloaded via Hugging Face are cached at:
```
<app_private_dir>/files/hf-models/<repo>/<revision>/<filename>
```

Cache persists across app restarts and is reused automatically.

## Usage Examples

### Basic LLM Inference

```kotlin
val smol = SmolLM()

CoroutineScope(Dispatchers.IO).launch {
    smol.loadFromHuggingFace(
        context = context,
        modelId = "unsloth/Qwen3-0.6B-GGUF",
        filename = "Qwen3-0.6B-Q4_K_M.gguf"
    )

    val response = smol.getResponse("Explain quantum computing concisely.")
    withContext(Dispatchers.Main) {
        textView.text = response
    }

    smol.close()
}
```

### RAG Pipeline

```kotlin
val smol = SmolLM()
val rag = RAGEngine(context, smol)

CoroutineScope(Dispatchers.IO).launch {
    rag.init()
    val chunks = rag.indexPdf(pdfUri)
    val answer = rag.ask("What are the main conclusions?")

    withContext(Dispatchers.Main) {
        resultView.text = answer
    }
}
```

### Speech-to-Text (Whisper)

```kotlin
import io.aatricks.llmedge.LLMEdgeManager

CoroutineScope(Dispatchers.IO).launch {
    // Simple transcription
    val text = LLMEdgeManager.transcribeAudioToText(
        context = context,
        audioSamples = audioSamples  // 16kHz mono PCM float32
    )

    // Full transcription with timing
    val segments = LLMEdgeManager.transcribeAudio(
        context = context,
        params = LLMEdgeManager.TranscriptionParams(
            audioSamples = audioSamples,
            language = "en"
        )
    ) { progress ->
        Log.d("Whisper", "Progress: $progress%")
    }

    withContext(Dispatchers.Main) {
        segments.forEach { segment ->
            textView.append("[${segment.startTimeMs}ms] ${segment.text}\n")
        }
    }
}
```

### Real-time Streaming Transcription

For live captioning from a microphone:

```kotlin
import io.aatricks.llmedge.LLMEdgeManager

class LiveCaptionActivity : AppCompatActivity() {
    private var transcriber: Whisper.StreamingTranscriber? = null

    fun startLiveCaptions() {
        lifecycleScope.launch(Dispatchers.IO) {
            // Create streaming transcriber with sliding window
            transcriber = LLMEdgeManager.createStreamingTranscriber(
                context = this@LiveCaptionActivity,
                params = LLMEdgeManager.StreamingTranscriptionParams(
                    stepMs = 3000,      // Process every 3 seconds
                    lengthMs = 10000,   // 10-second windows
                    language = "en",
                    useVad = true       // Skip silent audio
                )
            )

            // Collect transcription results
            transcriber?.start()?.collect { segment ->
                withContext(Dispatchers.Main) {
                    captionTextView.text = segment.text
                }
            }
        }
    }

    // Feed audio from microphone (called by AudioRecord callback)
    fun onAudioData(samples: FloatArray) {
        lifecycleScope.launch(Dispatchers.IO) {
            transcriber?.feedAudio(samples)
        }
    }

    fun stopLiveCaptions() {
        transcriber?.stop()
        LLMEdgeManager.stopStreamingTranscription()
    }
}
```

### Text-to-Speech (Bark)

> **Note:** Bark TTS with f16 models is very slow on mobile (~10+ minutes). Best suited for desktop/server use.

```kotlin
import io.aatricks.llmedge.LLMEdgeManager

CoroutineScope(Dispatchers.IO).launch {
    // Generate speech
    val audio = LLMEdgeManager.synthesizeSpeech(
        context = context,
        params = LLMEdgeManager.SpeechSynthesisParams(
            text = "Hello, world!"
        )
    ) { step, progress ->
        Log.d("Bark", "${step.name}: $progress%")
    }

    // Or save directly to file
    val outputFile = File(context.cacheDir, "output.wav")
    LLMEdgeManager.synthesizeSpeechToFile(
        context = context,
        text = "Hello, world!",
        outputFile = outputFile
    )

    // Unload when done
    LLMEdgeManager.unloadSpeechModels()
}
```

### Image Generation

```kotlin
val sd = StableDiffusion.load(
    context = this,
    modelId = "Meina/MeinaMix",
    filename = "MeinaPastel-v6-baked-vae.safetensors",
    offloadToCpu = true
)

val bitmap = sd.txt2img(
    StableDiffusion.GenerateParams(
        prompt = "serene mountain landscape, sunset",
        width = 512,
        height = 512,
        steps = 20,
        cfgScale = 7.0f
    )
)

imageView.setImageBitmap(bitmap)
sd.close()
```

### Video Generation

```kotlin
// Check device compatibility
val activityManager = getSystemService(ACTIVITY_SERVICE) as ActivityManager
val memInfo = ActivityManager.MemoryInfo()
activityManager.getMemoryInfo(memInfo)
val totalRamGB = memInfo.totalMem / (1024.0 * 1024.0 * 1024.0)

if (totalRamGB < 12.0) {
    showError("Video generation requires 12GB+ RAM")
    return
}

// Download all three required components
val modelFile = HuggingFaceHub.ensureRepoFileOnDisk(
    context, "Comfy-Org/Wan_2.1_ComfyUI_repackaged", "main",
    "wan2.1_t2v_1.3B_fp16.safetensors"
)

val vaeFile = HuggingFaceHub.ensureRepoFileOnDisk(
    context, "Comfy-Org/Wan_2.1_ComfyUI_repackaged", "main",
    "wan_2.1_vae.safetensors"
)

val t5xxlFile = HuggingFaceHub.ensureRepoFileOnDisk(
    context, "city96/umt5-xxl-encoder-gguf", "main",
    "umt5-xxl-encoder-Q3_K_S.gguf"
)

// Load with explicit paths
val sd = StableDiffusion.load(
    context = this,
    modelPath = modelFile.file.absolutePath,
    vaePath = vaeFile.file.absolutePath,
    t5xxlPath = t5xxlFile.file.absolutePath,
    offloadToCpu = true,
    keepClipOnCpu = true,
    keepVaeOnCpu = true
)

val frames = sd.txt2vid(
    StableDiffusion.VideoGenerateParams(
        prompt = "cat walking through garden",
        videoFrames = 8,
        width = 256,
        height = 256,
        steps = 20
    )
)

sd.close()
```

## Performance Optimization

### Memory Management

**Monitor Memory Usage**:
```kotlin
val snapshot = MemoryMetrics.snapshot(context)
Log.d("Memory", "Native heap: ${snapshot.nativePssKb / 1024}MB")
```

**Optimization Strategies**:
- Use quantized models (Q4_K_M) for lower memory footprint
- Enable CPU offloading for large models
- Close model instances when not in use
- Process images/video in batches with intermediate cleanup

### Thread Configuration

```kotlin
val params = SmolLM.InferenceParams(
    numThreads = Runtime.getRuntime().availableProcessors(),
    contextSize = 2048  // Adjust based on device RAM
)
```

### Vulkan Acceleration

Verify Vulkan availability:
```kotlin
if (SmolLM.isVulkanEnabled()) {
    Log.i("Performance", "Vulkan backend active")
} else {
    Log.w("Performance", "Falling back to CPU backend")
}
```

Check logcat for initialization:
```bash
adb logcat -s SmolLM:* SmolSD:* | grep -i vulkan
```

## Troubleshooting

### Model Loading Failures

**Symptoms**: `FileNotFoundException`, `IllegalStateException` during load

**Solutions**:
- Verify model file exists in expected location
- Check available storage space
- Ensure network connectivity for Hugging Face downloads
- Validate model file integrity (not corrupted)

### Out of Memory Errors

**Symptoms**: App crashes with OOM during inference or generation

**Solutions**:
- Use smaller models or quantized variants
- Reduce image/video resolution
- Enable CPU offloading: `offloadToCpu = true`
- Lower context window size
- Close unused model instances

### Slow Inference Performance

**Symptoms**: Generation takes excessive time per token/frame

**Solutions**:
- Use quantized models (Q4_K_M, Q3_K_S)
- Reduce inference steps (15-20 is usually sufficient)
- Enable Vulkan on compatible devices
- Adjust thread count to match device cores
- Use smaller resolutions for media generation

### Video Generation Failures

**Symptoms**: Crashes or errors when loading Wan models

**Solutions**:
- Verify device has 12GB+ RAM
- Ensure all three files downloaded (main + VAE + T5XXL)
- Use explicit file paths (not modelId shorthand)
- Check stable-diffusion.cpp logs in logcat
- Verify sufficient storage for 6GB+ model files

### Native Library Issues

**Symptoms**: `UnsatisfiedLinkError`, native crashes

**Solutions**:
- Rebuild AAR and reinstall app
- Verify NDK version matches (r27+)
- Check device ABI compatibility
- Inspect logcat for native stack traces
- Clean build: `./gradlew clean`

### Speech Processing Issues

**Symptoms**: Bark TTS taking 10+ minutes to generate audio

**Explanation**: This is expected behavior with f16 models on mobile. Bark.cpp uses full-precision weights which are computationally intensive on ARM CPUs.

**Workarounds**:
- Use Bark for desktop/server batch processing only
- Wait for quantized models in combined ggml format (not yet available)
- For real-time TTS on mobile, consider alternative solutions

**Symptoms**: Whisper transcription crashing or producing garbled output

**Solutions**:
- Ensure audio is 16kHz mono PCM float32 format
- Use smaller models (tiny/base) for faster processing
- Check that model file downloaded completely

## Testing Infrastructure

### Speech E2E Testing

Run speech tests via adb:
```bash
adb shell am instrument -w -e class com.example.llmedgeexample.SpeechE2ETest \
  com.example.llmedgeexample.test/androidx.test.runner.AndroidJUnitRunner
```

**Test coverage:**
- `testWhisperModelDownloadAndLoad` - Downloads and loads Whisper model ✅
- `testWhisperTranscription` - Transcribes audio samples ✅
- `testWhisperSystemInfo` - Validates model info ✅
- `testBarkModelDownloadAndLoad` - Downloads and loads Bark model ✅
- `testFullSpeechPipeline` - Skipped on mobile (too slow with f16)

### Headless E2E Testing

Run automated video generation tests:

```bash
adb shell am start -n com.example.llmedgeexample/.HeadlessVideoTestActivity
```

Monitor test execution:
```bash
adb logcat -s VideoE2E:*
```

Test results are logged to logcat with detailed timing and validation metrics.

## Architecture Notes

### Memory Architecture
- Native models allocated via JNI in native heap
- Dalvik heap used only for Java objects and bitmaps
- Large file downloads use system DownloadManager
- Tensor operations execute in native memory space

### Threading Model
- All model operations run on background threads (Dispatchers.IO)
- UI updates dispatched to Main thread
- Blocking calls avoided on UI thread
- Coroutines used for structured concurrency

### Resource Lifecycle
- Models implement `AutoCloseable` for automatic cleanup
- Native resources freed via `close()` method
- File handles managed with try-with-resources pattern
- Memory mapped files used for large model loading

## License

Apache 2.0 - See LICENSE file for details

## Contributing

Contributions welcome. Please review the main repository's contributing guidelines before submitting pull requests.
