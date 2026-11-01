package com.example.localai.data

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.example.localai.LocalAiApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

/** Downloads one GGUF file in the background, with resume support. */
class ModelDownloadWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val url = inputData.getString("url") ?: return@withContext Result.failure()
        val fileName = inputData.getString("fileName") ?: return@withContext Result.failure()
        val container = (applicationContext as LocalAiApp).container

        runCatching { setForeground(foregroundInfo(fileName)) }   // best effort

        val target = container.store.fileFor(fileName)
        val part = File(container.store.dir, "$fileName.part")
        val existing = if (part.exists()) part.length() else 0L

        val request = Request.Builder().url(url)
            .apply { if (existing > 0) header("Range", "bytes=$existing-") }
            .build()

        try {
            container.http.newCall(request).execute().use { resp ->
                if (resp.code == 416) {                       // our partial file is stale
                    part.delete()
                    return@withContext Result.retry()
                }
                if (resp.code in 400..499) {
                    return@withContext Result.failure(workDataOf("error" to "Server answered HTTP ${resp.code}"))
                }
                if (!resp.isSuccessful) return@withContext retryOrFail("HTTP ${resp.code}")
                if (resp.header("Content-Type")?.startsWith("text/html") == true) {
                    return@withContext Result.failure(workDataOf("error" to "That link is a web page, not a model file"))
                }

                val body = resp.body ?: return@withContext retryOrFail("empty response")
                val resumed = resp.code == 206
                val startAt = if (resumed) existing else 0L
                val len = body.contentLength()
                val total = if (len > 0) len + startAt else -1L

                RandomAccessFile(part, "rw").use { out ->
                    if (resumed) out.seek(startAt) else out.setLength(0)
                    val buf = ByteArray(256 * 1024)
                    var done = startAt
                    var lastReport = 0L
                    body.byteStream().use { input ->
                        while (true) {
                            if (isStopped) return@withContext Result.failure()   // cancelled; .part is kept
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            done += n
                            val now = System.currentTimeMillis()
                            if (now - lastReport > 400) {
                                lastReport = now
                                setProgress(workDataOf("done" to done, "total" to total))
                            }
                        }
                    }
                }
            }

            // sanity check: must be a GGUF file
            val magicOk = part.inputStream().use { i ->
                val m = ByteArray(4)
                i.read(m) == 4 && String(m, Charsets.ISO_8859_1) == "GGUF"
            }
            if (!magicOk) {
                part.delete()
                return@withContext Result.failure(workDataOf("error" to "Downloaded file is not a valid GGUF model"))
            }
            if (target.exists()) target.delete()
            if (!part.renameTo(target)) {
                return@withContext Result.failure(workDataOf("error" to "Could not save the model"))
            }
            Result.success()
        } catch (e: IOException) {
            retryOrFail(e.message ?: "network error")
        }
    }

    private fun retryOrFail(msg: String): Result =
        if (runAttemptCount >= 5) Result.failure(workDataOf("error" to msg)) else Result.retry()

    private fun foregroundInfo(title: String): ForegroundInfo {
        val nm = applicationContext.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel("downloads", "Model downloads", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val notification = NotificationCompat.Builder(applicationContext, "downloads")
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Downloading model")
            .setContentText(title)
            .setOngoing(true)
            .build()
        val id = 1000 + (title.hashCode() and 0xFFFF)
        return if (Build.VERSION.SDK_INT >= 29)
            ForegroundInfo(id, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else ForegroundInfo(id, notification)
    }
}
