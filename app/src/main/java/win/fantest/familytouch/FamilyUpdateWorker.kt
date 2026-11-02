package win.fantest.familytouch

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/** A persistent, foreground Android job. Never depends on an Activity, chat or PIN state. */
internal class FamilyUpdateWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {
    companion object {
        const val CHANNEL = "family_touch_background_updates"
        const val NOTIFICATION = 6402
        const val COMPLETE_NOTIFICATION = 6403
        const val KEY_VERSION = "version"
        const val KEY_NAME = "name"
        const val KEY_SIZE = "bytes"
        const val KEY_SHA = "sha256"
        const val KEY_SOURCES = "sources"
        const val KEY_PROGRESS = "progress"
        const val KEY_ERROR = "error"
        const val CHUNK = 1024 * 1024L
        private const val MAX_CHUNK_RETRIES = 5
        private val client = OkHttpClient.Builder()
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(38, TimeUnit.SECONDS)
            .callTimeout(55, TimeUnit.SECONDS)
            .followRedirects(true).build()
    }

    private fun channel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = applicationContext.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel(
                CHANNEL, "Family Touch updates", NotificationManager.IMPORTANCE_LOW
            ))
        }
    }

    private fun notification(percent: Int, ready: Boolean = false): Notification {
        channel()
        val openApp = Intent(applicationContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pending = PendingIntent.getActivity(applicationContext, 6402, openApp,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val builder = NotificationCompat.Builder(applicationContext, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(if (ready) "Family Touch update ready" else "Updating Family Touch")
            .setContentText(if (ready) "Open the app and unlock to install" else "Downloading securely: $percent%")
            .setOnlyAlertOnce(true)
            .setOngoing(!ready)
            .setContentIntent(pending)
        if (!ready) builder.setProgress(100, percent.coerceIn(0, 100), false)
        else builder.setAutoCancel(true).setSmallIcon(android.R.drawable.stat_sys_download_done)
        return builder.build()
    }

    private fun foreground(percent: Int) =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            ForegroundInfo(NOTIFICATION, notification(percent), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else ForegroundInfo(NOTIFICATION, notification(percent))

    override suspend fun getForegroundInfo(): ForegroundInfo = foreground(0)

    override suspend fun doWork() = withContext(Dispatchers.IO) {
        val version = inputData.getLong(KEY_VERSION, -1L)
        val size = inputData.getLong(KEY_SIZE, -1L)
        val checksum = inputData.getString(KEY_SHA)?.lowercase() ?: ""
        val sources = inputData.getStringArray(KEY_SOURCES)?.toList() ?: emptyList()
        if (version <= 0 || size !in 1..250_000_000L ||
            !Regex("[0-9a-f]{64}").matches(checksum) ||
            sources.isEmpty() || sources.any { !FamilyUpdates.isAllowedDownloadUrl(it) }) {
            return@withContext Result.failure(workDataOf(KEY_ERROR to "Invalid signed update metadata"))
        }
        val update = AvailableFamilyUpdate(version,
            inputData.getString(KEY_NAME).orEmpty(), size, checksum, sources.first(), sources.drop(1))
        val part = FamilyUpdates.partialFile(applicationContext, update)
        val finished = FamilyUpdates.targetFile(applicationContext, update)
        try {
            setForeground(foreground(0))
            // WorkManager may restart us at any point. Discard only the unfinished range,
            // never a fully written chunk. A new version/hash uses a different filename.
            if (part.exists() && part.length() > size) part.delete()
            var offset = if (part.exists()) part.length().coerceAtMost(size) else 0L
            if (offset < size) offset -= offset % CHUNK
            RandomAccessFile(part, "rw").use { it.setLength(offset) }
            setProgress(workDataOf(KEY_PROGRESS to ((offset * 100L / size).toInt())))
            while (offset < size) {
                if (isStopped) return@withContext Result.retry()
                val end = minOf(size - 1, offset + CHUNK - 1)
                var ok = false
                var lastError = "Connection interrupted"
                for (attempt in 0 until MAX_CHUNK_RETRIES) {
                    if (isStopped) return@withContext Result.retry()
                    val source = sources[attempt % sources.size]
                    try {
                        val request = Request.Builder().url(source)
                            .header("Range", "bytes=$offset-$end")
                            .header("Cache-Control", "no-cache")
                            .build()
                        client.newCall(request).execute().use { response ->
                            if (response.code != 206) throw IOException("HTTP ${response.code}")
                            if (!FamilyUpdates.isAllowedDownloadUrl(response.request.url.toString())) {
                                throw IOException("Unexpected update redirect")
                            }
                            val expected = "bytes $offset-$end/$size"
                            if (response.header("Content-Range")?.trim() != expected) {
                                throw IOException("Mirror did not return requested range")
                            }
                            val stream = response.body?.byteStream() ?: throw IOException("Empty response")
                            RandomAccessFile(part, "rw").use { file ->
                                file.setLength(offset)
                                file.seek(offset)
                                val buf = ByteArray(65536)
                                var written = 0L
                                while (written < end - offset + 1) {
                                    if (isStopped) throw IOException("Worker stopped")
                                    val count = stream.read(buf, 0,
                                        minOf(buf.size.toLong(), end - offset + 1 - written).toInt())
                                    if (count < 0) break
                                    file.write(buf, 0, count)
                                    written += count
                                }
                                if (written != end - offset + 1) throw IOException("Incomplete range")
                                file.fd.sync()
                            }
                        }
                        ok = true
                        break
                    } catch (ex: IOException) {
                        lastError = ex.message ?: "Connection interrupted"
                        // Restart the incomplete chunk; no unchecked data may advance offset.
                        RandomAccessFile(part, "rw").use { it.setLength(offset) }
                        delay((1000L * (attempt + 1)).coerceAtMost(5000L))
                    }
                }
                if (!ok) {
                    setProgress(workDataOf(KEY_PROGRESS to ((offset * 100L / size).toInt()),
                        KEY_ERROR to "Paused: $lastError"))
                    return@withContext Result.retry()
                }
                offset = end + 1
                val pct = (offset * 100 / size).toInt().coerceIn(0, 100)
                setProgress(workDataOf(KEY_PROGRESS to pct))
                setForeground(foreground(pct))
            }
            val hash = MessageDigest.getInstance("SHA-256")
            part.inputStream().buffered().use { stream ->
                val b = ByteArray(65536)
                while (true) {
                    val n = stream.read(b)
                    if (n < 0) break
                    hash.update(b, 0, n)
                }
            }
            val actual = hash.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
            if (actual != checksum || part.length() != size) {
                part.delete()
                return@withContext Result.failure(workDataOf(KEY_ERROR to "APK checksum mismatch"))
            }
            FamilyUpdates.verifyPackageAndSigner(applicationContext, part, version)
            if (finished.exists() && !finished.delete()) {
                return@withContext Result.retry()
            }
            if (!part.renameTo(finished)) return@withContext Result.retry()
            FamilyUpdates.markReady(applicationContext, update)
            applicationContext.getSystemService(NotificationManager::class.java)
                .notify(COMPLETE_NOTIFICATION, notification(100, ready = true))
            Result.success(workDataOf(KEY_VERSION to version, KEY_PROGRESS to 100))
        } catch (e: Exception) {
            // Preserve the completed chunks across network errors, app crashes and device restarts.
            setProgress(workDataOf(KEY_ERROR to (e.message ?: "Update paused")))
            Result.retry()
        }
    }
}
