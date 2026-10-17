package com.example.sigla

import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.facebook.react.bridge.*
import com.facebook.react.modules.core.DeviceEventManagerModule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.URL

class SiglaBridgeModule(private val reactContext: ReactApplicationContext) :
    ReactContextBaseJavaModule(reactContext) {

    private val scope = CoroutineScope(Dispatchers.Main + Job())
    private var speechPlayer: SpeechPlayer? = null
    private var isRecording = false
    private var recordingStartTime = 0L
    private val handler = Handler(Looper.getMainLooper())
    private var bulkDownloadJob: Job? = null
    private var isBulkDownloading = false

    override fun getName(): String = "SiglaBridge"

    companion object {
        private const val TAG = "SiglaBridgeModule"
        @Volatile var currentInstance: SiglaBridgeModule? = null
    }

    init {
        currentInstance = this
        Handler(Looper.getMainLooper()).post {
            val appSettings = AppSettings.getInstance(reactContext)
            speechPlayer = SpeechPlayer(reactContext, appSettings, scope)
        }
    }

    private fun sendEvent(eventName: String, params: WritableMap?) {
        try {
            reactContext
                .getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter::class.java)
                ?.emit(eventName, params)
        } catch (e: Exception) {
            Log.e(TAG, "Error emitting event $eventName", e)
        }
    }

    // ── Emit methods called from native engine ────────────────────────────────

    fun emitTranslationResult(word: String, confidence: Int, filipino: String?, gestureType: String) {
        val map = Arguments.createMap().apply {
            putString("word", word)
            putInt("confidence", confidence)
            if (filipino != null) putString("filipino", filipino)
            putString("gestureType", gestureType)
        }
        sendEvent("onTranslationResult", map)
    }

    fun emitDetectionStatus(
        status: String,
        frames: Int,
        bufferPercent: Int,
        velocity: String,
        streak: Int,
        handsWarning: Boolean
    ) {
        val map = Arguments.createMap().apply {
            putString("status", status)
            putInt("frames", frames)
            putInt("bufferPercent", bufferPercent)
            putString("velocity", velocity)
            putInt("streak", streak)
            putBoolean("handsWarning", handsWarning)
        }
        sendEvent("onDetectionStatus", map)
    }

    fun emitTapProgress(elapsedSeconds: Double, maxSeconds: Double, progressPercent: Int, isRecording: Boolean) {
        val map = Arguments.createMap().apply {
            putDouble("elapsedSeconds", elapsedSeconds)
            putDouble("maxSeconds", maxSeconds)
            putInt("progressPercent", progressPercent)
            putBoolean("isRecording", isRecording)
        }
        sendEvent("onTapProgress", map)
    }

    // ── Initial Data ──────────────────────────────────────────────────────────

    @ReactMethod
    fun getInitialData(promise: Promise) {
        scope.launch(Dispatchers.IO) {
            try {
                val appSettings = AppSettings.getInstance(reactContext)
                val favManager = FavoritesManager.getInstance(reactContext)
                val historyManager = TranslationHistoryManager.getInstance(reactContext)

                val rootMap = Arguments.createMap()

                // Settings
                val settingsMap = Arguments.createMap().apply {
                    putInt("volume", appSettings.volume)
                    putString("voiceType", appSettings.voiceType)
                    putBoolean("isDarkMode", appSettings.isDarkMode)
                    putBoolean("showFilipino", appSettings.showFilipino)
                    putBoolean("isFrontCamera", appSettings.isFrontCamera)
                    putString("translationMode", appSettings.translationMode)
                    putBoolean("isOnboardingDone", appSettings.isOnboardingDone)
                    appSettings.userName?.let { putString("userName", it) }
                }
                rootMap.putMap("settings", settingsMap)

                // Favorites
                val favArray = Arguments.createArray()
                favManager.getAll().forEach { favArray.pushInt(it) }
                rootMap.putArray("favorites", favArray)

                // History
                val historyArray = Arguments.createArray()
                historyManager.getAll().forEach { entry ->
                    val entryMap = Arguments.createMap().apply {
                        putString("word", entry.word)
                        putInt("confidence", entry.confidence)
                        putString("gestureType", entry.gestureType)
                        putDouble("timestamp", entry.timestamp.toDouble())
                    }
                    historyArray.pushMap(entryMap)
                }
                rootMap.putArray("history", historyArray)

                // Cached words
                val cachedWords = ModelUpdateManager.loadCachedWordBank(reactContext).orEmpty()
                val wordsArray = Arguments.createArray()
                cachedWords.forEach { w ->
                    val wMap = Arguments.createMap().apply {
                        putInt("id", w.id)
                        putString("label", w.label)
                        w.description?.let { putString("description", it) }
                        putString("sign_type", w.sign_type)
                        putString("category", w.category)
                        w.thumbnail_url?.let { putString("thumbnail_url", it) }
                        w.video_url?.let { putString("video_url", it) }
                        w.filipino_translation?.let { putString("filipino_translation", it) }
                        w.vocabulary?.let { putString("vocabulary", it) }
                    }
                    wordsArray.pushMap(wMap)
                }
                rootMap.putArray("cachedWords", wordsArray)

                // Cached categories
                val cachedCategories = ModelUpdateManager.loadCachedCategories(reactContext).orEmpty()
                val catsArray = Arguments.createArray()
                cachedCategories.forEach { c ->
                    val cMap = Arguments.createMap().apply {
                        putInt("id", c.id)
                        putString("name", c.name)
                        c.description?.let { putString("description", it) }
                        putInt("word_count", c.word_count)
                    }
                    catsArray.pushMap(cMap)
                }
                rootMap.putArray("cachedCategories", catsArray)

                promise.resolve(rootMap)
            } catch (e: Exception) {
                promise.reject("ERR_INITIAL_DATA", e.message, e)
            }
        }
    }

    // ── Settings ─────────────────────────────────────────────────────────────

    @ReactMethod
    fun saveSettings(settingsMap: ReadableMap) {
        val appSettings = AppSettings.getInstance(reactContext)
        if (settingsMap.hasKey("volume")) appSettings.volume = settingsMap.getInt("volume")
        if (settingsMap.hasKey("voiceType")) appSettings.voiceType = settingsMap.getString("voiceType") ?: "female"
        if (settingsMap.hasKey("isDarkMode")) appSettings.isDarkMode = settingsMap.getBoolean("isDarkMode")
        if (settingsMap.hasKey("showFilipino")) appSettings.showFilipino = settingsMap.getBoolean("showFilipino")
        if (settingsMap.hasKey("isFrontCamera")) appSettings.isFrontCamera = settingsMap.getBoolean("isFrontCamera")
        if (settingsMap.hasKey("translationMode")) appSettings.translationMode = settingsMap.getString("translationMode") ?: "tap"
        if (settingsMap.hasKey("isOnboardingDone")) appSettings.isOnboardingDone = settingsMap.getBoolean("isOnboardingDone")
        if (settingsMap.hasKey("userName")) {
            val name = settingsMap.getString("userName")
            appSettings.userName = if (name.isNullOrBlank()) null else name
        }
    }

    // ── Favorites ────────────────────────────────────────────────────────────

    @ReactMethod
    fun saveFavorites(favoritesArray: ReadableArray) {
        val favManager = FavoritesManager.getInstance(reactContext)
        val currentFavs = favManager.getAll()
        val newFavs = mutableSetOf<Int>()
        for (i in 0 until favoritesArray.size()) {
            newFavs.add(favoritesArray.getInt(i))
        }
        // Sync diff
        currentFavs.forEach { if (it !in newFavs) favManager.toggle(it) }
        newFavs.forEach { if (it !in currentFavs) favManager.toggle(it) }
    }

    // ── History ──────────────────────────────────────────────────────────────

    @ReactMethod
    fun addHistoryEntry(entryMap: ReadableMap) {
        val word = entryMap.getString("word") ?: return
        val confidence = if (entryMap.hasKey("confidence")) entryMap.getInt("confidence") else 90
        val gestureType = entryMap.getString("gestureType") ?: "tap"
        TranslationHistoryManager.getInstance(reactContext).add(word, confidence, gestureType)
    }

    @ReactMethod
    fun clearHistory() {
        TranslationHistoryManager.getInstance(reactContext).clearAll()
    }

    // ── Speech Output ────────────────────────────────────────────────────────

    @ReactMethod
    fun speak(text: String, volume: Double, voiceType: String) {
        val appSettings = AppSettings.getInstance(reactContext)
        appSettings.volume = volume.toInt().coerceIn(0, 100)
        appSettings.voiceType = voiceType
        speechPlayer?.speak(text, queueUntilReady = true)
    }

    // ── Camera & ML Controls ─────────────────────────────────────────────────

    @ReactMethod
    fun flipCamera() {
        SiglaCameraViewManager.flipCamera()
    }

    @ReactMethod
    fun setCameraFacing(facing: String) {
        SiglaCameraViewManager.setCameraFacing(facing == "front")
    }

    @ReactMethod
    fun startRecognition(mode: String, vocabulary: String) {
        SiglaCameraViewManager.startRecognition(mode, vocabulary)
    }

    @ReactMethod
    fun stopRecognition() {
        SiglaCameraViewManager.stopRecognition()
    }

    @ReactMethod
    fun startTapRecording() {
        if (isRecording) return
        isRecording = true
        recordingStartTime = System.currentTimeMillis()
        SiglaCameraViewManager.startTapRecording()

        // 2-second progress ticker
        val ticker = object : Runnable {
            override fun run() {
                if (!isRecording) return
                val elapsed = (System.currentTimeMillis() - recordingStartTime) / 1000.0
                val progress = ((elapsed / 2.0) * 100).toInt().coerceIn(0, 100)
                emitTapProgress(elapsed, 2.0, progress, isRecording)
                if (elapsed >= 2.0) {
                    stopTapRecording()
                } else {
                    handler.postDelayed(this, 100)
                }
            }
        }
        handler.post(ticker)
    }

    @ReactMethod
    fun stopTapRecording() {
        if (!isRecording) return
        isRecording = false
        SiglaCameraViewManager.stopTapRecording()
        emitTapProgress(2.0, 2.0, 100, false)
    }

    @ReactMethod
    fun triggerSOS() {
        try {
            val alert = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            val mp = MediaPlayer.create(reactContext, alert)
            mp?.start()
            speak("Emergency alert! Please help me.", 100.0, "female")
        } catch (e: Exception) {
            Log.e(TAG, "SOS error", e)
        }
    }

    // ── Model Status & Download ──────────────────────────────────────────────

    @ReactMethod
    fun getModelStatus(promise: Promise) {
        val version = ModelUpdateManager.getCachedVersion(reactContext) ?: "v1.10"
        val hasLocal = ModelUpdateManager.hasLocalModel(reactContext)
        val map = Arguments.createMap().apply {
            putString("version", version)
            putBoolean("hasLocalModel", hasLocal)
        }
        promise.resolve(map)
    }

    @ReactMethod
    fun checkAndUpdateModel(promise: Promise) {
        scope.launch(Dispatchers.IO) {
            try {
                val updated = ModelUpdateManager.checkAndUpdate(reactContext)
                val version = ModelUpdateManager.getCachedVersion(reactContext)
                val map = Arguments.createMap().apply {
                    putBoolean("updated", updated)
                    putString("version", version)
                }
                promise.resolve(map)
            } catch (e: Exception) {
                promise.reject("ERR_MODEL_UPDATE", e.message, e)
            }
        }
    }

    // ── Demo Video Download ──────────────────────────────────────────────────

    @ReactMethod
    fun downloadDemoVideo(url: String, wordId: Int) {
        scope.launch(Dispatchers.IO) {
            try {
                val resolvedUrl = ApiClient.resolveUrl(url) ?: return@launch
                val file = File(reactContext.filesDir, "demo_video_$wordId.mp4")
                URL(resolvedUrl).openStream().use { input ->
                    FileOutputStream(file).use { output ->
                        input.copyTo(output)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error downloading demo video", e)
            }
        }
    }

    @ReactMethod
    fun downloadAllDemoVideos() {
        if (isBulkDownloading) return
        isBulkDownloading = true

        bulkDownloadJob = scope.launch(Dispatchers.IO) {
            try {
                val words = ModelUpdateManager.loadCachedWordBank(reactContext).orEmpty()
                val total = words.filter { !it.video_url.isNullOrBlank() }.size
                var completed = 0

                for (word in words) {
                    if (!isBulkDownloading) break
                    val url = word.video_url
                    if (!url.isNullOrBlank()) {
                        val progressMap = Arguments.createMap().apply {
                            putInt("completed", completed)
                            putInt("total", total)
                            putString("status", "downloading")
                            putString("message", word.label)
                        }
                        sendEvent("onVideoDownloadProgress", progressMap)

                        try {
                            val resolvedUrl = ApiClient.resolveUrl(url)
                            if (resolvedUrl != null) {
                                val file = File(reactContext.filesDir, "demo_video_${word.id}.mp4")
                                URL(resolvedUrl).openStream().use { input ->
                                    FileOutputStream(file).use { output ->
                                        input.copyTo(output)
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed video download for ${word.label}", e)
                        }
                        completed++
                    }
                }

                val finalMap = Arguments.createMap().apply {
                    putInt("completed", completed)
                    putInt("total", total)
                    putString("status", if (isBulkDownloading) "finished" else "cancelled")
                }
                sendEvent("onVideoDownloadProgress", finalMap)
            } finally {
                isBulkDownloading = false
            }
        }
    }

    @ReactMethod
    fun cancelDownloadAllVideos() {
        isBulkDownloading = false
        bulkDownloadJob?.cancel()
        val cancelMap = Arguments.createMap().apply {
            putString("status", "cancelled")
        }
        sendEvent("onVideoDownloadProgress", cancelMap)
    }
}
