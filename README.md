# llmedge Example App — demos and how they work

This repository contains a tiny Android example app demonstrating the `llmedge` library. It's intentionally small and focused so you can quickly see how to:

- Load a local GGUF model bundled in the APK and run a blocking prompt.
- Download a GGUF from Hugging Face and run inference once the model is available.
- Run the on-device RAG (retrieval-augmented generation) demo.
- Run an on-device Stable Diffusion image generation demo (optional heavy workload).

Find the main `llmedge` library in the parent repo: https://github.com/Aatricks/llmedge

## Demos included

- Local Asset Demo (`LocalAssetDemoActivity`) — copies a model bundled in `app/src/main/assets/` into app-private storage and loads it with `SmolLM.load`. Demonstrates blocking load and streaming text output.
- Hugging Face Demo (`HuggingFaceDemoActivity`) — downloads a GGUF from the Hugging Face hub (or reuses a cached copy) and loads it with `SmolLM.loadFromHuggingFace`. Demonstrates progress callbacks and automatic caching under `filesDir/hf-models/`.
- RAG Demo (`RagActivity`) — shows a small on-device retrieval augmented generation flow using pre-bundled embeddings and `SmolLM` for final answers.
- Stable Diffusion (`StableDiffusionActivity`) — optional demo that downloads required VAE assets from Hugging Face (if missing) and runs a small `txt2img` workload to illustrate how to wire the image generation API.

## Prerequisites

- Android SDK with NDK r27+ installed (NDK r27 used during development).
- CMake 3.22+ (Gradle plugin will pick this up if properly configured).
- Java 11+ and Gradle (use the included wrapper: `./gradlew`).
- Optional: a modern Android device (Android 11 / API 30+) if you want to test Vulkan acceleration.

If you plan to run the Stable Diffusion demo on-device, use a device with ample RAM and consider enabling CPU offloads in the demo settings.

## Build & install

1. Build the library AAR from the parent repo root and copy it into the example app:

```bash
./gradlew :llmedge:assembleRelease
cp -f llmedge/build/outputs/aar/llmedge-release.aar llmedge-examples/app/libs/llmedge-release.aar
```

2. Build and install the example app (run from `llmedge-examples`):

```bash
./gradlew :app:assembleDebug
./gradlew :app:installDebug
```

3. Launch the app on your device and pick a demo from the launcher.

## Per-demo notes

Local Asset Demo
- Place a GGUF model like [smolm2-360M](https://huggingface.co/HuggingFaceTB/SmolLM2-360M-Instruct-GGUF) in `app/src/main/assets/YourModel.gguf` before building the APK. The example copies this file once at first run then loads from app-private storage.

Hugging Face Demo
- The demo downloads models into `filesDir/hf-models/<repo>/<revision>/`. It accepts public or private repos (pass a token through the app settings in the demo).

RAG Demo
- The RAG demo ships with a minimal example embedding/onnx setup in `app/src/main/assets/embeddings/` that the demo indexes and queries. This demonstrates document indexing and retrieval using the `SmolLM` for answer generation.

Stable Diffusion Demo (Image generation)
- The demo downloads a VAE (safetensors) if necessary and runs a small `txt2img` workload. The example demonstrates using `StableDiffusion.load(...)` and `txt2img(...)` — see `StableDiffusionActivity.kt` for the exact flow.
- Example runtime options exposed in the demo:
  - `offloadToCpu` — move large model tensors to host memory to reduce native heap usage.
  - `keepClipOnCpu` — keep CLIP on CPU to reduce GPU usage.
  - `keepVaeOnCpu` — move the VAE to CPU if necessary.

Tips
- If a generation OOM occurs, reduce resolution (`width`/`height`), `steps` or enable offloads.
- The demo falls back to smaller resolutions on OOMs to improve UX — consider the same strategy in your app.

## Vulkan and performance

If you built the AAR with Vulkan support enabled (`-DSD_VULKAN=ON -DGGML_VULKAN=ON`), verify at runtime that Vulkan is actually active:

- Device must be Android 11+ (API 30) with Vulkan 1.2-capable GPU.
- From Kotlin call `SmolLM.isVulkanEnabled()` to check whether the native backend reports Vulkan availability.
- Inspect logcat for `SmolSD` messages to ensure Vulkan initialization succeeded (no "Failed to initialize Vulkan backend" messages).


## Notes
- Hugging Face downloads are cached under `filesDir/hf-models/<repo>/<revision>/` for reuse.
- Tune `numThreads` in `InferenceParams` for your device.
