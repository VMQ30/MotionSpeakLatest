# LLM Sentence Synthesis — Design

> Status: proposed design for the `feature/llm-sentence-synthesis` branch.
> Implementation plan: [docs-internal/plans/2026-10-23-llm-sentence-synthesis.md](../plans/2026-10-23-llm-sentence-synthesis.md)

## 1. Problem

Today the translator appends recognised words one by one:

```text
HOSPITAL NEED HELP NOW
```

That is understandable but unnatural — no grammar, no word order, and none of
the emotional context a face carries while signing. The caller has to reassemble
the meaning themselves.

Meanwhile the branch already detects facial emotion on-device
(`EmotionClassifier`), but the result is currently computed and dropped to a log
line (`MainActivity.kt:374–390`).

## 2. Feature statement

While translating, the app **composes a full, natural sentence** from the latest
stable word predictions plus the detected facial tone:

```text
Words:   [hospital, need, help, now]   Tone: concerned
Output:  "I need help at the hospital right now. Please come."
```

- **Language toggle**: English or Filipino, matching the word bank's dual
  language (per-word Filipino meanings).
- **Trigger**: automatic — a short pause in signing ends one thought;
  no extra button required.
- **Runtime**: on-device LLM (Gemma), keeping the app's fully-offline promise.
- **Modularity**: the LLM is an optional, downloadable model; the app remains
  fully functional without it (PassThrough fallback renders today's behavior).

## 3. Current state (what this branch already has)

| Piece | File | State |
|---|---|---|
| Word stream | `PredictionService.kt` — `onResult(label)` callback; `MainActivity.showResult` appends to `tvMessage` with 1 s same-label dedupe | shipped |
| Face emotion | `EmotionClassifier.kt` — `FaceLandmarker` + `hsemotion_b0_fp16.tflite`; `predictTone()` → `(label, confidence)`; runs on a 500 ms cadence in the camera analyzer (`MainActivity.kt:374–390`) | committed |
| Tone normalisation | `ToneMapper.kt` — 8 HSEmotion labels → 4 tones (`positive`, `concerned`, `negative`, `neutral`); doc comment already says "for the LLM" | committed, **output currently unused** |
| Model download plumbing | `ModelUpdateManager.kt` — download/verify/swap of `.tflite` bundles + word bank sync | shipped |
| Settings | `AppSettings.kt` (`showFilipino`, `isFrontCamera`,…) | shipped; **new keys needed** |
| Translation history | `TranslationHistoryManager` — 200-entry cap | shipped; sentence vs raw words recording is a Phase-1 decision |

## 4. Architecture

```text
PredictionService.onResult ──► SentenceComposer ──► SentenceSynthesizer ──► UI bubble + TTS
                                    ▲
ToneMapper (exists)  ───────────────┘  tone snapshot taken at compose time
```

### 4.1 `SentenceSynthesizer` interface (new, pure Kotlin)

```kotlin
interface SentenceSynthesizer {
    /** Only suspends when composing is smart (LLM). PassThis returns immediately. */
    suspend fun compose(words: List<String>, tone: Tone, language: Language): SentenceResult
    suspend fun close()
}
```

Three implementations:

1. **`PassThroughSynthesizer`** — deterministic glue: `capitalize(words.joinToString(" "))`.
   Identical to today's output (minus per-word appending). Always available;
   default until the LLM is downloaded; explicit fallback on failure paths;
   test baseline in unit tests.
2. **`LlmSynthesizer`** — wraps MediaPipe LLM Inference (`com.google.mediapipe:tasks-genai`)
   with a Gemma 3 1B int4 `.task`. Strict prompt template, temperature 0 so the
   same input yields the same sentence (required for deterministic tests).
3. **`RemoteSynthesizer`** (Phase 3 optional/future) — POSTs words + tone to a
   backend `/api/sentence` endpoint that proxies a cloud LLM. Explicitly a
   fallback for "capable phone, model not downloaded" — it requires internet
   and is off by default.

### 4.2 `SentenceComposer` (the organizer)

- Rolling window over stable word predictions (cap N=8, tunable constant).
- Pause detection: no new stable prediction for `PAUSE_MS = 2000` (tunable)
  ends one thought → compose fires.
- Grabs the **tone** registered by the existing emotion loop (`ToneMapper`).
- Compose cancellation: a fire supersedes the previous unresolved one;
  words arriving mid-compose reset the pause timer.
- Mode-aware: letters mode and single-word taps still work exactly as today
  (PassThrough renders per-word output; composer only enriches the running
  stream view).

### 4.3 Device fitness gating (`LlmFeatureGate`)

The LLM path activates only when **all** hold:

- on-device Gemini/Gemma runtime requirements listed by the `tasks-genai` AAR
  (OS version + free/RAM thresholds — **verify exact values at build time**;
  historical guidance: ≥4 GB RAM),
- arm64 primary ABI (matches the app's existing `abiFilters`),
- the user downloaded the optional Gemma model via the model-update flow.

Any gate miss ⇒ factory returns `PassThroughSynthesizer`. Failure of a compose
call once active ⇒ log + degrade that call to PassThrough text. **The feature
can only get smarter, never broken.**

### 4.4 Languages

- English: word labels map 1:1 (the recognition labels are English-canonical).
- Filipino: each label is anchored to the word bank's Filipino meaning
  (`ModelUpdateManager.loadCachedWordBank`); the prompt pins label → Filipino
  replacement so the sentence stays faithful to known vocabulary. Fallback for
  missing Filipino entries: use the raw label.
- Chosen language is the *composer prompt* setting (`AppSettings`), not the
  display-language flag (`showFilipino`), so a hearing companion can pick a
  sentence language independent of the visible word-bank row.

### 4.5 Delivery of the LLM model (modular, like the other models)

- File: Gemma 3 1B-4bit `.task` (≈500–700 MB), downloaded once via the
  **existing** `ModelUpdateManager` pattern: new model kind `llm`, version metadata,
  SHA-256 verification, progress UI in Settings.
- Storage: new Supabase bucket `llm-models` (separate from `model-files` so
  600 MB binaries never mix with gesture models).
- Backend: Phase 3 — `model_versions.model_kind` gains `"llm"` (migration 013
  + CHECK constraint update), an admin upload/manage page, then `GET /api/models/llm/latest`.
- The AAR ships only when the translator screen is first used (avoid bloating
  cold start; camera models are already deferred the same way — see
  `HomeActivity.kt:57`).

## 5. Constraints (inherited)

- **Offline-first**: composing must work with airplane mode once the model is
  downloaded. Internet is needed only for the one-time Gemma download.
- **No regression**: words mode and letters mode behave as today when the LLM
  is absent/disabled. PassThrough output must equal today's `tvMessage` string.
- **64 KB pages / ABIs**: `tasks-genai` natives must be 16 KB-page aligned and
  arm64-compatible (this repo's API-37 emulator crash class proves these
  realities bite — see `abiFilters` comment in `app/build.gradle.kts`).
- **Determinism for tests**: temperature 0, strict template, fixed few-shot block.

## 6. Open questions (to resolve where marked "Verify")

| # | Question | Resolution plan |
|---|---|---|
| Q1 | Exact `com.google.mediapipe:tasks-genai` version compatible with the existing `tasks-vision:0.10.14` + Kotlin/AGP stack | Verify in Phase 2 Task 0 (Maven Central + docs), pin in gradle |
| Q2 | Gemma 3 1B int4 `.task` file acquisition & license acceptance (Kaggle) | **Manual step**; one-off per team; document bucket upload path |
| Q3 | `tasks-genai` minimum Android version + RAM threshold for on-device runtime | Verify against official MediaPipe GenAI docs (Phase 2) |
| Q4 | Record composed sentence or raw words in `TranslationHistoryManager`? | Phase 1 decision recording both fields (backward-compatible write), display unchanged |
| Q5 | Is a `model_versions` row needed per Gemma version (admin re-train not applicable — Google-hosted)? | Prefer a minimal `llm_versions` mirror table so admin keeps one UI |

## 7. Risks

| Risk | Mitigation |
|---|---|
| Prompt brittleness → wrong/injected text | Closed vocabulary + tone enum only; strict template; output length cap; no free-form input |
| Latency (first compose after warm-up 1–3 s) | Paused design means compose happens exactly when the user is done; warm the interpreter during the pause, not before |
| Battery/thermal | Interpreter alive only while the translator screen is active (mirrors camera-model lifetime pattern) |
| Model file leaves stale state after app update | Versioned metadata + re-download flow via ModelUpdateManager |
