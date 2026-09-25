package com.gokuencinar.iruniversal.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class AppRelease(
    val tag: String,
    val title: String,
    val version: String,
    val build: Int,
    val apkUrl: String,
    val notes: String
)

data class UpdateCheckResult(
    val release: AppRelease?,
    val updateAvailable: Boolean,
    val message: String
)

class AppUpdater(private val context: Context) {
    private val latestReleaseUrl =
        "https://api.github.com/repos/Gokuencinar/TVBGoneAndroid/releases/latest"

    val currentVersion: String
        get() = packageInfo().versionName ?: "0"

    val currentBuild: Int
        get() {
            val info = packageInfo()
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                info.longVersionCode.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            } else {
                @Suppress("DEPRECATION")
                info.versionCode
            }
        }

    fun checkForUpdates(): UpdateCheckResult {
        val connection = (URL(latestReleaseUrl).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 15_000
            requestMethod = "GET"
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "TVBGoneAndroid-Updater")
            setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
        }

        return try {
            val status = connection.responseCode
            if (status !in 200..299) {
                error("GitHub respondió HTTP $status")
            }

            val payload = connection.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(payload)
            val tag = json.optString("tag_name").trim()
            val title = json.optString("name").trim().ifBlank { tag }
            val notes = json.optString("body").trim()
            val assets = json.optJSONArray("assets") ?: error("La release no contiene assets.")

            var apkUrl: String? = null
            for (index in 0 until assets.length()) {
                val asset = assets.optJSONObject(index) ?: continue
                if (asset.optString("name").lowercase().endsWith(".apk")) {
                    apkUrl = asset.optString("browser_download_url").trim()
                    if (apkUrl.isNotBlank()) break
                }
            }
            val download = apkUrl ?: error("La release no contiene una APK.")

            val parsedBuild = parseBuild(tag)
            val parsedVersion = parseVersion(tag)
            val release = AppRelease(
                tag = tag,
                title = title,
                version = parsedVersion ?: currentVersion,
                build = parsedBuild ?: 0,
                apkUrl = download,
                notes = notes
            )

            val newer = isNewer(release)
            UpdateCheckResult(
                release = release,
                updateAvailable = newer,
                message = if (newer) {
                    "Nueva versión disponible: ${releaseLabel(release)}."
                } else {
                    "TVBGoneAndroid está actualizado."
                }
            )
        } catch (e: Exception) {
            UpdateCheckResult(
                release = null,
                updateAvailable = false,
                message = "No se pudo comprobar la actualización: " +
                    (e.message ?: e.javaClass.simpleName)
            )
        } finally {
            connection.disconnect()
        }
    }

    fun openDownload(release: AppRelease) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(release.apkUrl)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    fun releaseLabel(release: AppRelease): String =
        if (release.build > 0) "${release.version} (${release.build})" else release.version

    private fun isNewer(candidate: AppRelease): Boolean {
        if (candidate.build > 0) {
            return candidate.build > currentBuild
        }
        return compareVersions(candidate.version, currentVersion) > 0
    }

    private fun parseBuild(tag: String): Int? {
        Regex("""(?i)^build-(\d+)$""").find(tag)?.let {
            return it.groupValues[1].toIntOrNull()
        }
        Regex("""(?i)(?:-|_)b(\d+)$""").find(tag)?.let {
            return it.groupValues[1].toIntOrNull()
        }
        return null
    }

    private fun parseVersion(tag: String): String? =
        Regex("""(?i)v?(\d+(?:\.\d+){1,3}(?:-[a-z0-9.]+)?)""")
            .find(tag)
            ?.groupValues
            ?.getOrNull(1)

    private fun compareVersions(left: String, right: String): Int {
        val a = left.substringBefore("-").split(".").map { it.toIntOrNull() ?: 0 }
        val b = right.substringBefore("-").split(".").map { it.toIntOrNull() ?: 0 }
        val size = maxOf(a.size, b.size)
        for (index in 0 until size) {
            val av = a.getOrElse(index) { 0 }
            val bv = b.getOrElse(index) { 0 }
            if (av != bv) return av.compareTo(bv)
        }
        return 0
    }

    @Suppress("DEPRECATION")
    private fun packageInfo() =
        context.packageManager.getPackageInfo(context.packageName, 0)
}
