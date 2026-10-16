// Скачивание APK обновления через системный DownloadManager (2.1.0).
//
// Тот же обход «удержания» HiOS, что и в DmSubscription: DM качает файл
// от своего uid (com.android.providers.downloads), приложение забирает
// готовый файл с диска и отдаёт установщику через FileProvider.
package com.yoncfall.vpnlauncher

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.SystemClock
import com.yoncfall.vpnlauncher.core.USER_AGENT
import java.io.File

object DmUpdate {

    /** Бюджет времени: APK до ~65 МБ, лимит щедрый (старт сети бывает медленным). */
    private const val TIMEOUT_MS = 25 * 60_000L
    private const val POLL_MS = 500L

    /**
     * Скачать APK; onProgress получает процент (0..100) или -1 (размер
     * неизвестен). Файл той же версии удаляется заранее: DM отказывается
     * писать в занятый путь.
     *
     * @return готовый файл либо null (ошибка/таймаут - статус «не качается»,
     * плашка остаётся, пользователь может повторить).
     */
    fun download(
        context: Context,
        url: String,
        version: String,
        onProgress: (Int) -> Unit,
        logger: (String) -> Unit,
    ): File? {
        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager ?: return null
        val dir = context.getExternalFilesDir(null) ?: context.filesDir
        val dest = File(dir, "update-$version.apk")
        if (dest.exists()) dest.delete()
        var id = -1L
        return try {
            val req = DownloadManager.Request(Uri.parse(url)).apply {
                setTitle("Обновление VPN Лаунчер $version")
                setDestinationInExternalFilesDir(context, null, dest.name)
                setMimeType("application/vnd.android.package-archive")
                addRequestHeader("User-Agent", USER_AGENT)
            }
            id = dm.enqueue(req)
            logger("update download enqueued: $url")

            val t0 = SystemClock.elapsedRealtime()
            var localFile: File? = null
            var failed = false
            var reason = 0
            var lastPct = -2
            while (localFile == null && !failed && SystemClock.elapsedRealtime() - t0 < TIMEOUT_MS) {
                dm.query(DownloadManager.Query().setFilterById(id)).use { c ->
                    if (!c.moveToFirst()) {
                        failed = true
                        logger("update dm: record vanished")
                        return@use
                    }
                    val st = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                    when (st) {
                        DownloadManager.STATUS_SUCCESSFUL -> {
                            val uri = c.getString(
                                c.getColumnIndexOrThrow(DownloadManager.COLUMN_LOCAL_URI),
                            )
                            localFile = uri?.let { File(Uri.parse(it).path ?: "") }
                            if (localFile == null) failed = true
                        }
                        DownloadManager.STATUS_FAILED -> {
                            failed = true
                            reason = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
                        }
                        else -> {
                            val got = c.getLong(
                                c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR),
                            )
                            val total = c.getLong(
                                c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES),
                            )
                            // размер ещё неизвестен (STATUS_PENDING/редирект):
                            // молчим. Раньше шёл onProgress(-1), плашка
                            // сбрасывалась, кнопка снова активна - двойной
                            // тап запускал второй DM, который удалял
                            // скачиваемый файл (баг v2.1.0, правка 2.2.1)
                            if (total > 0) {
                                val pct = (got * 100 / total).toInt()
                                if (pct != lastPct) {
                                    lastPct = pct
                                    onProgress(pct)
                                }
                            }
                        }
                    }
                }
                if (localFile == null && !failed) Thread.sleep(POLL_MS)
            }

            val file = localFile
            if (file != null && file.isFile && file.length() > 1_000_000) {
                logger("update download done in ${SystemClock.elapsedRealtime() - t0}ms")
                file
            } else {
                logger(if (failed) "update dm failed (reason=$reason)" else "update dm timeout")
                dm.remove(id)
                id = -1
                null
            }
        } catch (e: Throwable) {
            if (id != -1L) {
                try {
                    dm.remove(id)
                } catch (_: Throwable) {
                }
            }
            logger("update dm error: ${e.message}")
            null
        }
    }
}
