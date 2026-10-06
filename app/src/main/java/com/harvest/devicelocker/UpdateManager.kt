package com.harvest.devicelocker

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Base64

/**
 * Downloads and silently installs a new build of this app when the server
 * publishes one. Silent (no "install this update?" dialog) only works
 * because this app is the Device Owner - PackageInstaller skips the user
 * confirmation prompt for the device owner's own installer sessions.
 */
object UpdateManager {

    fun currentVersionCode(context: Context): Long {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            info.versionCode.toLong()
        }
    }

    /** Returns a short human-readable result string for logging/toasting. */
    suspend fun checkAndInstall(context: Context, baseUrl: String): String =
        withContext(Dispatchers.IO) {
            try {
                val latest = fetchLatestInfo(baseUrl) ?: return@withContext "no release info"
                val current = currentVersionCode(context)
                if (latest.versionCode <= current) return@withContext "up to date (v$current)"

                val apkFile = File(context.cacheDir, "update.apk")
                downloadTo(latest.url, apkFile)

                val digest = sha256Base64(apkFile)
                if (digest != latest.sha256) {
                    apkFile.delete()
                    return@withContext "checksum mismatch, aborted"
                }

                installSilently(context, apkFile)
                "installing v${latest.versionCode}"
            } catch (e: Exception) {
                "update check failed: ${e.message}"
            }
        }

    private data class ReleaseInfo(val versionCode: Long, val sha256: String, val url: String)

    private fun fetchLatestInfo(baseUrl: String): ReleaseInfo? {
        val conn = URL("$baseUrl/app/latest").openConnection() as HttpURLConnection
        conn.connectTimeout = 5000
        conn.readTimeout = 5000
        if (conn.responseCode != 200) return null
        val body = BufferedReader(InputStreamReader(conn.inputStream)).readText()
        val json = JSONObject(body)
        return ReleaseInfo(
            versionCode = json.getLong("versionCode"),
            sha256 = json.getString("sha256"),
            url = json.getString("url"),
        )
    }

    private fun downloadTo(url: String, dest: File) {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 10000
        conn.readTimeout = 30000
        conn.inputStream.use { input ->
            dest.outputStream().use { output -> input.copyTo(output) }
        }
    }

    private fun sha256Base64(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return Base64.getEncoder().encodeToString(digest.digest())
    }

    private fun installSilently(context: Context, apkFile: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        val sessionId = installer.createSession(params)
        val session = installer.openSession(sessionId)
        session.use {
            it.openWrite("package", 0, apkFile.length()).use { out ->
                apkFile.inputStream().use { input -> input.copyTo(out) }
                it.fsync(out)
            }
            val intent = Intent(context, UpdateReceiver::class.java)
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                sessionId,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
            )
            it.commit(pendingIntent.intentSender)
        }
    }
}
