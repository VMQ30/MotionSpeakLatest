# LLM Sentence Synthesis Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** While the translator is running, compose a full natural sentence (English or Filipino) from the latest stable word predictions plus the facial tone the branch already detects on-device — with a mandatory offline PassThrough fallback so the feature can only ever get smarter, never broken.

**Architecture:** `PredictionService.onResult` word stream feeds a new `SentenceComposer` (rolling window + pause detection + tone snapshot from the existing `ToneMapper`), which calls a new `SentenceSynthesizer` interface. Three implementations: `PassThroughSynthesizer` (today's behavior — default/fallback/test baseline), `LlmSynthesizer` (MediaPipe tasks-genai + Gemma 3 1B int4 `.task`, downloaded once through the existing `ModelUpdateManager` flow, device-fitness gated), `RemoteSynthesizer` (Phase-3 optional backend proxy). Composed output shows in a new sentence bubble on the translator screen and is spoken via the existing TTS path.

**Tech Stack:** Kotlin, CameraX Analyzer, MediaPipe tasks-vision 0.10.14 (existing) + tasks-genai (Phase 2), TFLite 2.14, JUnit 4 · Node.js/Express, Sequelize, `node --test` (Phase 3 only) · Supabase Storage (`llm-models` bucket).

**Spec:** [docs-internal/specs/2026-10-23-llm-sentence-synthesis-design.md](../specs/2026-10-23-llm-sentence-synthesis-design.md)

## Global Constraint

- **No regression.** Words mode and letters mode behave exactly as today when the LLM is absent, disabled, or unsupported. `PassThroughSynthesizer.compose(words)` must equal today's appended `tvMessage` string (capitalisation + spaces).
- **Offline-first.** Composing works in airplane mode once the Gemma file is downloaded. Internet is only for the one-time model download.
- **Feature can only get smarter, never broken**: any gate miss or compose failure degrades to PassThrough for that call. No crash path.
- **Determinism**: LLM prompt is temperature 0 with a fixed few-shot block; same words + tone ⇒ same sentence string (unit-testable).
- Constants pinned by tests (must match Python-side nothing — this feature is mobile-first): `PAUSE_MS=2000`, `WINDOW_MAX=8`, `DEDUPE_MS=1000` (unchanged from today), sentence max length guard.
- Repo dirs are `motionspeak-*` (mobile/backend/ml/admin); paths below are relative to repo root.
- Commands: app tests `cd motionspeak-mobile && gradlew.bat :app:testDebugUnitTest`; backend tests `cd motionspeak-backend && npm test` (Phase 3).
- **Branch**: `feature/llm-sentence-synthesis` — **already created** off `feature/fsl-and-emotion-latest-version2` @ `bc5c37ed`. Work here, not on that base branch and not on main.

## Review Focus

1. **LLM absent/uninstalled/unsupported** — app identical to today; no crash, no toast spam, PassThrough everywhere. (Phase 1 + Phase 2 Task 4)
2. **16 KB-page / ABI safety for `tasks-genai`** — verify the AAR handles 16 KB pages (the class of `UnsatisfiedLinkError` seen on the API 37 emulator with old `tasks-vision`). (Phase 2 Task 0)
3. **Mid-compose word arrival** — new words never get lost: they reset the pause timer and are included in the NEXT compose; the in-flight compose is cancelled. No duplicated words across consecutive sentences. (Task 5 tests)
4. **Tone race** — the emotion classifier runs on a different cadence; composing must use the *last known* tone snapshot (never block on emotion thread). (Task 3)
5. **Language toggle correctness** — Filipino sentence must keep known word-bank vocabulary anchored; unknown label falls back to raw label. (Task 7)
6. **History semantics** — record the composed sentence without breaking the 200-entry cap or existing History UI. (Task 6 — decision made at implementation)
7. **Model download lifecycle** — app update / cache wipe must not leave a stale `.task` referenced by `meta.json`. (Phase 2 Task 2)

---

## File map

**motionspeak-mobile** (`app/src/main/kotlin/com/example/motionspeak/`)
- Create `sentence/SentenceSynthesizer.kt` (interface + `SentenceResult` + `Tone` + `Language` value types)
- Create `sentence/PassThroughSynthesizer.kt`
- Create `sentence/SentenceComposer.kt`
- Create `sentence/LlmFeatureGate.kt` (Phase 2: device + download gating)
- Create `sentence/LlmSynthesizer.kt` (Phase 2; wraps tasks-genai) and `sentence/Prompts.kt` (templates, EN/FIL)
- Create `sentence/*Test.kt` under `app/src/test/kotlin/com/example/motionspeak/`
- Modify `MainActivity.kt` — feed composer from `onResult`/analyzer; render sentence bubble
- Modify `ToneMapper.kt` — expose `Tone` value type (behavior unchanged)
- Modify `activity_main.xml` — add sentence bubble + "composing…" row (VISIBLE only when composing enabled)
- Modify `AppSettings.kt` — `llmEnabled`, `llmLanguage("en"|"fil")`, `composePauseMs`
- Modify `activity_settings.xml` + settings screen — new toggles (Phase 1)
- Modify `ModelUpdateManager.kt` (Phase 2) — `model_kind="llm"` download/verify/activate flow
- Modify `TranslationHistoryManager.kt` (Phase 1 decision) — record composed text field
- Modify `app/build.gradle.kts` (Phase 2) — `com.google.mediapipe:tasks-genai:<verified>` (keep `abiFilters` as-is)

**motionspeak-backend** (Phase 3)
- Create `migrations/013_model_versions_llm_kind.sql` (extend `model_kind` values incl. `"llm"`; CHECK constraint update)
- Modify `migrations/README.md`, `src/models/ModelVersion.js`, `src/controllers/modelController.js` (route for LLM model upload/list/activate reusing existing admin auth)
- Docs: add Phase-3 endpoints summary + `llm-models` bucket env var (`SUPABASE_BUCKET_LLM_MODELS`)

**motionspeak-admin** (Phase 3)
- Modify "Manage Model" — LLM model upload/list/activate tab (reuses existing model management patterns; NO new admin auth)

**motionspeak-ml** — **untouched** (no training pipeline for Gemma; it is a Google-hosted pretrained model)

---

# Task 0 — Branch & docs (DONE BEFORE THIS PLAN LANDED)

- [x] Branch `feature/llm-sentence-synthesis` created from `feature/fsl-and-emotion-latest-version2` @ `bc5c37ed` (keeps EmotionClassifier + ToneMapper groundwork in the feature lineage; not from main)
- [x] This spec + plan committed as the branch's first commits
- [ ] `git push -u origin feature/llm-sentence-synthesis` — **manual step**: may prompt for git credentials interactively
- [ ] Per-machine (never commit): flip `BASE_URL` in `app/build.gradle.kts` to `http://10.0.2.2:8080/api/` for emulator development; the branch ships `https://siglaph.online/api/` (deployment default)

# Phase 1 — Sentence skeleton (no LLM, no downloads, jumpstart for the team)

### Task 1: Value types + `SentenceSynthesizer` interface (Kotlin)

**Files:**
- Create: `app/src/main/kotlin/com/example/motionspeak/sentence/SentenceSynthesizer.kt`
- Test: `app/src/test/kotlin/com/example/motionspeak/sentence/SentenceSynthesizerContractTest.kt`

**Interfaces:**
- `enum class Tone { POSITIVE, CONCERNED, NEGATIVE, NEUTRAL }` (mirrors `ToneMapper` output)
- `enum class Language { EN, FIL }`
- `data class SentenceResult(val text: String, val synthesized: Boolean)`
- `interface SentenceSynthesizer { suspend fun compose(words: List<String>, tone: Tone, language: Language): SentenceResult; suspend fun close() }`
- Contract test: default close() is a no-op; compose must not throw for empty word list; result text non-blank

**Steps:**
- [ ] Write types + interface, kdoc explaining the fallback ladder
- [ ] Contract test asserting crash-free empty input and non-blank output for both `PassThroughSynthesizer` and any later impl (parameterized over list of synthesizers)
- [ ] Run `gradlew.bat :app:testDebugUnitTest --tests "*sentence*"` — green

### Task 2: `PassThroughSynthesizer` — today's behavior as a synthesizer

**Files:**
- Create: `app/src/main/kotlin/com/example/motionspeak/sentence/PassThroughSynthesizer.kt`
- Test: `app/src/test/kotlin/com/example/motionspeak/sentence/PassThroughSynthesizerTest.kt`

**Interfaces:**
- `compose()` returns `SentenceResult(words.joinToString(" ").replaceFirstChar { it.uppercase() }.ifBlank { "" }, synthesized=false)` — zero latency, never throws

**Steps:**
- [ ] Implementation exactly as above (mirror `showResult` concatenation semantics at `MainActivity.kt:241–242`)
- [ ] Unit tests: capitalisation, blank input, single word, tones ignored, language ignored (PassThrough is language-independent)
- [ ] Test that `compose` completes without any suspend machinery (fast path, no executor involvement)

### Task 3: `SentenceComposer` — window, pause detection, tone snapshot

**Files:**
- Create: `app/src/main/kotlin/com/example/motionspeak/sentence/SentenceComposer.kt`
- Test: `app/src/test/kotlin/com/example/motionspeak/sentence/SentenceComposerTest.kt`

**Interfaces:**
- `class SentenceComposer(synthesizer: SentenceSynthesizer, pauseMs: Long = 2000, windowMax: Int = 8, scope: CoroutineScope)`
- `fun onWord(word: String)` — appends (dedupe rules stay in MainActivity as today), restarts pause timer, schedules compose
- `fun onTone(tone: Tone)` — stores latest snapshot (call from the existing emotion loop once Phase 1 wires it)
- `fun reset()` — on mode switch/teardown: clears window + cancels pending jobs
- Emits result via a listener interface (or `StateFlow<SentenceUiState>`) — UI agnostic
- Injector-friendly timing (use injected `timeSource: () -> Long` so tests don't sleep)

**Steps:**
- [ ] Implement virtual-time-based pause detection (use `runTest` + `advanceTimeBy` so tests are instant, no `Thread.sleep`)
- [ ] Window cap: appending the 9th word chooses the newest 8 — pinned by unit test
- [ ] New word during an in-flight compose cancels it (coroutine cancellation) and owns the emitted result — Review Focus #3
- [ ] Tone snapshot read-only race safety: `@Volatile` snapshot store; last-known tone used even if emotion thread stalls — Review Focus #4
- [ ] Duplicate-word semantics: composer does NOT dedupe (that is MainActivity's 1 s rule, unchanged); tests assert no cross-sentence duplicates though — i.e., after compose fires, window clears

### Task 4: Wire the composer into the translator screen (behavior-preserving)

**Files:**
- Modify: `MainActivity.kt` (after `onResult` wiring around L203), `ToneMapper.kt` (expose `Tone`), `app/src/main/res/layout/activity_main.xml`
- Test: none (instrumentation stays light; conductor tests in Task 5 cover logic)

**Interfaces:**
- MainActivity owns `sentenceComposer`; `onResult` keeps its existing behavior 100% (append + speak + history) AND calls `composer.onWord(label)`
- Emotion analyzer block (currently `MainActivity.kt:374–390`) additionally calls `composer.onTone(ToneMapper.fromEmotion(label))` — the tone becomes live instead of only logged
- UI: new `tvSentence` bubble BELOW `tvMessage`; hidden while empty; shows the composed sentence only when it differs from the raw text (PassThrough composition of single-word/tap flows would look identical to the raw line — leave the bubble empty in that case to avoid duplicated-looking text)

**Steps:**
- [ ] `ToneMapper.fromEmotion` returns `Tone` values (internal mapping unchanged)
- [ ] Bubble: plain TextView + pill background following `values/colors_sigla.xml`; "composing…" placeholder state exists but is instant with PassThrough (it becomes visible only once the LLM synthesizer is active in Phase 2)
- [ ] Mode switches (Words/Letters) and teardown call `composer.reset()` — safe against stale subscriptions since the composer clears its own jobs (Review Focus #7)
- [ ] Manual emulator pass: signing produces words as today + the sentence bubble with PassThrough text after each ≥2 s pause; airplane mode unaffected (PassThrough)

### Task 5: Composer timing/cancellation tests (virtual clock)

**Files:**
- Test: `app/src/test/kotlin/com/example/motionspeak/sentence/SentenceComposerTest.kt` (extends Task 3)

**Steps:**
- [ ] fires exactly at pauseMs after the last word (virtual clock)
- [ ] does NOT fire while words keep arriving (< pauseMs gaps)
- [ ] word arriving during compose cancels the in-flight one (next result contains it)
- [ ] `reset()` cancels everything; no post-reset emissions — Review Focus #3 & #7
- [ ] all tests use `runTest`/virtual time; suite runs < 2 s

### Task 6: History recording decision (Phase 1 finish)

**Files:**
- Modify: `TranslationHistoryManager.kt` + its storage format

**Steps:**
- [ ] Decision recorded here when started by the team: add `composed` column/entry beside raw words (backward-compatible) — display unchanged in the History screen; word text remains the search/index basis
- [ ] After Phase 4 (LLM), History entries written from the composed flow use the new field

### Task 7: Settings toggles (language + feature) — UI-only in Phase 1

**Files:**
- Modify: `AppSettings.kt`, settings screen layout + binding

**Steps:**
- [ ] `llmEnabled` (boolean, default true), `llmLanguage` (EN/FIL toggle; default app language), `composePauseMs` (exposed slider/disclosure — default 2000; hidden behind "Advanced" to keep the screen minimal)
- [ ] Placeholder row for the future "Gemini model download" (Phase 2): visibly states "Model not downloaded — advanced text fallback active" when the gate fails
- [ ] Filipino sentence: full behavior goes live in Phase 2; here the toggle exists and the prompt plumbing compiles against the PassThrough no-op (the toggle has no visible effect until an LLM synthesizer exists — state that plainly in the Settings item subtitle to avoid confusing testers during Phase 1 review)
- [ ] Settings screen unaffected for v2 of words/letters modes; no change to `showFilipino`

# Phase 2 — The LLM module (modular, downloadable, gated)

### Task 0: Dependency verification (do FIRST; result un-blocks Tasks 2–5 — resolves spec open questions Q1/Q2/Q3)

**Artifacts (no code yet — decisions recorded in this plan and in gradle):**
- [ ] Verify current stable `com.google.mediapipe:tasks-genai` version on Maven Central and its compatibility with `tasks-vision:0.10.14` (protobuf/JNI collisions in practice)
- [ ] Verify Android requirements (historical: arm64 only + 16 KB-page support + min OS + RAM guidance ~4 GB); record the json in this task BEFORE writing `LlmFeatureGate`
- [ ] Verify the Gemma 3 1B int4 `.task` acquisition path (Kaggle license acceptance) + exact byte size; the file lands in the storage bucket `llm-models`
- [ ] Record decisions in `sentence/Prompts.kt` kdoc — e.g. "tasks-genai 0.10.x, Gemma 3 1B-4bit-int4, RAM ≥ 4 GB"

### Task 2: `LlmFeatureGate` + download flow via ModelUpdateManager

**Files:**
- Create: `sentence/LlmFeatureGate.kt`
- Modify: `ModelUpdateManager.kt`, settings UI (Sources for download progress)

**Interfaces:**
- `fun evaluate(context): LlmFit { READY, NEEDS_DOWNLOAD, UNSUPPORTED_PREFS/Os }` — screen never crashes from a gate miss
- Download path reuses ModelUpdateManager's progress/verify/restart pattern (model kind `"llm"`), storing SHA-256 + version in `meta.json`

**Steps:**
- [ ] Gate checks: supported ABI, OS level per Task 0 findings, RAM threshold, model file present + hash verified
- [ ] ModelUpdateManager: add `"llm"` handling (do NOT break `"words"`/`"letters"`; download/activate behind dedicated flag)
- [ ] Manual verify: airplane mode + no model ⇒ PassThrough; model exists passes hash check and offers to activate
- [ ] Cancel/re-download path: a truncated download is detected by hash and re-fetched (Review Focus #7)

### Task 3: `LlmSynthesizer` + `Prompts.kt`

**Files:**
- Create: `sentence/LlmSynthesizer.kt`, `sentence/Prompts.kt`
- Test: unit tests for prompt builders (NOT for LLM outputs — those are emulat/device manual passes)

**Interfaces:**
- Builder: system instruction + fixed few-shot block (6–10 EN examples, 6–10 FIL examples), inputs: `words:List<String>, tone:Tone, language:Language`, temperature 0, max output tokens bounded
- Filipino prompt: injected row mapping `label → filipino meaning` from the word-bank cache (fallback: raw label)
- Executor: inference runs on the translator's dedicated executor, interpreter lifetime == screen lifetime (revisit on thermal data)

**Steps:**
- [ ] Prompt golden-file tests: same input ⇒ byte-identical prompt (protects against drift); assert tone is an allowed enum value inside the template; assert injection surface = captured word list join (no user free-form text enters the prompt)
- [ ] `LlmSynthesizer.compose` warm-up on first call then steady-state; on RuntimeException/Timeout ⇒ degrade to PassThrough result for that call (Review Focus #1)
- [ ] Device manual pass (API 34 arm64 emulator + wired signs): latency target < 3 s steady state after warm-up
- [ ] Sentence max length guard: truncate to one sentence, first-sentence period terminated — unit tested at the mock level

### Task 4: Settings + bubble polish for LLM mode

**Steps:**
- [ ] "composing…" placeholder actually shows during LLM compose (PassThrough hides it — instant)
- [ ] Download toggle in Settings (enabled/disabled with reasons from `LlmFeatureGate` evaluation)
- [ ] Language switch applies on the NEXT compose, not adversarially mid-word (documented behavior)

### Task 5: Full behavior matrix manual test

**Matrix:** model absent × enabled/disabled × EN/FIL × online/offline × words/letters modes × tap mode.
Record results in this plan (checkbox per cell) — theFeature must be identical to today's app in every PassThrough cell.

# Phase 3 — Platform round-up (backend + admin + docs)

### Task 1: Migration 013 + backend routes

**Steps:**
- [ ] `migrations/013_model_versions_llm_kind.sql` — extend `model_kind` with `"llm"` (+ adjust CHECK constraint // idempotent)
- [ ] Upload/list/activate route for LLM model versions reusing admin auth + existing `modelController` patterns
- [ ] New env var `SUPABASE_BUCKET_LLM_MODELS` in `.env.example` + README note
- [ ] `npm test` green; migration runs cleanly against Supabase via pooler PG_URI

### Task 2: Admin "Manage Model" LLM tab

**Steps:**
- [ ] LLM model list/activate UI reusing existing model management components
- [ ] Over-the-checks: activating an LLM version does NOT touch words/letters deploy state

### Task 3: Mobile ModelUpdateManager switch to backend metadata (gets released URLs + versioning)

**Steps:**
- [ ] Replace hard-coded/remote bucket URL in the app with the backend GET endpoint (keeps Supabase URLs signed only server-side)
- [ ] Offline/old-server fallback: if the backend endpoint is unreachable, the already-downloaded local file keeps working

### Task 4: Docs
- [ ] Update `docs-internal/plans` cross-links; README feature mention; admin user guide row for the LLM tab
