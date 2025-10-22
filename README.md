# llmedge Example App — demos, how they work, and how to run them

This small Android example app demonstrates how the `llmedge` library is used in real demos bundled with the repo. The app is intentionally compact so you can quickly inspect how to load models (local or from Hugging Face), run on-device RAG, and wire a Stable Diffusion txt2img flow.

Find the main `llmedge` library in the parent repo: https://github.com/Aatricks/llmedge

## Demos included (activity names)

- Local Asset Demo — `LocalAssetDemoActivity.kt`
  - Copies a GGUF bundled in `app/src/main/assets/` into app-private storage and loads it via `SmolLM.load`.
  - Demonstrates blocking load, simple inference, and streaming output.

- Hugging Face Demo — `HuggingFaceDemoActivity.kt`
  - Downloads a GGUF from the Hugging Face hub (or reuses a cached copy) and loads it via `SmolLM.loadFromHuggingFace`.
  - Shows download progress callbacks and the cache layout under app `filesDir`.

- Retrieval-Augmented Generation (RAG) Demo — `RagActivity.kt`
  - Demonstrates indexing and retrieval using provided embeddings (bundled under `assets/embeddings/`) plus final answer generation with `SmolLM`.

- OCR and Vision capable LLM Demo — `ImageToTextActivity.kt`, `LlavaVisionActivity.kt`
  - Demonstrates loading a vision-capable LLM and performing OCR and description on images using the model's vision capabilities or Google ML Kit.
  - Shows how to prepare image inputs and handle multimodal outputs.

- Stable Diffusion (txt2img) Demo — `StableDiffusionActivity.kt`
  - Optional heavy demo that downloads required VAE/aux assets when missing and runs a small `txt2img` workload via the `SmolSD` wrapper.
  - Exposes runtime options to offload large tensors to CPU to avoid native heap OOMs.

## Quick prerequisites

- Android SDK + NDK r27+ (NDK r27 used during development)
- CMake 3.22+
- Java 11+
- Gradle (use the included Gradle wrapper in this repo)
- Optional: a modern Android device (Android 11 / API 30+) if you want to test Vulkan acceleration

If you plan to run the Stable Diffusion demo on-device, prefer a device with ample RAM and consider enabling CPU offloads in the demo settings.

## Build & install (recommended flow)

From the repository root:

1) Build the `llmedge` AAR and copy it into the example app:

```bash
./gradlew :llmedge:assembleRelease
cp -f llmedge/build/outputs/aar/llmedge-release.aar llmedge-examples/app/libs/llmedge-release.aar
```

2) Build and install the example app (run from `llmedge-examples`):

```bash
./gradlew :app:assembleDebug
./gradlew :app:installDebug
```

3) Launch the app on your device and choose a demo from the launcher.

Note: If you modify native build flags (e.g. enabling Vulkan) rebuild the AAR before reinstalling the example app so the APK includes matching native libraries.

## Where to put models and assets

- Bundled local GGUF models: place your GGUF (like [smolm2-360M](https://huggingface.co/HuggingFaceTB/SmolLM2-360M-Instruct-GGUF)) in `app/src/main/assets/YourModel.gguf` before building the APK. The Local Asset demo copies that file into the app's private storage on first run and then loads it from there.

- Embeddings & ONNX for RAG: the RAG demo expects small example embeddings and ONNX files under `app/src/main/assets/embeddings/`. The demo will index and query these at runtime.

- Hugging Face downloads (runtime cache): the Hugging Face demo downloads models into the app private cache under `filesDir/hf-models/<repo>/<revision>/` and will reuse cached copies on subsequent runs.

## API & runtime tips

- Loading models (examples):
  - SmolLM.load(filePath, params)
  - SmolLM.loadFromHuggingFace(repo, revision, token?, params)

- Check Vulkan availability at runtime with `SmolLM.isVulkanEnabled()` before enabling Vulkan-specific options in your UI.

- RAG flow: the example shows a minimal pipeline — index documents (embeddings), run a nearest-neighbor search locally, then pass relevant snippets to `SmolLM` to generate an answer. The RAG demo is intentionally small; for production you will want more robust chunking, ranking, and prompt engineering.

## Stable Diffusion notes

- The Stable Diffusion demo will download required auxiliary assets (VAE, etc.) if missing; downloads are cached under the app's files directory.
- Runtime options exposed in the demo (see `StableDiffusionActivity.kt`):
  - `offloadToCpu` — offload large tensors to host memory to reduce native heap usage.
  - `keepClipOnCpu` — keep CLIP on CPU to reduce GPU memory usage.
  - `keepVaeOnCpu` — keep the VAE on CPU if required to fit memory constraints.

- If you hit OOMs during generation: lower `width`/`height`, reduce `steps`, use CPU offloads, or use a smaller model.

## Vulkan and performance

- To build with Vulkan support enable the proper cmake flags when assembling the AAR (native CMake flags used in the library build): `-DSD_VULKAN=ON -DGGML_VULKAN=ON`.
- Runtime checks:
  - Device: Android 11+ (API 30) with Vulkan 1.2-capable GPU.
  - Call `SmolLM.isVulkanEnabled()` from Kotlin to verify the native backend reports Vulkan availability.
  - Inspect `logcat` for `SmolSD` / `SmolLM` messages and ensure there are no "Failed to initialize Vulkan backend" errors.

## Troubleshooting

- If a model fails to load or you see native crashes, rebuild the AAR and ensure the native ABIs in the AAR match the device ABI.
- Verify runtime permissions for storage/network if downloads fail in the Hugging Face demo.
- For Stable Diffusion, try smaller image sizes or enable offloads if you hit memory errors.

## Notes

- Hugging Face downloads are cached under `filesDir/hf-models/<repo>/<revision>/` for reuse.
- Tune `numThreads` in `InferenceParams` for your device to balance latency vs throughput.

---

If you'd like, I can also add a short code snippet showing exact Kotlin calls used by each demo or update the in-app strings/layouts to make the demos more discoverable. Just tell me which demo you want more docs for.
