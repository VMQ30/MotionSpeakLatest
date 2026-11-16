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
 */
data class SingleSignPrompt(
    val gloss: String,
    val translation: String,
    val expression: String = "Neutral",
    val expectedAffect: String = "[STABLE]"
)

/**
 * Prompt data for Test 2 (Full Sentences BLEU evaluation).
 */
data class SentencePrompt(
    val promptText: String,
    val glosses: List<String>,
    val referenceTranslations: List<String>,
    val expectedAffect: String = "[STABLE]"
)

/**
 * BLEU scores computed against human reference translations.
 */
data class BleuScore(
    val bleu1: Double,
    val bleu2: Double,
    val bleu3: Double,
    val bleu4: Double,
    val brevityPenalty: Double
)

/**
 * Stress condition parameter set for Test 4 (Operational Boundary Testing).
 */
data class StressCondition(
    val id: String,
    val name: String,
    val instruction: String,
    val distanceMeters: Float,
    val azimuthAngleDeg: Int,
    val elevationAngleDeg: Int,
    val backgroundComplexity: String,
    val targetSign: String = "EMERGENCY"
)

/**
 * Test Phases corresponding to the MotionSpeak Technical Evaluation Protocol.
 */
enum class TestPhase(val id: String, val title: String) {
    TEST_1_SINGLE_SIGNS("test1_single_signs", "Test 1: Single Signs & Expressions"),
    TEST_2_FULL_SENTENCES("test2_sentences", "Test 2: Full Sentences (BLEU)"),
    TEST_3_SPEED_CHECK("test3_speed_latency", "Test 3: Speed Check (FPS & Latency)"),
    TEST_4_BREAKING_POINTS("test4_stress", "Test 4: Operational Boundary Stress")
}

/**
 * Central coordinator for MotionSpeak's live testing protocol:
 * - Live Prompting (Single Signs, Sentences, Speed stress, Breaking points)
 * - Android Light Sensor ambient lux tracking (Sensor.TYPE_LIGHT)
 * - BLEU N-gram scoring (BLEU-1 through BLEU-4)
 * - Operational boundary state (Distance, Angle, Background complexity, Signer)
 * - Asynchronous CSV telemetry logging
 * - Safe export to public Downloads and total log wipe when test mode is disabled
 */
object TestModeManager {

    private const val TAG = "TestModeManager"
    private const val LOGS_DIR_NAME = "test_mode_logs"
    private const val TELEMETRY_FILE_NAME = "test_telemetry.csv"

    private val csvExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "motionspeak-telemetry-logger").apply { isDaemon = true }
    }

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

    // ── Pre-configured Evaluation Corpora ──────────────────────────────────────

    val SINGLE_SIGN_PROMPTS = listOf(
        SingleSignPrompt("ABORTION", "Abortion / Pagpapalaglag", "Concerned / Pain", "[DISTRESSED]"),
        SingleSignPrompt("ACCIDENT", "Accident / Aksidente", "Shock / Alarm", "[DISTRESSED]"),
        SingleSignPrompt("ALLERGIES", "Allergies / Alerhiya", "Discomfort / Irritated", "[AGITATED]"),
        SingleSignPrompt("AMBULANCE", "Ambulance / Ambulansya", "Urgent / Alarm", "[ALERT]"),
        SingleSignPrompt("ASTHMA", "Asthma / Hika", "Gasping / Distressed", "[DISTRESSED]"),
        SingleSignPrompt("BLURRED_VISION", "Blurred vision / Malabong paningin", "Squinting / Concerned", "[ALERT]"),
        SingleSignPrompt("BREATHING", "Breathing / Paghinga", "Chest focus / Labored", "[STABLE]"),
        SingleSignPrompt("BROKEN_BONE", "Broken bone / Bali sa buto", "Grimace / Severe pain", "[DISTRESSED]"),
        SingleSignPrompt("CANCER", "Cancer / Kanser", "Grave / Somber", "[DISTRESSED]"),
        SingleSignPrompt("CIRCUMCISION", "Circumcision / Tuli", "Neutral / Clinical", "[STABLE]"),
        SingleSignPrompt("COMPRESSION", "CPR Compression / Pagbomba sa dibdib", "Urgent / Focused", "[ALERT]"),
        SingleSignPrompt("CONSTIPATION", "Constipation / Hindi makadumi", "Straining / Uncomfortable", "[AGITATED]"),
        SingleSignPrompt("CONSULT", "Consultation / Konsulta", "Inquiring / Calm", "[STABLE]"),
        SingleSignPrompt("COUGH", "Cough / Ubo", "Throat irritation / Fatigue", "[DISTRESSED]"),
        SingleSignPrompt("COVID_19", "COVID-19 / Covid", "Caution / Alert", "[ALERT]"),
        SingleSignPrompt("DEPRESSION", "Depression / Depresyon", "Downcast / Sad", "[DISTRESSED]"),
        SingleSignPrompt("DIABETES", "Diabetes / Dyabetis", "Clinical / Serious", "[STABLE]"),
        SingleSignPrompt("DIARRHEA", "Diarrhea / Pagtatae", "Abdominal distress", "[DISTRESSED]"),
        SingleSignPrompt("DIZZINESS", "Dizziness / Pagkahilo", "Disoriented / Eyes closed", "[DISTRESSED]"),
        SingleSignPrompt("DRY_MOUTH", "Dry mouth / Tuyong bibig", "Thirst / Discomfort", "[STABLE]"),
        SingleSignPrompt("EMERGENCY", "Emergency / Saklolo", "Urgent / High alert", "[DISTRESSED]"),
        SingleSignPrompt("FEVER", "Fever / Lagnat", "Weak / Flushed", "[DISTRESSED]"),
        SingleSignPrompt("FLU", "Flu / Trangkaso", "Malaise / Fatigued", "[DISTRESSED]"),
        SingleSignPrompt("GIVING_BIRTH", "Giving birth / Panganganak", "Intense labor pain", "[DISTRESSED]"),
        SingleSignPrompt("GOOD_AFTERNOON", "Good afternoon / Magandang hapon", "Friendly / Polite", "[STABLE]"),
        SingleSignPrompt("GOOD_EVENING", "Good evening / Magandang gabi", "Calm / Warm", "[STABLE]"),
        SingleSignPrompt("GOOD_MORNING", "Good morning / Magandang umaga", "Smiling / Warm greeting", "[RELIEVED]"),
        SingleSignPrompt("HEADACHE", "Headache / Sakit ng ulo", "Temple pain / Grimace", "[DISTRESSED]"),
        SingleSignPrompt("HEART_ATTACK", "Heart attack / Atake sa puso", "Agonized / Clutching chest", "[DISTRESSED]"),
        SingleSignPrompt("HELLO", "Hello / Kumusta", "Welcoming smile", "[RELIEVED]"),
        SingleSignPrompt("HI", "Hi / Bati", "Friendly wave / Smile", "[RELIEVED]"),
        SingleSignPrompt("HOSPITALIZED", "Hospitalized / Naospital", "Weary / Sick", "[DISTRESSED]"),
        SingleSignPrompt("HOW_ARE_YOU", "How are you? / Kumusta ka?", "Inquiring / Brow raise", "[ALERT]"),
        SingleSignPrompt("HOW_CAN_I_HELP_YOU", "How can I help you? / Paano kita matutulungan?", "Empathetic / Open", "[STABLE]"),
        SingleSignPrompt("HURT", "Hurt / Nasaktan", "Wincing / Sharp pain", "[DISTRESSED]"),
        SingleSignPrompt("HYPERTENSION", "Hypertension / Alta presyon", "Tense / High blood", "[ALERT]"),
        SingleSignPrompt("I_DONT_UNDERSTAND", "I do not understand / Hindi ko naiintindihan", "Confused / Brow furrow", "[ALERT]"),
        SingleSignPrompt("INJECTION", "Injection / Turok", "Apprehensive / Clinical", "[ALERT]"),
        SingleSignPrompt("INJURY", "Injury / Pinsala", "Pain / Distressed", "[DISTRESSED]"),
        SingleSignPrompt("IV", "IV Drip / Swero", "Patient / Calm", "[STABLE]"),
        SingleSignPrompt("LABORATORY", "Laboratory / Lab test", "Clinical / Attentive", "[STABLE]"),
        SingleSignPrompt("MAYBE", "Maybe / Baka", "Head tilt / Shrug", "[STABLE]"),
        SingleSignPrompt("MEDICINES", "Medicines / Gamot", "Seeking relief", "[STABLE]"),
        SingleSignPrompt("MENSTRUATION", "Menstruation / Regla", "Cramping / Discomfort", "[DISTRESSED]"),
        SingleSignPrompt("MUMPS", "Mumps / Beke", "Swollen cheek / Pain", "[DISTRESSED]"),
        SingleSignPrompt("NECK_PAIN", "Neck pain / Pananakit ng leeg", "Stiff / Wincing", "[DISTRESSED]"),
        SingleSignPrompt("NO", "No / Hindi", "Head shake / Negation", "[STABLE]"),
        SingleSignPrompt("PIGSA", "Boil / Pigsa", "Tender / Inflamed pain", "[DISTRESSED]"),
        SingleSignPrompt("PLEASE", "Please / Pakiusap", "Polite / Entreaty", "[STABLE]"),
        SingleSignPrompt("PREGNANT", "Pregnant / Buntis", "Gentle / Hopeful", "[RELIEVED]"),
        SingleSignPrompt("PRESCRIPTION", "Prescription / Reseta", "Attentive / Reading", "[STABLE]"),
        SingleSignPrompt("SICK", "Sick / May sakit", "Exhausted / Pale", "[DISTRESSED]"),
        SingleSignPrompt("SIPON", "Cold / Sipon", "Sniffling / Congested", "[DISTRESSED]"),
        SingleSignPrompt("SORE_THROAT", "Sore throat / Masakit ang lalamunan", "Difficulty swallowing", "[DISTRESSED]"),
        SingleSignPrompt("SURGERY", "Surgery / Operasyon", "Anxious / Serious", "[DISTRESSED]"),
        SingleSignPrompt("THANK_YOU", "Thank you / Salamat", "Warm smile / Gratitude", "[RELIEVED]"),
        SingleSignPrompt("THERMOMETER", "Thermometer / Termometro", "Patient / Waiting", "[STABLE]"),
        SingleSignPrompt("UNDERSTAND", "Understand / Naiintindihan", "Affirmative nod", "[RELIEVED]"),
        SingleSignPrompt("XRAY", "X-ray / Eksrey", "Still / Clinical", "[STABLE]"),
        SingleSignPrompt("YES", "Yes / Oo", "Affirmative nod / Smile", "[RELIEVED]")
    )

    val FULL_SENTENCE_PROMPTS = listOf(
        SentencePrompt(
            promptText = "HELLO HOW ARE YOU?",
            glosses = listOf("HELLO", "HOW_ARE_YOU"),
            referenceTranslations = listOf("Hello how are you?", "Hi how are you doing?", "Kumusta ka?"),
            expectedAffect = "[RELIEVED]"
        ),
        SentencePrompt(
            promptText = "EMERGENCY PLEASE CALL AMBULANCE",
            glosses = listOf("EMERGENCY", "PLEASE", "AMBULANCE"),
            referenceTranslations = listOf("Emergency please call an ambulance", "Emergency please send an ambulance", "Saklolo pakiusap tumawag ng ambulansya"),
            expectedAffect = "[DISTRESSED]"
        ),
        SentencePrompt(
            promptText = "I HAVE HEADACHE AND FEVER",
            glosses = listOf("HEADACHE", "FEVER", "SICK"),
            referenceTranslations = listOf("I have a headache and fever", "I have a headache and a fever and feel sick", "Masakit ang ulo ko at may lagnat ako"),
            expectedAffect = "[DISTRESSED]"
        ),
        SentencePrompt(
            promptText = "DIFFICULTY BREATHING NEED MEDICINES FOR ASTHMA",
            glosses = listOf("BREATHING", "MEDICINES", "ASTHMA"),
            referenceTranslations = listOf("I have difficulty breathing and need medicine for asthma", "Difficulty breathing need medicine for asthma", "Hindi makahinga kailangan ng gamot sa hika"),
            expectedAffect = "[DISTRESSED]"
        ),
        SentencePrompt(
            promptText = "ACCIDENT BROKEN BONE VERY HURT",
            glosses = listOf("ACCIDENT", "BROKEN_BONE", "HURT"),
            referenceTranslations = listOf("I had an accident with a broken bone it hurts", "Accident broken bone very hurt", "Aksidente may bali sa buto sobrang sakit"),
            expectedAffect = "[DISTRESSED]"
        ),
        SentencePrompt(
            promptText = "I DO NOT UNDERSTAND THE PRESCRIPTION",
            glosses = listOf("I_DONT_UNDERSTAND", "PRESCRIPTION"),
            referenceTranslations = listOf("I do not understand the prescription", "I cannot understand this prescription", "Hindi ko naiintindihan ang reseta"),
            expectedAffect = "[ALERT]"
        ),
        SentencePrompt(
            promptText = "NEED DOCTOR CONSULTATION AND HOSPITALIZED",
            glosses = listOf("CONSULT", "HOSPITALIZED"),
            referenceTranslations = listOf("I need to consult a doctor and be hospitalized", "Need doctor consultation and hospitalization", "Kailangan ng konsulta sa doktor at maospital"),
            expectedAffect = "[DISTRESSED]"
        ),
        SentencePrompt(
            promptText = "CHEST PAIN EMERGENCY POSSIBLE HEART ATTACK",
            glosses = listOf("EMERGENCY", "HURT", "HEART_ATTACK"),
            referenceTranslations = listOf("Emergency I am hurt possible heart attack", "Chest pain emergency possible heart attack", "Saklolo masakit ang dibdib inaatake sa puso"),
            expectedAffect = "[DISTRESSED]"
        ),
        SentencePrompt(
            promptText = "THANK YOU HOW CAN I HELP YOU",
            glosses = listOf("THANK_YOU", "HOW_CAN_I_HELP_YOU"),
            referenceTranslations = listOf("Thank you how can I help you", "Thanks how may I assist you", "Maraming salamat paano kita matutulungan"),
            expectedAffect = "[RELIEVED]"
        ),
        SentencePrompt(
            promptText = "I HAVE SORE THROAT COUGH AND FLU",
            glosses = listOf("SORE_THROAT", "COUGH", "FLU"),
            referenceTranslations = listOf("I have a sore throat cough and the flu", "Patient has sore throat cough and flu", "May masakit na lalamunan ubo at trangkaso"),
            expectedAffect = "[DISTRESSED]"
        ),
        SentencePrompt(
            promptText = "NEED LABORATORY BLOOD TEST AND XRAY",
            glosses = listOf("LABORATORY", "XRAY"),
            referenceTranslations = listOf("I need a laboratory test and an X-ray", "Need laboratory test and X-ray", "Kailangan ng laboratoryo at eksrey"),
            expectedAffect = "[STABLE]"
        ),
        SentencePrompt(
            promptText = "PREGNANT GIVING BIRTH NEED SURGERY",
            glosses = listOf("PREGNANT", "GIVING_BIRTH", "SURGERY"),
            referenceTranslations = listOf("I am pregnant and giving birth and need surgery", "Pregnant giving birth needs surgery", "Buntis manganganak kailangan ng operasyon"),
            expectedAffect = "[DISTRESSED]"
        ),
        SentencePrompt(
            promptText = "DIZZINESS AND BLURRED VISION HYPERTENSION",
            glosses = listOf("DIZZINESS", "BLURRED_VISION", "HYPERTENSION"),
            referenceTranslations = listOf("I have dizziness and blurred vision from hypertension", "Dizziness and blurred vision from hypertension", "Nahihilo malabo ang paningin alta presyon"),
            expectedAffect = "[ALERT]"
        ),
        SentencePrompt(
            promptText = "PLEASE CHECK TEMPERATURE WITH THERMOMETER",
            glosses = listOf("PLEASE", "THERMOMETER", "FEVER"),
            referenceTranslations = listOf("Please check temperature with thermometer", "Please take my temperature using a thermometer", "Pakiusap tingnan ang temperatura gamit ang termometro"),
            expectedAffect = "[STABLE]"
        ),
        SentencePrompt(
            promptText = "NEED INJECTION AND IV DRIP",
            glosses = listOf("INJECTION", "IV"),
            referenceTranslations = listOf("I need an injection and an IV drip", "Patient needs injection and IV drip", "Kailangan ng turok at swero"),
            expectedAffect = "[STABLE]"
        ),
        SentencePrompt(
            promptText = "SEVERE DIARRHEA AND CONSTIPATION",
            glosses = listOf("DIARRHEA", "CONSTIPATION"),
            referenceTranslations = listOf("I have severe diarrhea and constipation", "Severe diarrhea and constipation", "Matinding pagtatae at hindi makadumi"),
            expectedAffect = "[DISTRESSED]"
        ),
        SentencePrompt(
            promptText = "GOOD MORNING DO YOU UNDERSTAND YES",
            glosses = listOf("GOOD_MORNING", "UNDERSTAND", "YES"),
            referenceTranslations = listOf("Good morning do you understand yes", "Good morning do you understand affirmative", "Magandang umaga naiintindihan mo ba oo"),
            expectedAffect = "[RELIEVED]"
        ),
        SentencePrompt(
            promptText = "MAYBE INJURY FROM ACCIDENT",
            glosses = listOf("MAYBE", "INJURY", "ACCIDENT"),
            referenceTranslations = listOf("Maybe an injury from an accident", "Possible injury caused by an accident", "Baka may pinsala dahil sa aksidente"),
            expectedAffect = "[DISTRESSED]"
        ),
        SentencePrompt(
            promptText = "DRY MOUTH AND ALLERGIES NO FEVER",
            glosses = listOf("DRY_MOUTH", "ALLERGIES", "NO", "FEVER"),
            referenceTranslations = listOf("I have dry mouth and allergies but no fever", "Dry mouth and allergies no fever", "Tuyong bibig at alerhiya walang lagnat"),
            expectedAffect = "[STABLE]"
        ),
        SentencePrompt(
            promptText = "DEPRESSION AND NECK PAIN NEED HELP",
            glosses = listOf("DEPRESSION", "NECK_PAIN", "CONSULT"),
            referenceTranslations = listOf("I have depression and neck pain and need to consult", "Depression and neck pain need consultation", "Depresyon at pananakit ng leeg kailangan ng tulong"),
            expectedAffect = "[DISTRESSED]"
        )
    )

    val BREAKING_POINT_SIGNS = listOf(
        "HELLO", "GOOD_MORNING", "EMERGENCY", "FEVER", "HEADACHE",
        "BREATHING", "MEDICINES", "HURT", "PLEASE", "THANK_YOU"
    )

    val STRESS_CONDITIONS = listOf(
        StressCondition("cond_baseline", "Baseline (1.0m, Frontal, Plain Wall)", "Baseline: 1.0m frontal against plain wall", 1.0f, 0, 0, "Plain wall", "EMERGENCY"),
        StressCondition("cond_dist_05", "Distance 0.5m (Close boundary)", "Step close: 0.5m distance from camera", 0.5f, 0, 0, "Plain wall", "HEADACHE"),
        StressCondition("cond_dist_15", "Distance 1.5m (Mid boundary)", "Step back: 1.5m distance from camera", 1.5f, 0, 0, "Plain wall", "GOOD_MORNING"),
        StressCondition("cond_dist_20", "Distance 2.0m (Standing boundary)", "Step back: 2.0m distance from camera", 2.0f, 0, 0, "Plain wall", "FEVER"),
        StressCondition("cond_dist_30", "Distance 3.0m (Far breaking point)", "Maximum distance: 3.0m from camera", 3.0f, 0, 0, "Plain wall", "BREATHING"),
        StressCondition("cond_az_p15", "Azimuth +15° (Slight right)", "Turn body/camera 15° to the right", 1.0f, 15, 0, "Plain wall", "MEDICINES"),
        StressCondition("cond_az_m15", "Azimuth -15° (Slight left)", "Turn body/camera 15° to the left", 1.0f, -15, 0, "Plain wall", "HURT"),
        StressCondition("cond_az_p30", "Azimuth +30° (Moderate right)", "Turn body/camera 30° to the right", 1.0f, 30, 0, "Plain wall", "PLEASE"),
        StressCondition("cond_az_m30", "Azimuth -30° (Moderate left)", "Turn body/camera 30° to the left", 1.0f, -30, 0, "Plain wall", "THANK_YOU"),
        StressCondition("cond_az_p45", "Azimuth +45° (Extreme lateral)", "Turn body/camera 45° to the right (occlusion stress)", 1.0f, 45, 0, "Plain wall", "EMERGENCY"),
        StressCondition("cond_az_m45", "Azimuth -45° (Extreme lateral)", "Turn body/camera 45° to the left (occlusion stress)", 1.0f, -45, 0, "Plain wall", "FEVER"),
        StressCondition("cond_el_p20", "Elevation +20° (High tilt)", "Tilt camera +20° above signer looking down", 1.0f, 0, 20, "Plain wall", "HEADACHE"),
        StressCondition("cond_el_m20", "Elevation -20° (Low tilt)", "Tilt camera -20° below signer looking up", 1.0f, 0, -20, "Plain wall", "GOOD_MORNING"),
        StressCondition("cond_bg_clutter", "Background: Cluttered Room", "Position signer against indoor furniture/shelves", 1.0f, 0, 0, "Cluttered room", "EMERGENCY"),
        StressCondition("cond_bg_motion", "Background: Dynamic Motion", "Have people or moving objects in the background", 1.0f, 0, 0, "Moving background", "BREATHING"),
        StressCondition("cond_bg_skintone", "Background: Skin-Tone Match", "Position against warm wood or skin-toned surface", 1.0f, 0, 0, "Skin-tone match", "MEDICINES"),
        StressCondition("cond_lux_low", "Low Illumination (<50 lux)", "Dim room lights to low ambient level", 1.0f, 0, 0, "Plain wall", "HURT"),
        StressCondition("cond_lux_high", "Harsh Sunlight (>1000 lux)", "Bright outdoor or direct light source", 1.0f, 0, 0, "Plain wall", "PLEASE")
    )

    // ── Live Session State ─────────────────────────────────────────────────────

    @Volatile var activePhase: TestPhase = TestPhase.TEST_1_SINGLE_SIGNS
    @Volatile var singleSignIndex: Int = 0
    @Volatile var sentenceIndex: Int = 0
    @Volatile var breakingPointSignIndex: Int = 0
    @Volatile var stressConditionIndex: Int = 0

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

    // Operational boundary variables
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
            return BleuScore(0.0, 0.0, 0.0, 0.0, 0.0)
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

        return BleuScore(
            bleu1 = (cumBleu(1) * 100).coerceIn(0.0, 100.0) / 100.0,
            bleu2 = (cumBleu(2) * 100).coerceIn(0.0, 100.0) / 100.0,
            bleu3 = (cumBleu(3) * 100).coerceIn(0.0, 100.0) / 100.0,
            bleu4 = (cumBleu(4) * 100).coerceIn(0.0, 100.0) / 100.0,
            brevityPenalty = bp
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

    // ── Log File Access & Storage ─────────────────────────────────────────────

    internal fun getLogFile(baseDir: File): File {
        val dir = File(baseDir, LOGS_DIR_NAME)
        if (!dir.exists()) dir.mkdirs()
        val file = File(dir, TELEMETRY_FILE_NAME)
        if (!file.exists() || file.length() == 0L) {
            file.writeText(CSV_HEADER + "\n")
        }
        return file
    }

    private fun getLogFile(context: Context): File = getLogFile(context.filesDir)

    /**
     * Checks if test logs are available in given directory and have recorded at least one data row.
     */
    internal fun hasLogsInDir(baseDir: File): Boolean {
        val file = File(baseDir, "$LOGS_DIR_NAME/$TELEMETRY_FILE_NAME")
        if (!file.exists() || file.length() <= CSV_HEADER.length + 2) return false
        return try {
            file.useLines { lines -> lines.drop(1).any { it.isNotBlank() } }
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Checks if test logs are available and have recorded at least one data row.
     */
    fun hasLogs(context: Context): Boolean = hasLogsInDir(context.filesDir)

    internal fun clearLogsInDir(baseDir: File) {
        val dir = File(baseDir, LOGS_DIR_NAME)
        if (dir.exists()) {
            dir.deleteRecursively()
        }
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
        sentenceIndex = 0
        breakingPointSignIndex = 0
        stressConditionIndex = 0
        currentIteration = 1
        distanceMeters = 1.0f
        azimuthAngleDeg = 0
        elevationAngleDeg = 0
        backgroundComplexity = "Plain wall"
        signerId = "Signer_01"
    }

    /**
     * Completely wipes all preexisting test logs and resets memory telemetry.
     * Called whenever Test Mode is turned OFF.
     */
    fun clearLogs(context: Context) {
        try {
            clearLogsInDir(context.filesDir)
            Log.d(TAG, "All preexisting test logs and telemetry wiped successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Error clearing test logs: ${e.message}", e)
        }
    }

    /**
     * Exports the accumulated telemetry CSV to the user's public Downloads directory.
     * Returns the human-readable path or filename if successful, null otherwise.
     */
    fun exportLogsToDownloads(context: Context): String? {
        val sourceFile = File(context.filesDir, "$LOGS_DIR_NAME/$TELEMETRY_FILE_NAME")
        if (!sourceFile.exists() || sourceFile.length() <= CSV_HEADER.length) return null

        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val exportFileName = "motionspeak_test_telemetry_$timeStamp.csv"

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
            Log.e(TAG, "Failed to export logs to Downloads: ${e.message}", e)
            null
        }
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

    /**
     * Logs single-sign evaluation row (Test 1, Test 3, or Test 4).
     */
    fun logSingleSign(
        context: Context,
        prompt: SingleSignPrompt,
        decision: PredictionDecision,
        mediapipeLatencyMs: Double,
        e2eLatencyMs: Long,
        fps: Double,
        mediapipeConfidence: Float = 0.5f,
        cameraToBitmapMs: Double = 0.0,
        detectedEmotion: String = "neutral",
        facialAffect: String = "[STABLE]",
        facialConfidence: Float = 0.5f,
        tLlmMs: Double = 0.0,
        tTtsMs: Double = 0.0
    ) {
        logSingleSign(
            context = context,
            promptStr = prompt.gloss,
            expectedAffect = prompt.expectedAffect,
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
        expectedAffect: String = "[STABLE]",
        decision: PredictionDecision,
        mediapipeLatencyMs: Double,
        e2eLatencyMs: Long,
        fps: Double,
        mediapipeConfidence: Float = 0.5f,
        cameraToBitmapMs: Double = 0.0,
        detectedEmotion: String = "neutral",
        facialAffect: String = "[STABLE]",
        facialConfidence: Float = 0.5f,
        tLlmMs: Double = 0.0,
        tTtsMs: Double = 0.0
    ) {
        val isTestMode = AppSettings.getInstance(context).isTestModeEnabled
        if (!isTestMode) return

        val nowIso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).format(Date())
        val realtimeMs = SystemClock.elapsedRealtime()
        val deviceTier = detectDeviceTier(context)
        val delegateType = if (AppSettings.getInstance(context).isInferenceGpuEnabled) "GPU" else "CPU"

        val normPrompt = promptStr.trim().uppercase(Locale.ROOT).replace("[^A-Z0-9_]".toRegex(), "")
        val normPred = decision.topLabel.trim().uppercase(Locale.ROOT).replace("[^A-Z0-9_]".toRegex(), "")
        val isGestureCorrect = normPrompt.isNotEmpty() && (normPrompt == normPred || normPrompt.contains(normPred) || normPred.contains(normPrompt))

        val normExpectedAffect = expectedAffect.trim().uppercase(Locale.ROOT).removeSurrounding("[", "]")
        val normDetectedAffect = facialAffect.trim().uppercase(Locale.ROOT).removeSurrounding("[", "]")
        val normTriageFromDetected = ToneMapper.toTriageAffect(detectedEmotion).uppercase(Locale.ROOT).removeSurrounding("[", "]")

        val isEmotionCorrect = normExpectedAffect.isEmpty() ||
            (normExpectedAffect == "STABLE" && normDetectedAffect == "STABLE") ||
            (normExpectedAffect == normDetectedAffect) ||
            (normExpectedAffect == normTriageFromDetected) ||
            expectedAffect.equals(detectedEmotion, ignoreCase = true)

        val isCombinedCorrect = isGestureCorrect && isEmotionCorrect

        latestPredictionLabel = decision.topLabel
        latestConfidence = decision.confidence
        latestIsMatch = isCombinedCorrect
        latestLiveFps = fps
        latestE2eLatencyMs = e2eLatencyMs
        latestMediaPipeMs = mediapipeLatencyMs
        latestTfliteMs = decision.tfliteLatencyMs
        latestLlmMs = tLlmMs
        latestTtsMs = tTtsMs

        val altsFormatted = decision.alternatives.joinToString(";") { "${it.label}:${String.format(Locale.US, "%.2f", it.confidence)}" }

        val row = listOf(
            nowIso,
            realtimeMs.toString(),
            activePhase.id,
            currentIteration.toString(),
            escapeCsv(promptStr),
            escapeCsv(expectedAffect),
            escapeCsv(detectedEmotion),
            escapeCsv(facialAffect),
            String.format(Locale.US, "%.4f", facialConfidence),
            "", // no reference sentences for single glosses
            escapeCsv(decision.topLabel),
            "", // no sentence synthesis
            String.format(Locale.US, "%.4f", decision.confidence),
            decision.tier.name,
            escapeCsv(altsFormatted),
            String.format(Locale.US, "%.4f", mediapipeConfidence),
            String.format(Locale.US, "%.2f", cameraToBitmapMs),
            String.format(Locale.US, "%.2f", mediapipeLatencyMs),
            String.format(Locale.US, "%.2f", decision.tfliteLatencyMs),
            String.format(Locale.US, "%.2f", tLlmMs),
            String.format(Locale.US, "%.2f", tTtsMs),
            e2eLatencyMs.toString(),
            String.format(Locale.US, "%.1f", fps),
            if (currentLux >= 0f) String.format(Locale.US, "%.1f", currentLux) else "N/A",
            String.format(Locale.US, "%.2f", distanceMeters),
            azimuthAngleDeg.toString(),
            elevationAngleDeg.toString(),
            escapeCsv(backgroundComplexity),
            escapeCsv(signerId),
            escapeCsv(deviceTier),
            delegateType,
            if (isCombinedCorrect) "1" else "0",
            "", "", "", "" // no BLEU scores
        ).joinToString(",")

        csvExecutor.execute {
            try {
                val file = getLogFile(context)
                FileOutputStream(file, true).bufferedWriter().use { it.write(row + "\n") }
            } catch (e: Exception) {
                Log.e(TAG, "Error writing single sign telemetry: ${e.message}", e)
            }
        }
    }

    /**
     * Logs full sentence synthesis row with BLEU-1 through BLEU-4 metrics (Test 2).
     */
    fun logSentence(
        context: Context,
        prompt: SentencePrompt,
        result: SentenceResult,
        fps: Double,
        detectedEmotion: String = "neutral",
        facialAffect: String = "[STABLE]",
        facialConfidence: Float = 0.5f,
        tLlmMs: Double = 0.0,
        tTtsMs: Double = 0.0,
        e2eLatencyMs: Long = 0L
    ) {
        val isTestMode = AppSettings.getInstance(context).isTestModeEnabled
        if (!isTestMode) return

        val bleu = calculateBleu(result.text, prompt.referenceTranslations)
        latestBleuScore = bleu

        val nowIso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).format(Date())
        val realtimeMs = SystemClock.elapsedRealtime()
        val deviceTier = detectDeviceTier(context)
        val delegateType = if (AppSettings.getInstance(context).isInferenceGpuEnabled) "GPU" else "CPU"
        val refString = prompt.referenceTranslations.joinToString(" | ")

        val explicitLlmMs = if (tLlmMs > 0.0) tLlmMs else (if (result.latencyMs > 0L) result.latencyMs.toDouble() else latestLlmMs)
        val finalE2e = if (e2eLatencyMs > 0L) e2eLatencyMs else (explicitLlmMs.toLong() + tTtsMs.toLong())

        latestLiveFps = fps
        latestE2eLatencyMs = finalE2e
        latestLlmMs = explicitLlmMs
        latestTtsMs = tTtsMs

        val row = listOf(
            nowIso,
            realtimeMs.toString(),
            TestPhase.TEST_2_FULL_SENTENCES.id,
            currentIteration.toString(),
            escapeCsv(prompt.promptText),
            escapeCsv(prompt.expectedAffect),
            escapeCsv(detectedEmotion),
            escapeCsv(facialAffect),
            String.format(Locale.US, "%.4f", facialConfidence),
            escapeCsv(refString),
            escapeCsv(result.originalTokens.joinToString(" ")),
            escapeCsv(result.text),
            "1.0000",
            ConfidenceTier.HIGH.name,
            "",
            "1.0000",
            "0.00",
            "0.00",
            "0.00",
            String.format(Locale.US, "%.2f", explicitLlmMs),
            String.format(Locale.US, "%.2f", tTtsMs),
            finalE2e.toString(),
            String.format(Locale.US, "%.1f", fps),
            if (currentLux >= 0f) String.format(Locale.US, "%.1f", currentLux) else "N/A",
            String.format(Locale.US, "%.2f", distanceMeters),
            azimuthAngleDeg.toString(),
            elevationAngleDeg.toString(),
            escapeCsv(backgroundComplexity),
            escapeCsv(signerId),
            escapeCsv(deviceTier),
            delegateType,
            "1",
            String.format(Locale.US, "%.4f", bleu.bleu1),
            String.format(Locale.US, "%.4f", bleu.bleu2),
            String.format(Locale.US, "%.4f", bleu.bleu3),
            String.format(Locale.US, "%.4f", bleu.bleu4)
        ).joinToString(",")

        csvExecutor.execute {
            try {
                val file = getLogFile(context)
                FileOutputStream(file, true).bufferedWriter().use { it.write(row + "\n") }
            } catch (e: Exception) {
                Log.e(TAG, "Error writing sentence telemetry: ${e.message}", e)
            }
        }
    }
}
