package dev.ravindran.ythub.mobile

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.content.pm.PackageManager
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

internal data class YTHubRelease(
    val versionName: String,
    val versionCode: Int,
    val notes: String,
    val apkUrl: String
)

internal sealed interface AppUpdateStatus {
    data object NoStableRelease : AppUpdateStatus
    data object UpToDate : AppUpdateStatus
    data class Available(val release: YTHubRelease) : AppUpdateStatus
}

/** Checks public GitHub Releases and installs updates through Android's package installer. */
internal object AppUpdater {
    private const val releaseApi = "https://api.github.com/repos/ravindran-dev/YTHub/releases/latest"

    suspend fun checkForUpdate(): AppUpdateStatus = withContext(Dispatchers.IO) {
        val connection = (URL(releaseApi).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 8_000
            readTimeout = 12_000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "Hertix-Android-Updater")
        }
        try {
            val status = connection.responseCode
            if (status == HttpURLConnection.HTTP_NOT_FOUND) return@withContext AppUpdateStatus.NoStableRelease
            check(status == HttpURLConnection.HTTP_OK) { "GitHub update check failed ($status). Try again later." }
            val release = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
            if (release.optBoolean("draft", false) || release.optBoolean("prerelease", false)) return@withContext AppUpdateStatus.NoStableRelease
            val tag = release.optString("tag_name").removePrefix("v").removePrefix("V")
            val version = Regex("\\d+").findAll(tag).mapNotNull { it.value.toIntOrNull() }.toList()
            check(version.size >= 3) { "Release tag must use a version such as v1.0.4." }
            val latestCode = (version[0] * 1_000_000L + version[1] * 1_000L + version[2]).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            val apk = release.optJSONArray("assets")?.let { assets ->
                (0 until assets.length()).asSequence().map { assets.getJSONObject(it) }
                    .firstOrNull { it.optString("name").endsWith(".apk", ignoreCase = true) }
            }
            check(apk != null) { "This Hertix release does not include an APK." }
            if (latestCode <= BuildConfig.VERSION_CODE) return@withContext AppUpdateStatus.UpToDate
            AppUpdateStatus.Available(YTHubRelease(tag, latestCode, release.optString("body").take(12_000), apk.getString("browser_download_url")))
        } finally {
            connection.disconnect()
        }
    }

    suspend fun download(context: Context, release: YTHubRelease, onProgress: (Float) -> Unit): File = withContext(Dispatchers.IO) {
        val directory = File(context.filesDir, "updates").apply { mkdirs() }
        val destination = File(directory, "Hertix-${release.versionName}.apk")
        val temporary = File(directory, "${destination.name}.part")
        val connection = (URL(release.apkUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "Hertix-Android-Updater")
        }
        try {
            check(connection.responseCode == HttpURLConnection.HTTP_OK) { "APK download failed (${connection.responseCode})." }
            val total = connection.contentLengthLong
            check(total == -1L || total in 1..(1L shl 30)) { "The release APK size is invalid or exceeds 1 GB." }
            connection.inputStream.use { input ->
                temporary.outputStream().buffered().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var read: Int
                    var count = 0L
                    var notifiedProgress = 0f
                    while (input.read(buffer).also { read = it } >= 0) {
                        if (read == 0) continue
                        output.write(buffer, 0, read)
                        count += read
                        check(count <= (1L shl 30)) { "The release APK exceeds 1 GB." }
                        if (total > 0) {
                            val progress = (count.toFloat() / total).coerceIn(0f, 1f)
                            if (progress >= 1f || progress - notifiedProgress >= .01f) {
                                notifiedProgress = progress
                                withContext(Dispatchers.Main.immediate) { onProgress(progress) }
                            }
                        }
                    }
                }
            }
            check(temporary.length() > 0L && (total <= 0L || temporary.length() == total)) { "APK download was incomplete. Try again." }
            check(temporary.renameTo(destination)) { "Could not save the update APK." }
            destination
        } catch (error: Throwable) {
            temporary.delete()
            throw error
        } finally {
            connection.disconnect()
        }
    }

    fun launchInstaller(context: Context, apk: File): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !context.packageManager.canRequestPackageInstalls()) {
            context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")))
            return false
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", apk)
        context.startActivity(Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        })
        return true
    }

    @Suppress("DEPRECATION")
    fun verifyUpdateSigner(context: Context, apk: File) {
        val flag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val archive = context.packageManager.getPackageArchiveInfo(apk.absolutePath, flag)
            ?: error("This file is not a valid Android APK.")
        check(archive.packageName == context.packageName) { "This APK is for a different app." }
        val installedSigners = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES).signingInfo?.apkContentsSigners
        } else {
            context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES).signatures
        }
        val updateSigners = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) archive.signingInfo?.apkContentsSigners else archive.signatures
        check(!installedSigners.isNullOrEmpty() && !updateSigners.isNullOrEmpty() &&
            installedSigners.map { it.toCharsString() }.toSet() == updateSigners.map { it.toCharsString() }.toSet()) {
            "This APK was signed with a different key and cannot update this installation. Reinstalling would be required."
        }
    }
}
