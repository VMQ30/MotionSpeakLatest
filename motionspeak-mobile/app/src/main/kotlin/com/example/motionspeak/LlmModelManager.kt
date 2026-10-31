package com.example.motionspeak

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Metadata describing an on-device LLM model package.
 */
data class LlmMetadata(
    val version: String,
    val modelName: String,
    val format: String,
    val sizeBytes: Long,
    val sha256: String?,
    val installedAtTimestamp: Long = System.currentTimeMillis()
)

data class LlmDownloadProgress(
    val bytesRead: Long,
    val totalBytes: Long,
    val percent: Int
)

/**
 * Manages downloading, local storage, integrity verification, and lifecycle of the on-device LLM model.
 */
object LlmModelManager {

    private const val TAG = "LlmModelManager"
    private const val MODEL_DIR = "llm_models"
    private const val MODEL_FILENAME = "gemma-3-1b-it-int4.task"
    private const val META_FILENAME = "llm_meta.json"

    // Default metadata for Gemma 3 1B int4
    const val DEFAULT_MODEL_VERSION = "1.0.0"
    const val DEFAULT_MODEL_NAME = "Gemma 3 1B Int4"

    private val gson = Gson()
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    private fun getModelDir(context: Context): File =
        File(context.filesDir, MODEL_DIR).apply { if (!exists()) mkdirs() }

    fun getModelFile(context: Context): File =
        File(getModelDir(context), MODEL_FILENAME)

    private fun getMetaFile(context: Context): File =
        File(getModelDir(context), META_FILENAME)

    /**
     * Checks if the model exists and has non-zero size.
     */
    fun hasModel(context: Context): Boolean {
        val file = getModelFile(context)
        return file.exists() && file.length() > 0
    }

    /**
     * Returns formatted size string (e.g. "548 MB") or "Not installed".
     */
    fun getFormattedModelSize(context: Context): String {
        val file = getModelFile(context)
        if (!file.exists() || file.length() == 0L) return "Not installed"
        val mb = file.length().toDouble() / (1024 * 1024)
        return String.format("%.1f MB", mb)
    }

    /**
     * Reads saved metadata.
     */
    fun getMetadata(context: Context): LlmMetadata? {
        val metaFile = getMetaFile(context)
        if (!metaFile.exists()) return null
        return try {
            gson.fromJson(metaFile.readText(), LlmMetadata::class.java)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse LLM metadata: ${e.message}")
            null
        }
    }

    /**
     * Computes the SHA-256 hash of a file.
     */
    fun computeSha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(32768)
            var read: Int
            while (input.read(buffer).also { read = it } != -1) {
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * Verifies file integrity against expected SHA-256 hash.
     */
    fun verifyIntegrity(context: Context, expectedSha256: String?): Boolean {
        val file = getModelFile(context)
        if (!file.exists() || file.length() == 0L) return false
        if (expectedSha256.isNullOrBlank()) {
            Log.e(TAG, "Integrity verification failed: expected SHA-256 is missing")
            return false
        }
        val actual = computeSha256(file)
        return actual.equals(expectedSha256.trim(), ignoreCase = true)
    }

    /**
     * Deletes the installed model and metadata.
     */
    fun deleteModel(context: Context): Boolean {
        var deleted = true
        val file = getModelFile(context)
        if (file.exists() && !file.delete()) deleted = false
        val meta = getMetaFile(context)
        if (meta.exists() && !meta.delete()) deleted = false
        Log.i(TAG, "Deleted LLM model files: success=$deleted")
        return deleted
    }

    /**
     * Imports a model file from external/local storage into the internal app sandbox.
     */
    suspend fun importLocalModelFile(
        context: Context,
        source: File,
        version: String = DEFAULT_MODEL_VERSION,
        expectedSha256: String? = null
    ): Boolean = withContext(Dispatchers.IO) {
        if (!source.exists() || source.length() == 0L) {
            Log.e(TAG, "Source model file does not exist: ${source.absolutePath}")
            return@withContext false
        }

        val stagingDir = File(getModelDir(context), ".staging").apply { mkdirs() }
        val stagingFile = File(stagingDir, "model.staging")

        try {
            FileInputStream(source).use { input ->
                FileOutputStream(stagingFile).use { output ->
                    input.copyTo(output)
                }
            }

            if (!expectedSha256.isNullOrBlank()) {
                val actualSha256 = computeSha256(stagingFile)
                if (!actualSha256.equals(expectedSha256.trim(), ignoreCase = true)) {
                    Log.e(TAG, "Import SHA-256 mismatch! Expected=$expectedSha256, Actual=$actualSha256")
                    stagingFile.delete()
                    return@withContext false
                }
            }

            val targetFile = getModelFile(context)
            if (targetFile.exists()) targetFile.delete()

            if (!stagingFile.renameTo(targetFile)) {
                stagingFile.copyTo(targetFile, overwrite = true)
                stagingFile.delete()
            }

            val meta = LlmMetadata(
                version = version,
                modelName = DEFAULT_MODEL_NAME,
                format = "task",
                sizeBytes = targetFile.length(),
                sha256 = expectedSha256 ?: computeSha256(targetFile)
            )
            getMetaFile(context).writeText(gson.toJson(meta))
            Log.i(TAG, "Successfully imported LLM model (${targetFile.length()} bytes)")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to import LLM model: ${e.message}", e)
            stagingFile.delete()
            false
        }
    }

    /**
     * Downloads an LLM model with streaming SHA-256 validation, staging, and atomic swap.
     */
    suspend fun downloadModel(
        context: Context,
        url: String,
        expectedSha256: String?,
        version: String = DEFAULT_MODEL_VERSION,
        callHolder: AtomicReference<Call?>? = null,
        onProgress: ((LlmDownloadProgress) -> Unit)? = null
    ): Boolean = withContext(Dispatchers.IO) {
        val stagingDir = File(getModelDir(context), ".staging").apply { mkdirs() }
        val stagingFile = File(stagingDir, "download.staging")
        if (stagingFile.exists()) stagingFile.delete()

        try {
            val request = Request.Builder().url(url).build()
            val call = httpClient.newCall(request)
            callHolder?.set(call)

            call.execute().use { response ->
                if (!response.isSuccessful) {
                    Log.e(TAG, "Download failed with HTTP ${response.code}")
                    return@withContext false
                }
                val body = response.body ?: return@withContext false
                                val totalBytes = body.contentLength()
                val maxAllowedBytes = 3L * 1024 * 1024 * 1024 // 3 GB max
                if (totalBytes > maxAllowedBytes) {
                    Log.e(TAG, "Model download exceeds maximum permitted size: $totalBytes bytes")
                    return@withContext false
                }
                val requiredUsableSpace = if (totalBytes > 0) totalBytes * 2 else 500L * 1024 * 1024
                if (context.filesDir.usableSpace < requiredUsableSpace) {
                    Log.e(TAG, "Insufficient disk space on device for model download")
                    return@withContext false
                }
                if (expectedSha256.isNullOrBlank()) {
                    Log.e(TAG, "Refusing unverified download: expected SHA-256 checksum is required")
                    return@withContext false
                }
                var bytesRead = 0L

                val digest = MessageDigest.getInstance("SHA-256")

                body.byteStream().use { input ->
                    FileOutputStream(stagingFile).use { output ->
                        val buffer = ByteArray(32768)
                        var read: Int
                        while (input.read(buffer).also { read = it } != -1) {
                            output.write(buffer, 0, read)
                            digest.update(buffer, 0, read)
                            bytesRead += read
                            val pct = if (totalBytes > 0) ((bytesRead * 100) / totalBytes).toInt() else 0
                            onProgress?.invoke(LlmDownloadProgress(bytesRead, totalBytes, pct))
                        }
                    }
                }

                if (!expectedSha256.isNullOrBlank()) {
                    val actualSha = digest.digest().joinToString("") { "%02x".format(it) }
                    if (!actualSha.equals(expectedSha256.trim(), ignoreCase = true)) {
                        Log.e(TAG, "Checksum mismatch! Expected=$expectedSha256, Actual=$actualSha")
                        stagingFile.delete()
                        return@withContext false
                    }
                }

                val target = getModelFile(context)
                if (target.exists()) target.delete()

                if (!stagingFile.renameTo(target)) {
                    stagingFile.copyTo(target, overwrite = true)
                    stagingFile.delete()
                }

                val meta = LlmMetadata(
                    version = version,
                    modelName = DEFAULT_MODEL_NAME,
                    format = "task",
                    sizeBytes = target.length(),
                    sha256 = expectedSha256
                )
                getMetaFile(context).writeText(gson.toJson(meta))
                Log.i(TAG, "LLM model downloaded and verified (${target.length()} bytes)")
                true
            }
        } catch (e: Exception) {
            Log.e(TAG, "Download failed with exception: ${e.message}", e)
            stagingFile.delete()
            false
        } finally {
            callHolder?.set(null)
        }
    }
}
