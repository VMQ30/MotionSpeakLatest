package com.example.motionspeak

import android.app.ActivityManager
import android.content.ContentValues
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.min

/**
 * Prompt data for Test 1 (Single Signs & Expressions) and Test 4 (Breaking Points).
 * Uses standardized emotional states: Neutral, Happy, Sad, Surprise, Fear, Angry, Disgust, Contempt.
 */
data class SingleSignPrompt(
    val gloss: String,
    val translation: String,
    val expression: String = "Neutral",
    val expectedEmotion: String = "Neutral",
    val expectedAffect: String = expectedEmotion
)

/**
 * Prompt data for Test 2 (Full Sentences BLEU evaluation).
 */
data class SentencePrompt(
    val promptText: String,
    val glosses: List<String>,
    val referenceTranslations: List<String>,
    val expectedEmotion: String = "Neutral",
    val expectedAffect: String = expectedEmotion
)

/**
 * BLEU scores computed against human reference translations.
 */
data class BleuScore(
    val bleu1: Double,
    val bleu2: Double,
    val bleu3: Double,
    val bleu4: Double,
    val brevityPenalty: Double,
    val cumulativeBleu: Double = bleu4
)

/**
 * Stress condition parameter set for Test 4 (Table 4.20 Operational Boundary Testing).
 */
data class StressCondition(
    val id: String,
    val name: String,
    val instruction: String,
    val stressVariable: String,
    val stressLevel: String,
    val distanceMeters: Float,
    val azimuthAngleDeg: Int,
    val elevationAngleDeg: Int,
    val backgroundComplexity: String,
    val targetSign: String = "EMERGENCY"
)

/**
 * Sub-modes for Section 3.1 (Table 4.17).
 */
enum class Section31SubMode(val id: String, val title: String) {
    HAND_GESTURE_ONLY("hand_gesture_only", "Hand Gesture (60 signs)"),
    FACIAL_EXPRESSION_ONLY("facial_expression_only", "Facial Expression Only"),
    COMBINED("combined", "Combined Gesture + Facial")
}

/**
 * Test Phases corresponding to Chapter 4 Problem 3 sections:
 * - 3.1: Table 4.17 Gesture and Facial Expression Recognition
 * - 3.2: Table 4.18 Sentence Synthesis Quality (BLEU)
 * - 3.3: Table 4.19 Processing Efficiency (FPS & Latency)
 * - 3.4: Table 4.20 Operational Boundaries Under Varying Conditions
 */
enum class TestPhase(val id: String, val title: String) {
    TEST_1_SINGLE_SIGNS("test1_single_signs", "Test 1 (3.1): Recognition Performance"),
    TEST_2_FULL_SENTENCES("test2_sentences", "Test 2 (3.2): Sentence Synthesis (BLEU)"),
    TEST_3_SPEED_CHECK("test3_speed_latency", "Test 3 (3.3): Processing Efficiency"),
    TEST_4_BREAKING_POINTS("test4_stress", "Test 4 (3.4): Operational Boundaries")
}

/**
 * Central coordinator for MotionSpeak's live testing protocol:
 * - Table 4.17: test_3_1_recognition.csv
 * - Table 4.18: test_3_2_sentences.csv
 * - Table 4.19: test_3_3_efficiency.csv
 * - Table 4.20: test_3_4_operational_boundaries.csv
 * - Unified 8-emotion taxonomy matching HSEmotion & Camera Overlay
 * - Separate Download & Export functions per section in Settings
 */
object TestModeManager {

    private const val TAG = "TestModeManager"
    const val LOGS_DIR_NAME = "test_mode_logs"

    // Dedicated CSV File Names
    const val FILE_3_1_RECOGNITION = "test_3_1_recognition.csv"
    const val FILE_3_2_SENTENCES = "test_3_2_sentences.csv"
    const val FILE_3_3_EFFICIENCY = "test_3_3_efficiency.csv"
    const val FILE_3_4_BOUNDARIES = "test_3_4_operational_boundaries.csv"
    const val TELEMETRY_FILE_NAME = "test_telemetry.csv" // Legacy compatibility

    private val csvExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "motionspeak-telemetry-logger").apply { isDaemon = true }
    }

    // ── Dedicated Table Schemas ───────────────────────────────────────────────

    val HEADER_3_1 = listOf(
        "timestamp_iso",
        "sub_mode",
        "iteration_index",
        "prompt_ground_truth",
        "predicted_label",
        "confidence",
        "is_correct"
    ).joinToString(",")

    val HEADER_3_2 = listOf(
        "timestamp_iso",
        "iteration_index",
        "prompt_ground_truth",
        "signed_glosses",
        "synthesized_sentence",
        "reference_translations",
        "bleu_1",
        "bleu_2",
        "bleu_3",
        "bleu_4",
        "cumulative_bleu",
        "is_llm_generated",
        "synthesis_latency_ms"
    ).joinToString(",")

    val HEADER_3_3 = listOf(
        "timestamp_iso",
        "frame_index",
        "camera_to_bitmap_ms",
        "mediapipe_latency_ms",
        "tflite_latency_ms",
        "total_inference_ms",
        "e2e_latency_ms",
        "fps"
    ).joinToString(",")

    val HEADER_3_4 = listOf(
        "timestamp_iso",
        "stress_variable",
        "stress_level",
        "prompt_gloss",
        "predicted_gloss",
        "confidence",
        "is_correct",
        "fps",
        "e2e_latency_ms",
        "ambient_lux",
        "distance_meters",
        "azimuth_angle_deg",
        "elevation_angle_deg",
        "background_complexity",
        "signer_id",
        "device_tier",
        "below_threshold"
    ).joinToString(",")

    // Legacy schema compatibility
    val CSV_HEADER = listOf(
        "timestamp_iso",
        "elapsed_realtime_ms",
        "test_phase",
        "iteration_index",
        "prompt_ground_truth",
        "expected_facial_affect",
        "detected_emotion",
        "facial_affect",
        "facial_confidence",
        "reference_translations",
        "predicted_gloss",
        "predicted_sentence",
        "confidence",
        "confidence_tier",
        "top_alternatives",
        "mediapipe_hand_confidence",
        "camera_to_bitmap_ms",
        "mediapipe_latency_ms",
        "tflite_latency_ms",
        "t_llm_ms",
        "t_tts_ms",
        "e2e_latency_ms",
        "fps",
        "ambient_lux",
        "distance_meters",
        "azimuth_angle_deg",
        "elevation_angle_deg",
        "background_complexity",
        "signer_id",
        "device_tier",
        "delegate_type",
        "is_correct",
        "bleu_1",
        "bleu_2",
        "bleu_3",
        "bleu_4"
    ).joinToString(",")

    // ── Standard 8 Emotional States (HSEmotion & Overlay unified) ──────────────

    val FACIAL_EXPRESSION_PROMPTS = listOf(
        "Neutral",
        "Happy",
        "Sad",
        "Surprise",
        "Fear",
        "Angry",
        "Disgust",
        "Contempt"
    )

    // ── Pre-configured Evaluation Corpora ──────────────────────────────────────

    val SINGLE_SIGN_PROMPTS = listOf(
        SingleSignPrompt("ABORTION", "Abortion / Pagpapalaglag", "Concerned / Pain", "Sad"),
        SingleSignPrompt("ACCIDENT", "Accident / Aksidente", "Shock / Alarm", "Fear"),
        SingleSignPrompt("ALLERGIES", "Allergies / Alerhiya", "Discomfort / Irritated", "Disgust"),
        SingleSignPrompt("AMBULANCE", "Ambulance / Ambulansya", "Urgent / Alarm", "Surprise"),
        SingleSignPrompt("ASTHMA", "Asthma / Hika", "Gasping / Distressed", "Fear"),
        SingleSignPrompt("BLURRED_VISION", "Blurred vision / Malabong paningin", "Squinting / Concerned", "Surprise"),
        SingleSignPrompt("BREATHING", "Breathing / Paghinga", "Chest focus / Labored", "Neutral"),
        SingleSignPrompt("BROKEN_BONE", "Broken bone / Bali sa buto", "Grimace / Severe pain", "Sad"),
        SingleSignPrompt("CANCER", "Cancer / Kanser", "Grave / Somber", "Sad"),
        SingleSignPrompt("CIRCUMCISION", "Circumcision / Tuli", "Neutral / Clinical", "Neutral"),
        SingleSignPrompt("COMPRESSION", "CPR Compression / Pagbomba sa dibdib", "Urgent / Focused", "Surprise"),
        SingleSignPrompt("CONSTIPATION", "Constipation / Hindi makadumi", "Straining / Uncomfortable", "Disgust"),
        SingleSignPrompt("CONSULT", "Consultation / Konsulta", "Inquiring / Calm", "Neutral"),
        SingleSignPrompt("COUGH", "Cough / Ubo", "Throat irritation / Fatigue", "Sad"),
        SingleSignPrompt("COVID_19", "COVID-19 / Covid", "Caution / Alert", "Surprise"),
        SingleSignPrompt("DEPRESSION", "Depression / Depresyon", "Downcast / Sad", "Sad"),
        SingleSignPrompt("DIABETES", "Diabetes / Dyabetis", "Clinical / Serious", "Neutral"),
        SingleSignPrompt("DIARRHEA", "Diarrhea / Pagtatae", "Abdominal distress", "Disgust"),
        SingleSignPrompt("DIZZINESS", "Dizziness / Pagkahilo", "Disoriented / Eyes closed", "Fear"),
        SingleSignPrompt("DRY_MOUTH", "Dry mouth / Tuyong bibig", "Thirst / Discomfort", "Neutral"),
        SingleSignPrompt("EMERGENCY", "Emergency / Saklolo", "Urgent / High alert", "Fear"),
        SingleSignPrompt("FEVER", "Fever / Lagnat", "Weak / Flushed", "Sad"),
        SingleSignPrompt("FLU", "Flu / Trangkaso", "Malaise / Fatigued", "Sad"),
        SingleSignPrompt("GIVING_BIRTH", "Giving birth / Panganganak", "Intense labor pain", "Fear"),
        SingleSignPrompt("GOOD_AFTERNOON", "Good afternoon / Magandang hapon", "Friendly / Polite", "Happy"),
        SingleSignPrompt("GOOD_EVENING", "Good evening / Magandang gabi", "Calm / Warm", "Neutral"),
        SingleSignPrompt("GOOD_MORNING", "Good morning / Magandang umaga", "Smiling / Warm greeting", "Happy"),
        SingleSignPrompt("HEADACHE", "Headache / Sakit ng ulo", "Temple pain / Grimace", "Sad"),
        SingleSignPrompt("HEART_ATTACK", "Heart attack / Atake sa puso", "Agonized / Clutching chest", "Fear"),
        SingleSignPrompt("HELLO", "Hello / Kumusta", "Welcoming smile", "Happy"),
        SingleSignPrompt("HI", "Hi / Bati", "Friendly wave / Smile", "Happy"),
        SingleSignPrompt("HOSPITALIZED", "Hospitalized / Naospital", "Weary / Sick", "Sad"),
        SingleSignPrompt("HOW_ARE_YOU", "How are you? / Kumusta ka?", "Inquiring / Brow raise", "Surprise"),
        SingleSignPrompt("HOW_CAN_I_HELP_YOU", "How can I help you? / Paano kita matutulungan?", "Empathetic / Open", "Happy"),
        SingleSignPrompt("HURT", "Hurt / Nasaktan", "Wincing / Sharp pain", "Sad"),
        SingleSignPrompt("HYPERTENSION", "Hypertension / Alta presyon", "Tense / High blood", "Surprise"),
        SingleSignPrompt("I_DONT_UNDERSTAND", "I do not understand / Hindi ko naiintindihan", "Confused / Brow furrow", "Surprise"),
        SingleSignPrompt("INJECTION", "Injection / Turok", "Apprehensive / Clinical", "Fear"),
        SingleSignPrompt("INJURY", "Injury / Pinsala", "Pain / Distressed", "Sad"),
        SingleSignPrompt("IV", "IV Drip / Swero", "Patient / Calm", "Neutral"),
        SingleSignPrompt("LABORATORY", "Laboratory / Lab test", "Clinical / Attentive", "Neutral"),
        SingleSignPrompt("MAYBE", "Maybe / Baka", "Head tilt / Shrug", "Neutral"),
        SingleSignPrompt("MEDICINES", "Medicines / Gamot", "Seeking relief", "Neutral"),
        SingleSignPrompt("MENSTRUATION", "Menstruation / Regla", "Cramping / Discomfort", "Sad"),
        SingleSignPrompt("MUMPS", "Mumps / Beke", "Swollen cheek / Pain", "Sad"),
        SingleSignPrompt("NECK_PAIN", "Neck pain / Pananakit ng leeg", "Stiff / Wincing", "Sad"),
        SingleSignPrompt("NO", "No / Hindi", "Head shake / Negation", "Neutral"),
        SingleSignPrompt("PIGSA", "Boil / Pigsa", "Tender / Inflamed pain", "Disgust"),
        SingleSignPrompt("PLEASE", "Please / Pakiusap", "Polite / Entreaty", "Neutral"),
        SingleSignPrompt("PREGNANT", "Pregnant / Buntis", "Gentle / Hopeful", "Happy"),
        SingleSignPrompt("PRESCRIPTION", "Prescription / Reseta", "Attentive / Reading", "Neutral"),
        SingleSignPrompt("SICK", "Sick / May sakit", "Exhausted / Pale", "Sad"),
        SingleSignPrompt("SIPON", "Cold / Sipon", "Sniffling / Congested", "Sad"),
        SingleSignPrompt("SORE_THROAT", "Sore throat / Masakit ang lalamunan", "Difficulty swallowing", "Sad"),
        SingleSignPrompt("SURGERY", "Surgery / Operasyon", "Anxious / Serious", "Fear"),
        SingleSignPrompt("THANK_YOU", "Thank you / Salamat", "Warm smile / Gratitude", "Happy"),
        SingleSignPrompt("THERMOMETER", "Thermometer / Termometro", "Patient / Waiting", "Neutral"),
        SingleSignPrompt("UNDERSTAND", "Understand / Naiintindihan", "Affirmative nod", "Happy"),
        SingleSignPrompt("XRAY", "X-ray / Eksrey", "Still / Clinical", "Neutral"),
        SingleSignPrompt("YES", "Yes / Oo", "Affirmative nod / Smile", "Happy")
    )

    val FULL_SENTENCE_PROMPTS = listOf(
        SentencePrompt(
            promptText = "HELLO HOW ARE YOU?",
            glosses = listOf("HELLO", "HOW_ARE_YOU"),
            referenceTranslations = listOf("Hello how are you?", "Hi how are you doing?", "Kumusta ka?"),
            expectedEmotion = "Happy"
        ),
        SentencePrompt(
            promptText = "EMERGENCY PLEASE CALL AMBULANCE",
            glosses = listOf("EMERGENCY", "PLEASE", "AMBULANCE"),
            referenceTranslations = listOf("Emergency please call an ambulance", "Emergency please send an ambulance", "Saklolo pakiusap tumawag ng ambulansya"),
            expectedEmotion = "Fear"
        ),
        SentencePrompt(
            promptText = "I HAVE HEADACHE AND FEVER",
            glosses = listOf("HEADACHE", "FEVER", "SICK"),
            referenceTranslations = listOf("I have a headache and fever", "I have a headache and a fever and feel sick", "Masakit ang ulo ko at may lagnat ako"),
            expectedEmotion = "Sad"
        ),
        SentencePrompt(
            promptText = "DIFFICULTY BREATHING NEED MEDICINES FOR ASTHMA",
            glosses = listOf("BREATHING", "MEDICINES", "ASTHMA"),
            referenceTranslations = listOf("I have difficulty breathing and need medicine for asthma", "Difficulty breathing need medicine for asthma", "Hindi makahinga kailangan ng gamot sa hika"),
            expectedEmotion = "Fear"
        ),
        SentencePrompt(
            promptText = "ACCIDENT BROKEN BONE VERY HURT",
            glosses = listOf("ACCIDENT", "BROKEN_BONE", "HURT"),
            referenceTranslations = listOf("I had an accident with a broken bone it hurts", "Accident broken bone very hurt", "Aksidente may bali sa buto sobrang sakit"),
            expectedEmotion = "Sad"
        ),
        SentencePrompt(
            promptText = "I DO NOT UNDERSTAND THE PRESCRIPTION",
            glosses = listOf("I_DONT_UNDERSTAND", "PRESCRIPTION"),
            referenceTranslations = listOf("I do not understand the prescription", "I cannot understand this prescription", "Hindi ko naiintindihan ang reseta"),
            expectedEmotion = "Surprise"
        ),
        SentencePrompt(
            promptText = "NEED DOCTOR CONSULTATION AND HOSPITALIZED",
            glosses = listOf("CONSULT", "HOSPITALIZED"),
            referenceTranslations = listOf("I need to consult a doctor and be hospitalized", "Need doctor consultation and hospitalization", "Kailangan ng konsulta sa doktor at maospital"),
            expectedEmotion = "Sad"
        ),
        SentencePrompt(
            promptText = "CHEST PAIN EMERGENCY POSSIBLE HEART ATTACK",
            glosses = listOf("EMERGENCY", "HURT", "HEART_ATTACK"),
            referenceTranslations = listOf("Emergency I am hurt possible heart attack", "Chest pain emergency possible heart attack", "Saklolo masakit ang dibdib inaatake sa puso"),
            expectedEmotion = "Fear"
        ),
        SentencePrompt(
            promptText = "THANK YOU HOW CAN I HELP YOU",
            glosses = listOf("THANK_YOU", "HOW_CAN_I_HELP_YOU"),
            referenceTranslations = listOf("Thank you how can I help you", "Thanks how may I assist you", "Maraming salamat paano kita matutulungan"),
            expectedEmotion = "Happy"
        ),
        SentencePrompt(
            promptText = "I HAVE SORE THROAT COUGH AND FLU",
            glosses = listOf("SORE_THROAT", "COUGH", "FLU"),
            referenceTranslations = listOf("I have a sore throat cough and the flu", "Patient has sore throat cough and flu", "May masakit na lalamunan ubo at trangkaso"),
            expectedEmotion = "Sad"
        ),
        SentencePrompt(
            promptText = "NEED LABORATORY BLOOD TEST AND XRAY",
            glosses = listOf("LABORATORY", "XRAY"),
            referenceTranslations = listOf("I need a laboratory test and an X-ray", "Need laboratory test and X-ray", "Kailangan ng laboratoryo at eksrey"),
            expectedEmotion = "Neutral"
        ),
        SentencePrompt(
            promptText = "PREGNANT GIVING BIRTH NEED SURGERY",
            glosses = listOf("PREGNANT", "GIVING_BIRTH", "SURGERY"),
            referenceTranslations = listOf("I am pregnant and giving birth and need surgery", "Pregnant giving birth needs surgery", "Buntis manganganak kailangan ng operasyon"),
            expectedEmotion = "Fear"
        ),
        SentencePrompt(
            promptText = "DIZZINESS AND BLURRED VISION HYPERTENSION",
            glosses = listOf("DIZZINESS", "BLURRED_VISION", "HYPERTENSION"),
            referenceTranslations = listOf("I have dizziness and blurred vision from hypertension", "Dizziness and blurred vision from hypertension", "Nahihilo malabo ang paningin alta presyon"),
            expectedEmotion = "Surprise"
        ),
        SentencePrompt(
            promptText = "PLEASE CHECK TEMPERATURE WITH THERMOMETER",
            glosses = listOf("PLEASE", "THERMOMETER", "FEVER"),
            referenceTranslations = listOf("Please check temperature with thermometer", "Please take my temperature using a thermometer", "Pakiusap tingnan ang temperatura gamit ang termometro"),
            expectedEmotion = "Neutral"
        ),
        SentencePrompt(
            promptText = "NEED INJECTION AND IV DRIP",
            glosses = listOf("INJECTION", "IV"),
            referenceTranslations = listOf("I need an injection and an IV drip", "Patient needs injection and IV drip", "Kailangan ng turok at swero"),
            expectedEmotion = "Neutral"
        ),
        SentencePrompt(
            promptText = "SEVERE DIARRHEA AND CONSTIPATION",
            glosses = listOf("DIARRHEA", "CONSTIPATION"),
            referenceTranslations = listOf("I have severe diarrhea and constipation", "Severe diarrhea and constipation", "Matinding pagtatae at hindi makadumi"),
            expectedEmotion = "Disgust"
        ),
        SentencePrompt(
            promptText = "GOOD MORNING DO YOU UNDERSTAND YES",
            glosses = listOf("GOOD_MORNING", "UNDERSTAND", "YES"),
            referenceTranslations = listOf("Good morning do you understand yes", "Good morning do you understand affirmative", "Magandang umaga naiintindihan mo ba oo"),
            expectedEmotion = "Happy"
        ),
        SentencePrompt(
            promptText = "MAYBE INJURY FROM ACCIDENT",
            glosses = listOf("MAYBE", "INJURY", "ACCIDENT"),
            referenceTranslations = listOf("Maybe an injury from an accident", "Possible injury caused by an accident", "Baka may pinsala dahil sa aksidente"),
            expectedEmotion = "Sad"
        ),
        SentencePrompt(
            promptText = "DRY MOUTH AND ALLERGIES NO FEVER",
            glosses = listOf("DRY_MOUTH", "ALLERGIES", "NO", "FEVER"),
            referenceTranslations = listOf("I have dry mouth and allergies but no fever", "Dry mouth and allergies no fever", "Tuyong bibig at alerhiya walang lagnat"),
            expectedEmotion = "Neutral"
        ),
        SentencePrompt(
            promptText = "DEPRESSION AND NECK PAIN NEED HELP",
            glosses = listOf("DEPRESSION", "NECK_PAIN", "CONSULT"),
            referenceTranslations = listOf("I have depression and neck pain and need to consult", "Depression and neck pain need consultation", "Depresyon at pananakit ng leeg kailangan ng tulong"),
            expectedEmotion = "Sad"
        )
    )

    val BREAKING_POINT_SIGNS = listOf(
        "HELLO", "GOOD_MORNING", "EMERGENCY", "FEVER", "HEADACHE",
        "BREATHING", "MEDICINES", "HURT", "PLEASE", "THANK_YOU"
    )

    val STRESS_CONDITIONS = listOf(
        StressCondition("cond_lux_50", "Illuminance: ~50 lux (Low light / evening)", "Dim room lights to ~50 lux level", "Illuminance", "~50 lux (Low light / evening)", 1.0f, 0, 0, "Plain wall", "HURT"),
        StressCondition("cond_lux_150", "Illuminance: ~150 lux (Dim indoor)", "Maintain dim indoor lighting (~150 lux)", "Illuminance", "~150 lux (Dim indoor)", 1.0f, 0, 0, "Plain wall", "FEVER"),
        StressCondition("cond_lux_300", "Illuminance: ~300 lux (Ambient office lighting)", "Standard indoor office lighting (~300 lux)", "Illuminance", "~300 lux (Ambient office lighting)", 1.0f, 0, 0, "Plain wall", "HEADACHE"),
        StressCondition("cond_lux_1000", "Illuminance: >1,000 lux (Bright daylight / outdoor)", "Position near bright daylight or outdoor source (>1,000 lux)", "Illuminance", ">1,000 lux (Bright daylight / outdoor)", 1.0f, 0, 0, "Plain wall", "PLEASE"),
        StressCondition("cond_dist_05", "Camera distance: 0.5 m (Close boundary)", "Step close: 0.5 m distance from camera", "Camera distance", "0.5 m (Close boundary)", 0.5f, 0, 0, "Plain wall", "EMERGENCY"),
        StressCondition("cond_dist_15_20", "Camera distance: 1.5–2.0 m (Standing boundary)", "Step back: 1.5–2.0 m distance from camera", "Camera distance", "1.5–2.0 m (Standing boundary)", 1.75f, 0, 0, "Plain wall", "GOOD_MORNING"),
        StressCondition("cond_ang_15", "Camera angle: 15° (Slight azimuth / handheld)", "Turn body/camera 15° to the right", "Camera angle", "15° (Slight azimuth / handheld)", 1.0f, 15, 0, "Plain wall", "MEDICINES"),
        StressCondition("cond_ang_30", "Camera angle: 30° (Moderate azimuth / desk tilt)", "Turn body/camera 30° to the right", "Camera angle", "30° (Moderate azimuth / desk tilt)", 1.0f, 30, 0, "Plain wall", "BREATHING"),
        StressCondition("cond_ang_45", "Camera angle: 45° (Extreme lateral angle)", "Turn body/camera 45° to the right (occlusion stress)", "Camera angle", "45° (Extreme lateral angle)", 1.0f, 45, 0, "Plain wall", "THANK_YOU"),
        StressCondition("cond_bg_plain", "Background complexity: Structured indoor (Plain wall)", "Position signer against structured indoor plain wall", "Background complexity", "Structured indoor (Plain wall)", 1.0f, 0, 0, "Plain wall", "HELLO"),
        StressCondition("cond_bg_clutter", "Background complexity: Cluttered and dynamic (Clinic corridor)", "Position against cluttered corridor with dynamic background movement", "Background complexity", "Cluttered and dynamic (Clinic corridor)", 1.0f, 0, 0, "Cluttered corridor", "EMERGENCY"),
        StressCondition("cond_signer_seen", "Signer variation: Primary / Seen Signers", "Evaluate with primary calibrated signer (Signer_01)", "Signer variation", "Primary / Seen Signers", 1.0f, 0, 0, "Plain wall", "FEVER"),
        StressCondition("cond_signer_unseen", "Signer variation: Unseen Signers (8 test signers)", "Evaluate with alternative or unseen signer (Signer_02+)", "Signer variation", "Unseen Signers (8 test signers)", 1.0f, 0, 0, "Plain wall", "HEADACHE"),
        StressCondition("cond_hw_entry", "Hardware tier: 4 GB RAM (Entry tier)", "Evaluate on entry-tier hardware envelope", "Hardware tier", "4 GB RAM (Entry tier)", 1.0f, 0, 0, "Plain wall", "PLEASE"),
        StressCondition("cond_hw_flagship", "Hardware tier: 8–12+ GB RAM (Flagship tier)", "Evaluate on flagship/upper-mid tier hardware", "Hardware tier", "8–12+ GB RAM (Flagship tier)", 1.0f, 0, 0, "Plain wall", "THANK_YOU")
    )

    // ── Live Session State ─────────────────────────────────────────────────────

    @Volatile var activePhase: TestPhase = TestPhase.TEST_1_SINGLE_SIGNS
    @Volatile var section31SubMode: Section31SubMode = Section31SubMode.HAND_GESTURE_ONLY
    @Volatile var singleSignIndex: Int = 0
    @Volatile var facialEmotionIndex: Int = 0
    @Volatile var sentenceIndex: Int = 0
    @Volatile var breakingPointSignIndex: Int = 0
    @Volatile var stressConditionIndex: Int = 0
    @Volatile var frameIndex33: Long = 0L

    fun applyStressCondition(index: Int): StressCondition {
        val safeIdx = index.coerceIn(0, STRESS_CONDITIONS.size - 1)
        stressConditionIndex = safeIdx
        val cond = STRESS_CONDITIONS[safeIdx]
        distanceMeters = cond.distanceMeters
        azimuthAngleDeg = cond.azimuthAngleDeg
        elevationAngleDeg = cond.elevationAngleDeg
        backgroundComplexity = cond.backgroundComplexity
        return cond
    }

    // Iteration tracking: supports 10 iterations across the 60-sign vocabulary (600+ rows)
    @Volatile var currentIteration: Int = 1
    @Volatile var maxIterations: Int = 10

    // Operational boundary variables (Used strictly in Section 3.4)
    @Volatile var distanceMeters: Float = 1.0f
    @Volatile var azimuthAngleDeg: Int = 0
    @Volatile var elevationAngleDeg: Int = 0
    @Volatile var backgroundComplexity: String = "Plain wall"
    @Volatile var signerId: String = "Signer_01"

    // Physical hardware sensor reading
    @Volatile var rawHardwareLux: Float = -1.0f
        private set

    // Effective lux (fused hardware + camera photometric illuminance)
    val currentLux: Float
        get() {
            val eff = PhotometricLuxEstimator.latestEffectiveLux
            return if (eff >= 0f) eff else rawHardwareLux
        }

    private var sensorManager: SensorManager? = null
    private val registeredSensors = ArrayList<Sensor>()
    private var isSensorRegistered = false

    private val lightListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent?) {
            if (event?.sensor?.type == Sensor.TYPE_LIGHT && event.values.isNotEmpty()) {
                val lux = event.values[0]
                rawHardwareLux = if (rawHardwareLux < 0f) lux else maxOf(rawHardwareLux, lux)
                PhotometricLuxEstimator.updateHardwareLux(rawHardwareLux)
            }
        }
        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    // Live Metrics HUD cache
    @Volatile var latestLiveFps: Double = 0.0
    @Volatile var latestE2eLatencyMs: Long = 0L
    @Volatile var latestMediaPipeMs: Double = 0.0
    @Volatile var latestTfliteMs: Double = 0.0
    @Volatile var latestLlmMs: Double = 0.0
    @Volatile var latestTtsMs: Double = 0.0
    @Volatile var latestConfidence: Float = 0f
    @Volatile var latestPredictionLabel: String = ""
    @Volatile var latestIsMatch: Boolean = false
    @Volatile var latestBleuScore: BleuScore? = null

    // ── Light Sensor Lifecycle ────────────────────────────────────────────────

    fun startSensor(context: Context) {
        if (isSensorRegistered) return
        try {
            val sm = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
            sensorManager = sm
            val sensors = sm?.getSensorList(Sensor.TYPE_LIGHT) ?: emptyList()
            if (sm != null && sensors.isNotEmpty()) {
                registeredSensors.clear()
                for (s in sensors) {
                    val ok = sm.registerListener(lightListener, s, SensorManager.SENSOR_DELAY_UI)
                    if (ok) registeredSensors.add(s)
                }
                isSensorRegistered = registeredSensors.isNotEmpty()
                Log.d(TAG, "Android Light Sensors (${registeredSensors.size} active) registered successfully")
            } else {
                Log.w(TAG, "No ambient light sensor found on device; falling back to photometric camera illuminance")
                rawHardwareLux = -1.0f
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error starting light sensor: ${e.message}", e)
        }
    }

    fun stopSensor() {
        if (!isSensorRegistered) return
        try {
            sensorManager?.unregisterListener(lightListener)
            sensorManager = null
            registeredSensors.clear()
            isSensorRegistered = false
            PhotometricLuxEstimator.reset()
            rawHardwareLux = -1.0f
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping light sensor: ${e.message}", e)
        }
    }

    // ── BLEU Score Computation (Kotlin On-Device) ─────────────────────────────

    /**
     * Calculates BLEU-1, BLEU-2, BLEU-3, and BLEU-4 scores for a synthesized sentence
     * against human reference translations with Chen & Cherry smoothing.
     */
    fun calculateBleu(candidate: String, references: List<String>): BleuScore {
        val candTokens = tokenize(candidate)
        val refTokensList = references.map { tokenize(it) }.filter { it.isNotEmpty() }

        if (candTokens.isEmpty() || refTokensList.isEmpty()) {
            return BleuScore(0.0, 0.0, 0.0, 0.0, 0.0, 0.0)
        }

        // Brevity Penalty
        val candLen = candTokens.size
        var closestRefLen = refTokensList.first().size
        var minDiff = kotlin.math.abs(candLen - closestRefLen)
        for (ref in refTokensList) {
            val diff = kotlin.math.abs(candLen - ref.size)
            if (diff < minDiff) {
                minDiff = diff
                closestRefLen = ref.size
            }
        }
        val bp = if (candLen > closestRefLen) 1.0 else exp(1.0 - (closestRefLen.toDouble() / candLen))

        // Precisions for n = 1..4
        val precisions = DoubleArray(4)
        for (n in 1..4) {
            val candNgrams = getNgrams(candTokens, n)
            if (candNgrams.isEmpty()) {
                precisions[n - 1] = 0.0
                continue
            }

            val maxRefCounts = HashMap<String, Int>()
            for (ref in refTokensList) {
                val refNgrams = getNgrams(ref, n)
                val counts = HashMap<String, Int>()
                for (ng in refNgrams) counts[ng] = (counts[ng] ?: 0) + 1
                for ((ng, count) in counts) {
                    val current = maxRefCounts[ng] ?: 0
                    if (count > current) maxRefCounts[ng] = count
                }
            }

            val candCounts = HashMap<String, Int>()
            for (ng in candNgrams) candCounts[ng] = (candCounts[ng] ?: 0) + 1

            var matchCount = 0
            for ((ng, count) in candCounts) {
                val maxRef = maxRefCounts[ng] ?: 0
                matchCount += min(count, maxRef)
            }

            // Chen & Cherry Method 1 smoothing for 0 counts
            val p = if (matchCount == 0) {
                (0.1 / candNgrams.size.toDouble())
            } else {
                matchCount.toDouble() / candNgrams.size.toDouble()
            }
            precisions[n - 1] = p
        }

        fun cumBleu(maxN: Int): Double {
            var sum = 0.0
            for (i in 0 until maxN) {
                sum += ln(precisions[i])
            }
            return bp * exp(sum / maxN)
        }

        val b1 = (cumBleu(1) * 100).coerceIn(0.0, 100.0) / 100.0
        val b2 = (cumBleu(2) * 100).coerceIn(0.0, 100.0) / 100.0
        val b3 = (cumBleu(3) * 100).coerceIn(0.0, 100.0) / 100.0
        val b4 = (cumBleu(4) * 100).coerceIn(0.0, 100.0) / 100.0

        return BleuScore(
            bleu1 = b1,
            bleu2 = b2,
            bleu3 = b3,
            bleu4 = b4,
            brevityPenalty = bp,
            cumulativeBleu = b4
        )
    }

    private fun tokenize(text: String): List<String> =
        text.lowercase(Locale.ROOT)
            .replace("[^a-z0-9_\\s]".toRegex(), "")
            .split("\\s+".toRegex())
            .filter { it.isNotBlank() }

    private fun getNgrams(tokens: List<String>, n: Int): List<String> {
        if (tokens.size < n) return emptyList()
        val ngrams = ArrayList<String>(tokens.size - n + 1)
        for (i in 0..tokens.size - n) {
            ngrams.add(tokens.subList(i, i + n).joinToString(" "))
        }
        return ngrams
    }

    // ── Dedicated Log File Access & Storage ───────────────────────────────────

    internal fun getSectionLogFile(baseDir: File, section: Int): File {
        val dir = File(baseDir, LOGS_DIR_NAME)
        if (!dir.exists()) dir.mkdirs()
        val (fileName, header) = when (section) {
            1 -> FILE_3_1_RECOGNITION to HEADER_3_1
            2 -> FILE_3_2_SENTENCES to HEADER_3_2
            3 -> FILE_3_3_EFFICIENCY to HEADER_3_3
            4 -> FILE_3_4_BOUNDARIES to HEADER_3_4
            else -> TELEMETRY_FILE_NAME to CSV_HEADER
        }
        val file = File(dir, fileName)
        if (!file.exists() || file.length() == 0L) {
            file.writeText(header + "\n")
        }
        return file
    }

    // Compatibility overload
    internal fun getLogFile(baseDir: File): File = getSectionLogFile(baseDir, 1)

    /**
     * Checks if a specific section's log file exists and contains at least one recorded data row.
     */
    fun hasLogsForSectionInDir(baseDir: File, section: Int): Boolean {
        val fileName = when (section) {
            1 -> FILE_3_1_RECOGNITION
            2 -> FILE_3_2_SENTENCES
            3 -> FILE_3_3_EFFICIENCY
            4 -> FILE_3_4_BOUNDARIES
            else -> TELEMETRY_FILE_NAME
        }
        val header = when (section) {
            1 -> HEADER_3_1
            2 -> HEADER_3_2
            3 -> HEADER_3_3
            4 -> HEADER_3_4
            else -> CSV_HEADER
        }
        val file = File(baseDir, "$LOGS_DIR_NAME/$fileName")
        if (!file.exists() || file.length() <= header.length + 2) return false
        return try {
            file.useLines { lines -> lines.drop(1).any { it.isNotBlank() } }
        } catch (_: Exception) {
            false
        }
    }

    fun hasLogsForSection(context: Context, section: Int): Boolean =
        hasLogsForSectionInDir(context.filesDir, section)

    /**
     * Checks if ANY test logs are available in given directory and have recorded data.
     */
    internal fun hasLogsInDir(baseDir: File): Boolean {
        return (1..4).any { hasLogsForSectionInDir(baseDir, it) } ||
            run {
                val legacy = File(baseDir, "$LOGS_DIR_NAME/$TELEMETRY_FILE_NAME")
                legacy.exists() && legacy.length() > CSV_HEADER.length + 2
            }
    }

    /**
     * Checks if test logs are available across any section.
     */
    fun hasLogs(context: Context): Boolean = hasLogsInDir(context.filesDir)

    private fun resetInMemoryMetrics() {
        latestLiveFps = 0.0
        latestE2eLatencyMs = 0L
        latestMediaPipeMs = 0.0
        latestTfliteMs = 0.0
        latestLlmMs = 0.0
        latestTtsMs = 0.0
        latestConfidence = 0f
        latestPredictionLabel = ""
        latestIsMatch = false
        latestBleuScore = null
        singleSignIndex = 0
        facialEmotionIndex = 0
        sentenceIndex = 0
        breakingPointSignIndex = 0
        stressConditionIndex = 0
        frameIndex33 = 0L
        currentIteration = 1
        distanceMeters = 1.0f
        azimuthAngleDeg = 0
        elevationAngleDeg = 0
        backgroundComplexity = "Plain wall"
        signerId = "Signer_01"
    }

    internal fun clearLogsInDir(baseDir: File) {
        val dir = File(baseDir, LOGS_DIR_NAME)
        if (dir.exists()) {
            dir.deleteRecursively()
        }
        resetInMemoryMetrics()
    }

    /**
     * Completely wipes all preexisting test logs across all 4 sections.
     * Called whenever Test Mode is turned OFF or user clicks Delete Logs.
     */
    fun clearLogs(context: Context) {
        try {
            clearLogsInDir(context.filesDir)
            Log.d(TAG, "All preexisting test logs and telemetry wiped successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Error clearing test logs: ${e.message}", e)
        }
    }

    // ── Dedicated Download / Export Functions ─────────────────────────────────

    private fun exportSpecificFile(context: Context, sourceFileName: String, targetPrefix: String): String? {
        val sourceFile = File(context.filesDir, "$LOGS_DIR_NAME/$sourceFileName")
        if (!sourceFile.exists() || sourceFile.length() < 10) return null

        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val exportFileName = "${targetPrefix}_$timeStamp.csv"

        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val resolver = context.contentResolver
                val contentValues = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, exportFileName)
                    put(MediaStore.MediaColumns.MIME_TYPE, "text/csv")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                }
                val uri: Uri? = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
                if (uri != null) {
                    resolver.openOutputStream(uri)?.use { out ->
                        sourceFile.inputStream().use { it.copyTo(out) }
                    }
                    exportFileName
                } else {
                    null
                }
            } else {
                @Suppress("DEPRECATION")
                val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                if (!downloadsDir.exists()) downloadsDir.mkdirs()
                val destFile = File(downloadsDir, exportFileName)
                sourceFile.copyTo(destFile, overwrite = true)
                destFile.absolutePath
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to export $sourceFileName to Downloads: ${e.message}", e)
            null
        }
    }

    fun exportSection31(context: Context): String? =
        exportSpecificFile(context, FILE_3_1_RECOGNITION, "motionspeak_table_4_17_recognition")

    fun exportSection32(context: Context): String? =
        exportSpecificFile(context, FILE_3_2_SENTENCES, "motionspeak_table_4_18_sentences")

    fun exportSection33(context: Context): String? =
        exportSpecificFile(context, FILE_3_3_EFFICIENCY, "motionspeak_table_4_19_efficiency")

    fun exportSection34(context: Context): String? =
        exportSpecificFile(context, FILE_3_4_BOUNDARIES, "motionspeak_table_4_20_boundaries")

    /**
     * Exports all available section logs to public Downloads.
     */
    fun exportLogsToDownloads(context: Context): String? {
        val exported = mutableListOf<String>()
        if (hasLogsForSection(context, 1)) exportSection31(context)?.let { exported.add(it) }
        if (hasLogsForSection(context, 2)) exportSection32(context)?.let { exported.add(it) }
        if (hasLogsForSection(context, 3)) exportSection33(context)?.let { exported.add(it) }
        if (hasLogsForSection(context, 4)) exportSection34(context)?.let { exported.add(it) }
        return if (exported.isNotEmpty()) exported.joinToString(", ") else null
    }

    // ── Telemetry Writing Helpers ─────────────────────────────────────────────

    private fun escapeCsv(text: String?): String {
        if (text == null) return ""
        val escaped = text.replace("\"", "\"\"")
        return if (escaped.contains(",") || escaped.contains("\"") || escaped.contains("\n")) {
            "\"$escaped\""
        } else {
            escaped
        }
    }

    private fun detectDeviceTier(context: Context): String {
        return try {
            val actManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            val memInfo = ActivityManager.MemoryInfo()
            actManager?.getMemoryInfo(memInfo)
            val totalRamGb = memInfo.totalMem / (1024.0 * 1024.0 * 1024.0)
            when {
                totalRamGb >= 10.0 -> "Flagship (${String.format(Locale.US, "%.1f", totalRamGb)}GB RAM)"
                totalRamGb >= 5.5  -> "Mid-range (${String.format(Locale.US, "%.1f", totalRamGb)}GB RAM)"
                else               -> "Entry-level (${String.format(Locale.US, "%.1f", totalRamGb)}GB RAM)"
            }
        } catch (_: Exception) {
            "Unknown"
        }
    }

    // ── Section 3.1: Table 4.17 Recognition Logging ───────────────────────────

    fun logSection31(
        context: Context,
        subMode: Section31SubMode,
        promptGroundTruth: String,
        predictedLabel: String,
        confidence: Float,
        isCorrect: Boolean
    ) {
        if (!AppSettings.getInstance(context).isTestModeEnabled) return
        val nowIso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).format(Date())

        latestPredictionLabel = predictedLabel
        latestConfidence = confidence
        latestIsMatch = isCorrect

        val row = listOf(
            nowIso,
            subMode.id,
            currentIteration.toString(),
            escapeCsv(promptGroundTruth),
            escapeCsv(predictedLabel),
            String.format(Locale.US, "%.4f", confidence),
            if (isCorrect) "1" else "0"
        ).joinToString(",")

        csvExecutor.execute {
            try {
                val file = getSectionLogFile(context.filesDir, 1)
                FileOutputStream(file, true).bufferedWriter().use { it.write(row + "\n") }
            } catch (e: Exception) {
                Log.e(TAG, "Error writing Section 3.1 telemetry: ${e.message}", e)
            }
        }
    }

    // ── Section 3.2: Table 4.18 Sentence BLEU Logging ─────────────────────────

    fun logSection32(
        context: Context,
        prompt: SentencePrompt,
        signedGlosses: List<String>,
        synthesizedSentence: String,
        bleuScore: BleuScore,
        isLlmGenerated: Boolean,
        synthesisLatencyMs: Long
    ) {
        if (!AppSettings.getInstance(context).isTestModeEnabled) return
        val nowIso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).format(Date())

        latestBleuScore = bleuScore
        latestPredictionLabel = synthesizedSentence
        latestLlmMs = synthesisLatencyMs.toDouble()
        latestIsMatch = true

        val refString = prompt.referenceTranslations.joinToString(" | ")
        val row = listOf(
            nowIso,
            currentIteration.toString(),
            escapeCsv(prompt.promptText),
            escapeCsv(signedGlosses.joinToString(" ")),
            escapeCsv(synthesizedSentence),
            escapeCsv(refString),
            String.format(Locale.US, "%.4f", bleuScore.bleu1),
            String.format(Locale.US, "%.4f", bleuScore.bleu2),
            String.format(Locale.US, "%.4f", bleuScore.bleu3),
            String.format(Locale.US, "%.4f", bleuScore.bleu4),
            String.format(Locale.US, "%.4f", bleuScore.cumulativeBleu),
            if (isLlmGenerated) "1" else "0",
            synthesisLatencyMs.toString()
        ).joinToString(",")

        csvExecutor.execute {
            try {
                val file = getSectionLogFile(context.filesDir, 2)
                FileOutputStream(file, true).bufferedWriter().use { it.write(row + "\n") }
            } catch (e: Exception) {
                Log.e(TAG, "Error writing Section 3.2 telemetry: ${e.message}", e)
            }
        }
    }

    // ── Section 3.3: Table 4.19 Processing Efficiency Logging ─────────────────

    fun logSection33(
        context: Context,
        frameIndex: Long,
        cameraToBitmapMs: Double,
        mediapipeLatencyMs: Double,
        tfliteLatencyMs: Double,
        totalInferenceMs: Double,
        e2eLatencyMs: Long,
        fps: Double
    ) {
        if (!AppSettings.getInstance(context).isTestModeEnabled) return
        val nowIso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).format(Date())

        latestLiveFps = fps
        latestE2eLatencyMs = e2eLatencyMs
        latestMediaPipeMs = mediapipeLatencyMs
        latestTfliteMs = tfliteLatencyMs

        val row = listOf(
            nowIso,
            frameIndex.toString(),
            String.format(Locale.US, "%.2f", cameraToBitmapMs),
            String.format(Locale.US, "%.2f", mediapipeLatencyMs),
            String.format(Locale.US, "%.2f", tfliteLatencyMs),
            String.format(Locale.US, "%.2f", totalInferenceMs),
            e2eLatencyMs.toString(),
            String.format(Locale.US, "%.1f", fps)
        ).joinToString(",")

        csvExecutor.execute {
            try {
                val file = getSectionLogFile(context.filesDir, 3)
                FileOutputStream(file, true).bufferedWriter().use { it.write(row + "\n") }
            } catch (e: Exception) {
                Log.e(TAG, "Error writing Section 3.3 telemetry: ${e.message}", e)
            }
        }
    }

    // ── Section 3.4: Table 4.20 Operational Boundaries Logging ────────────────

    fun logSection34(
        context: Context,
        stressVariable: String,
        stressLevel: String,
        promptGloss: String,
        predictedGloss: String,
        confidence: Float,
        isCorrect: Boolean,
        fps: Double,
        e2eLatencyMs: Long,
        ambientLux: Float,
        distanceMeters: Float,
        azimuthAngleDeg: Int,
        elevationAngleDeg: Int,
        backgroundComplexity: String,
        signerId: String,
        deviceTier: String,
        belowThreshold: Boolean
    ) {
        if (!AppSettings.getInstance(context).isTestModeEnabled) return
        val nowIso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).format(Date())

        latestPredictionLabel = predictedGloss
        latestConfidence = confidence
        latestIsMatch = isCorrect
        latestLiveFps = fps
        latestE2eLatencyMs = e2eLatencyMs

        val row = listOf(
            nowIso,
            escapeCsv(stressVariable),
            escapeCsv(stressLevel),
            escapeCsv(promptGloss),
            escapeCsv(predictedGloss),
            String.format(Locale.US, "%.4f", confidence),
            if (isCorrect) "1" else "0",
            String.format(Locale.US, "%.1f", fps),
            e2eLatencyMs.toString(),
            if (ambientLux >= 0f) String.format(Locale.US, "%.1f", ambientLux) else "N/A",
            String.format(Locale.US, "%.2f", distanceMeters),
            azimuthAngleDeg.toString(),
            elevationAngleDeg.toString(),
            escapeCsv(backgroundComplexity),
            escapeCsv(signerId),
            escapeCsv(deviceTier),
            if (belowThreshold) "1" else "0"
        ).joinToString(",")

        csvExecutor.execute {
            try {
                val file = getSectionLogFile(context.filesDir, 4)
                FileOutputStream(file, true).bufferedWriter().use { it.write(row + "\n") }
            } catch (e: Exception) {
                Log.e(TAG, "Error writing Section 3.4 telemetry: ${e.message}", e)
            }
        }
    }

    // ── Adapter Methods (Delegates to dedicated section logs) ─────────────────

    fun logSingleSign(
        context: Context,
        prompt: SingleSignPrompt,
        decision: PredictionDecision,
        mediapipeLatencyMs: Double,
        e2eLatencyMs: Long,
        fps: Double,
        mediapipeConfidence: Float = 0.5f,
        cameraToBitmapMs: Double = 0.0,
        detectedEmotion: String = "Neutral",
        facialAffect: String = "Neutral",
        facialConfidence: Float = 0.5f,
        tLlmMs: Double = 0.0,
        tTtsMs: Double = 0.0
    ) {
        logSingleSign(
            context = context,
            promptStr = prompt.gloss,
            expectedAffect = prompt.expectedEmotion,
            decision = decision,
            mediapipeLatencyMs = mediapipeLatencyMs,
            e2eLatencyMs = e2eLatencyMs,
            fps = fps,
            mediapipeConfidence = mediapipeConfidence,
            cameraToBitmapMs = cameraToBitmapMs,
            detectedEmotion = detectedEmotion,
            facialAffect = facialAffect,
            facialConfidence = facialConfidence,
            tLlmMs = tLlmMs,
            tTtsMs = tTtsMs
        )
    }

    fun logSingleSign(
        context: Context,
        promptStr: String,
        expectedAffect: String = "Neutral",
        decision: PredictionDecision,
        mediapipeLatencyMs: Double,
        e2eLatencyMs: Long,
        fps: Double,
        mediapipeConfidence: Float = 0.5f,
        cameraToBitmapMs: Double = 0.0,
        detectedEmotion: String = "Neutral",
        facialAffect: String = "Neutral",
        facialConfidence: Float = 0.5f,
        tLlmMs: Double = 0.0,
        tTtsMs: Double = 0.0
    ) {
        if (!AppSettings.getInstance(context).isTestModeEnabled) return

        val normPrompt = promptStr.trim().uppercase(Locale.ROOT).replace("[^A-Z0-9_]".toRegex(), "")
        val normPred = decision.topLabel.trim().uppercase(Locale.ROOT).replace("[^A-Z0-9_]".toRegex(), "")
        val isGestureCorrect = normPrompt.isNotEmpty() && (normPrompt == normPred || normPrompt.contains(normPred) || normPred.contains(normPrompt))

        val normExpected = ToneMapper.toTriageAffect(expectedAffect).lowercase(Locale.ROOT)
        val normDetected = ToneMapper.toTriageAffect(detectedEmotion).lowercase(Locale.ROOT)
        val isEmotionCorrect = normExpected == normDetected

        when (activePhase) {
            TestPhase.TEST_1_SINGLE_SIGNS -> {
                when (section31SubMode) {
                    Section31SubMode.HAND_GESTURE_ONLY -> {
                        logSection31(
                            context = context,
                            subMode = Section31SubMode.HAND_GESTURE_ONLY,
                            promptGroundTruth = promptStr,
                            predictedLabel = decision.topLabel,
                            confidence = decision.confidence,
                            isCorrect = isGestureCorrect
                        )
                    }
                    Section31SubMode.FACIAL_EXPRESSION_ONLY -> {
                        val currentFacialPrompt = FACIAL_EXPRESSION_PROMPTS.getOrNull(facialEmotionIndex) ?: expectedAffect
                        val facialMatch = currentFacialPrompt.equals(detectedEmotion, ignoreCase = true)
                        logSection31(
                            context = context,
                            subMode = Section31SubMode.FACIAL_EXPRESSION_ONLY,
                            promptGroundTruth = currentFacialPrompt,
                            predictedLabel = detectedEmotion,
                            confidence = facialConfidence,
                            isCorrect = facialMatch
                        )
                    }
                    Section31SubMode.COMBINED -> {
                        val combinedPrompt = "$promptStr ($expectedAffect)"
                        val combinedPred = "${decision.topLabel} ($detectedEmotion)"
                        val isCombinedCorrect = isGestureCorrect && isEmotionCorrect
                        logSection31(
                            context = context,
                            subMode = Section31SubMode.COMBINED,
                            promptGroundTruth = combinedPrompt,
                            predictedLabel = combinedPred,
                            confidence = decision.confidence,
                            isCorrect = isCombinedCorrect
                        )
                    }
                }
            }
            TestPhase.TEST_3_SPEED_CHECK -> {
                frameIndex33++
                val totalInf = mediapipeLatencyMs + decision.tfliteLatencyMs
                logSection33(
                    context = context,
                    frameIndex = frameIndex33,
                    cameraToBitmapMs = cameraToBitmapMs,
                    mediapipeLatencyMs = mediapipeLatencyMs,
                    tfliteLatencyMs = decision.tfliteLatencyMs,
                    totalInferenceMs = totalInf,
                    e2eLatencyMs = e2eLatencyMs,
                    fps = fps
                )
            }
            TestPhase.TEST_4_BREAKING_POINTS -> {
                val cond = STRESS_CONDITIONS.getOrNull(stressConditionIndex) ?: STRESS_CONDITIONS[0]
                val belowThreshold = !isGestureCorrect || fps < 20.0 || e2eLatencyMs > 500L
                logSection34(
                    context = context,
                    stressVariable = cond.stressVariable,
                    stressLevel = cond.stressLevel,
                    promptGloss = promptStr,
                    predictedGloss = decision.topLabel,
                    confidence = decision.confidence,
                    isCorrect = isGestureCorrect,
                    fps = fps,
                    e2eLatencyMs = e2eLatencyMs,
                    ambientLux = currentLux,
                    distanceMeters = distanceMeters,
                    azimuthAngleDeg = azimuthAngleDeg,
                    elevationAngleDeg = elevationAngleDeg,
                    backgroundComplexity = backgroundComplexity,
                    signerId = signerId,
                    deviceTier = detectDeviceTier(context),
                    belowThreshold = belowThreshold
                )
            }
            TestPhase.TEST_2_FULL_SENTENCES -> {
                // Sentences are logged manually via logSentence
            }
        }
    }

    fun logSentence(
        context: Context,
        prompt: SentencePrompt,
        result: SentenceResult,
        fps: Double,
        detectedEmotion: String = "Neutral",
        facialAffect: String = "Neutral",
        facialConfidence: Float = 0.5f,
        tLlmMs: Double = 0.0,
        tTtsMs: Double = 0.0,
        e2eLatencyMs: Long = 0L
    ) {
        val bleu = calculateBleu(result.text, prompt.referenceTranslations)
        val latency = if (result.latencyMs > 0L) result.latencyMs else (if (tLlmMs > 0.0) tLlmMs.toLong() else latestLlmMs.toLong())
        logSection32(
            context = context,
            prompt = prompt,
            signedGlosses = result.originalTokens,
            synthesizedSentence = result.text,
            bleuScore = bleu,
            isLlmGenerated = result.isLlmGenerated,
            synthesisLatencyMs = latency
        )
    }
}
