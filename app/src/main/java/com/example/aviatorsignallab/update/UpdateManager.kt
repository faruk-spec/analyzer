package com.example.aviatorsignallab.update

import android.content.Context
import com.example.aviatorsignallab.BuildConfig
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

sealed class UpdateCheckResult {
    data class UpdateAvailable(val metadata: ReleaseMetadata) : UpdateCheckResult()
    object UpToDate : UpdateCheckResult()
    data class Error(val message: String) : UpdateCheckResult()
}

class UpdateManager(
    private val context: Context,
    private val metadataUrl: String = BuildConfig.UPDATE_METADATA_URL
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()

    suspend fun checkForUpdates(): UpdateCheckResult = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(metadataUrl)
                .addHeader("Accept", "application/json")
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext UpdateCheckResult.Error("HTTP Error: ${response.code}")
                }
                val body = response.body?.string() ?: return@withContext UpdateCheckResult.Error("Empty response")
                val metadata = parseMetadataSafely(body) ?: return@withContext UpdateCheckResult.Error("Invalid metadata JSON")

                // Security validation: URL must be HTTPS
                if (!metadata.apkUrl.startsWith("https://")) {
                    return@withContext UpdateCheckResult.Error("Insecure APK download URL rejected")
                }

                if (metadata.versionCode > BuildConfig.VERSION_CODE) {
                    UpdateCheckResult.UpdateAvailable(metadata)
                } else {
                    UpdateCheckResult.UpToDate
                }
            }
        } catch (e: Exception) {
            UpdateCheckResult.Error(e.message ?: "Failed to check for updates")
        }
    }

    private fun parseMetadataSafely(json: String): ReleaseMetadata? {
        return try {
            // First check if it's our standard format: { versionCode: X, ... }
            gson.fromJson(json, ReleaseMetadata::class.java)
        } catch (e: Exception) {
            null
        }
    }

    suspend fun downloadAndInstallApk(
        metadata: ReleaseMetadata,
        onProgress: (Int) -> Unit
    ): Result<File> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(metadata.apkUrl)
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext Result.failure(Exception("Download failed with HTTP ${response.code}"))
                }
                val body = response.body ?: return@withContext Result.failure(Exception("Response body is null"))
                val totalLength = body.contentLength()

                val updateDir = File(context.externalCacheDir ?: context.cacheDir, "updates").apply { mkdirs() }
                val apkFile = File(updateDir, "AviatorSignalLab_v${metadata.versionName}.apk")

                body.byteStream().use { input ->
                    FileOutputStream(apkFile).use { output ->
                        val buffer = ByteArray(8192)
                        var bytesRead: Int
                        var totalRead = 0L

                        while (input.read(buffer).also { bytesRead = it } != -1) {
                            output.write(buffer, 0, bytesRead)
                            totalRead += bytesRead
                            if (totalLength > 0) {
                                val progress = ((totalRead * 100) / totalLength).toInt()
                                onProgress(progress)
                            }
                        }
                    }
                }

                if (!apkFile.exists() || apkFile.length() <= 0) {
                    return@withContext Result.failure(Exception("Downloaded file is empty or missing"))
                }

                Result.success(apkFile)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
