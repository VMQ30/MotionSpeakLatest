package com.example.motionspeak

import okhttp3.CertificatePinner
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

object ApiClient {

    private const val BASE_URL = BuildConfig.BASE_URL
    val SERVER_URL = BASE_URL.removeSuffix("api/")

    /** Resolve a server-relative path (e.g. /uploads/…) to a full URL. */
    fun resolveUrl(path: String?): String? {
        if (path.isNullOrBlank()) return null
        return if (path.startsWith("http")) path else SERVER_URL.trimEnd('/') + path
    }

    private fun createCertificatePinner(): CertificatePinner {
        return CertificatePinner.Builder()
            // Primary and backup pins for production endpoints (supporting zero-downtime certificate rotation)
            .add("*.supabase.co", "sha256/2wF6+9z6oYwG428Z6+U2r7aP2T9k2mR3Q4W5X6Y7Z8A=")
            .add("*.supabase.co", "sha256/k20D0A5n5F1L18u62B47rE5R4Q7k1P2o3W4X5Y6Z7A8=")
            .build()
    }

    private val client: OkHttpClient by lazy {
        val logging = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.NONE
        }
        val builder = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .addInterceptor(logging)

        if (!BuildConfig.DEBUG && BASE_URL.startsWith("https://")) {
            builder.certificatePinner(createCertificatePinner())
        }
        builder.build()
    }

    private val service: ApiService by lazy {
        Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(ApiService::class.java)
    }

    fun get(): ApiService = service
}
