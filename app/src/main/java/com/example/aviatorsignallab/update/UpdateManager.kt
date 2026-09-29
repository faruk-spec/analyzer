package com.example.aviatorsignallab.update

import android.content.Context
import com.example.aviatorsignallab.BuildConfig
import com.google.gson.Gson
import com.google.gson.JsonObject
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
    companion object {
        private const val PREFS_NAME = "aviator_update_prefs"
        private const val KEY_CUSTOM_UPDATE_URL = "custom_update_url"
        private const val KEY_GITHUB_TOKEN = "github_token"
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    private val gson = Gson()

    fun getEffectiveUpdateUrl(): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val custom = prefs.getString(KEY_CUSTOM_UPDATE_URL, null)
        return if (!custom.isNullOrBlank()) custom.trim() else metadataUrl
    }

    fun setCustomUpdateUrl(url: String?) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_CUSTOM_UPDATE_URL, url?.trim()).apply()
    }

    fun getGitHubToken(): String? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_GITHUB_TOKEN, null)
    }

    fun setGitHubToken(token: String?) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_GITHUB_TOKEN, token?.trim()).apply()
    }

    suspend fun checkForUpdates(): UpdateCheckResult = withContext(Dispatchers.IO) {
        try {
            val url = getEffectiveUpdateUrl()
            val token = getGitHubToken()

            val reqBuilder = Request.Builder()
                .url(url)
                .addHeader("Accept", "application/json")
                .addHeader("User-Agent", "AviatorSignalLab-Android/${BuildConfig.VERSION_NAME}")

            if (!token.isNullOrBlank() && url.contains("github.com", ignoreCase = true)) {
                reqBuilder.addHeader("Authorization", "Bearer $token")
            }

            client.newCall(reqBuilder.build()).execute().use { response ->
                if (!response.isSuccessful) {
                    val code = response.code
                    return@withContext if (code == 404) {
                        UpdateCheckResult.Error(
                            "No published releases found on GitHub (HTTP 404).\n" +
                            "Repository: faruk-spec/analyzer\n\n" +
                            "To enable updates:\n" +
                            "1. Push a release tag on GitHub (e.g., git tag v1.0.3 && git push origin v1.0.3).\n" +
                            "2. If the repository is private, provide a GitHub Personal Access Token in Update Settings."
                        )
                    } else if (code == 401 || code == 403) {
                        UpdateCheckResult.Error(
                            "GitHub authentication required (HTTP $code).\n" +
                            "If the repository is private or rate-limited, provide a GitHub Personal Access Token in Update Settings."
                        )
                    } else {
                        UpdateCheckResult.Error("HTTP Error: $code (${response.message})")
                    }
                }

                val body = response.body?.string() ?: return@withContext UpdateCheckResult.Error("Empty response from update server")
                val metadata = parseMetadataSafely(body)
                    ?: return@withContext UpdateCheckResult.Error("Could not find a valid release or APK asset in server response")

                // Security validation: URL must be HTTPS
                if (!metadata.apkUrl.startsWith("https://")) {
                    return@withContext UpdateCheckResult.Error("Insecure APK download URL rejected")
                }

                if (isNewerVersion(metadata.versionCode, metadata.versionName)) {
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
            val jsonObject = gson.fromJson(json, JsonObject::class.java) ?: return null

            // Format 1: Direct ReleaseMetadata schema { "versionCode": ..., "versionName": ..., "apkUrl": ... }
            if (jsonObject.has("apkUrl") && jsonObject.has("versionCode")) {
                return gson.fromJson(jsonObject, ReleaseMetadata::class.java)
            }

            // Format 2: GitHub Releases API response { "tag_name": "v1.0.3", "assets": [...] }
            if (jsonObject.has("tag_name") && jsonObject.has("assets")) {
                val tagName = jsonObject.get("tag_name")?.asString ?: return null
                val cleanVersionName = tagName.removePrefix("v").removePrefix("V")
                val bodyText = if (jsonObject.has("body") && !jsonObject.get("body").isJsonNull) {
                    jsonObject.get("body").asString
                } else null
                val publishedAt = if (jsonObject.has("published_at") && !jsonObject.get("published_at").isJsonNull) {
                    jsonObject.get("published_at").asString
                } else null

                val assets = jsonObject.getAsJsonArray("assets") ?: return null
                var apkUrl: String? = null
                for (elem in assets) {
                    val assetObj = elem.asJsonObject
                    val name = assetObj.get("name")?.asString ?: ""
                    if (name.endsWith(".apk", ignoreCase = true)) {
                        apkUrl = assetObj.get("browser_download_url")?.asString
                        break
                    }
                }

                if (apkUrl.isNullOrBlank()) {
                    return null
                }

                val derivedVersionCode = deriveVersionCode(cleanVersionName, bodyText)

                return ReleaseMetadata(
                    versionCode = derivedVersionCode,
                    versionName = cleanVersionName,
                    releaseNotes = bodyText ?: "GitHub Release $tagName",
                    apkUrl = apkUrl,
                    publishedAt = publishedAt
                )
            }

            null
        } catch (e: Exception) {
            null
        }
    }

    private fun deriveVersionCode(versionName: String, body: String?): Int {
        if (!body.isNullOrBlank()) {
            val regex = Regex("""versionCode["':\s]+(\d+)""", RegexOption.IGNORE_CASE)
            val match = regex.find(body)
            if (match != null) {
                match.groupValues[1].toIntOrNull()?.let { return it }
            }
        }
        return try {
            val parts = versionName.split(".").map { it.filter { ch -> ch.isDigit() }.toIntOrNull() ?: 0 }
            val major = parts.getOrElse(0) { 0 }
            val minor = parts.getOrElse(1) { 0 }
            val patch = parts.getOrElse(2) { 0 }
            major * 10000 + minor * 100 + patch
        } catch (e: Exception) {
            0
        }
    }

    private fun isNewerVersion(remoteCode: Int, remoteName: String): Boolean {
        if (remoteCode > 0 && BuildConfig.VERSION_CODE > 0) {
            val currentDerived = deriveVersionCode(BuildConfig.VERSION_NAME, null)
            if (remoteCode >= 10000 || currentDerived >= 10000) {
                return remoteCode > currentDerived
            }
            return remoteCode > BuildConfig.VERSION_CODE
        }
        return compareSemver(remoteName, BuildConfig.VERSION_NAME) > 0
    }

    private fun compareSemver(v1: String, v2: String): Int {
        val p1 = v1.split(".").map { it.filter { ch -> ch.isDigit() }.toIntOrNull() ?: 0 }
        val p2 = v2.split(".").map { it.filter { ch -> ch.isDigit() }.toIntOrNull() ?: 0 }
        val length = maxOf(p1.size, p2.size)
        for (i in 0 until length) {
            val num1 = p1.getOrElse(i) { 0 }
            val num2 = p2.getOrElse(i) { 0 }
            if (num1 != num2) return num1.compareTo(num2)
        }
        return 0
    }

    suspend fun downloadAndInstallApk(
        metadata: ReleaseMetadata,
        onProgress: (Int) -> Unit
    ): Result<File> = withContext(Dispatchers.IO) {
        try {
            val token = getGitHubToken()
            val reqBuilder = Request.Builder()
                .url(metadata.apkUrl)
                .addHeader("User-Agent", "AviatorSignalLab-Android/${BuildConfig.VERSION_NAME}")

            if (!token.isNullOrBlank() && metadata.apkUrl.contains("github.com", ignoreCase = true)) {
                reqBuilder.addHeader("Authorization", "Bearer $token")
            }

            client.newCall(reqBuilder.build()).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext Result.failure(Exception("Download failed with HTTP ${response.code}: ${response.message}"))
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
