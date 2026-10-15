package com.yoncfall.vpnlauncher

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.SystemClock
import com.yoncfall.vpnlauncher.core.USER_AGENT
import com.yoncfall.vpnlauncher.core.parseNodeList
import kotlinx.serialization.json.JsonObject
import java.io.File

/**
 * Загрузка подписки через системный DownloadManager.
 *
 * Обход «удержания» HiOS: диагностика 15.10.2026 (модуль :probe) показала, что
 * держатель задерживает на ~37-47 с только ПЕРВЫЕ данные новых TLS-соединений
 * ИМЕННО приложения с uid 10275 (наше), тогда как трафик
 * com.android.providers.downloads (uid 10084) и любых прочих пакетов к тому же
 * серверу идёт мгновенно (200-500 мс). DownloadManager качает файл от своего
 * uid, приложение читает готовый текст с диска.
 *
 * Любая ошибка/пауза дольше [TIMEOUT_MS] -> null: вызывающий код берёт
 * обычный core.fetchNodes (поведение без фикса сохраняется).
 */
object DmSubscription {

    /** Бюджет времени на весь цикл DM (саб маленький, ~3 КБ; обычный путь ~40 с). */
    private const val TIMEOUT_MS = 15_000
    private const val POLL_MS = 250L

    private val HTTP_URL_RE = Regex("^https?://", RegexOption.IGNORE_CASE)

    /**
     * @return список нод либо null (тогда нужен обычный fetchNodes).
     * Уже распарсенный пустой список не возвращается (null -> фоллбэк),
     * чтобы сбой декодирования на стороне DM не выглядел как «0 серверов».
     */
    fun fetchNodes(
        context: Context,
        url: String,
        logger: (String) -> Unit,
    ): List<JsonObject>? {
        if (!HTTP_URL_RE.containsMatchIn(url.trim())) return null
        val host = try {
            java.net.URL(url.trim()).host?.takeIf { it.isNotEmpty() } ?: "?"
        } catch (e: Exception) {
            "?"
        }
        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager
            ?: return null
        var id = -1L
        return try {
            // Уникальное имя на каждый запрос: DM сам не конфликтует с чужими
            // файлами, но и не полагается на это (внешняя папка общая).
            val name = "sub-${System.currentTimeMillis()}.tmp"
            val req = DownloadManager.Request(Uri.parse(url.trim())).apply {
                setTitle("Подписка")
                setDestinationInExternalFilesDir(context, null, name)
                // паритет с прямым fetchNodes + запрет gzip (файл должен быть
                // декодируемым текстом без распаковки)
                addRequestHeader("User-Agent", USER_AGENT)
                addRequestHeader("Accept-Encoding", "identity")
            }
            logger("fetching subscription via download manager: $host")
            id = dm.enqueue(req)

            val t0 = SystemClock.elapsedRealtime()
            var localUri: String? = null
            var failed = false
            var reason = 0
            var lastSt = -1
            while (SystemClock.elapsedRealtime() - t0 < TIMEOUT_MS && !failed && localUri == null) {
                dm.query(DownloadManager.Query().setFilterById(id)).use { c ->
                    if (!c.moveToFirst()) {
                        failed = true
                        logger("dm: record vanished at ${SystemClock.elapsedRealtime() - t0}ms")
                        return@use
                    }
                    val st = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                    val bytes = c.getLong(
                        c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR),
                    )
                    val rc = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
                    // журнал: только смена статуса (1=PEND, 2=RUN, 4=PAUSED, 8=OK, 16=FAIL)
                    if (st != lastSt) {
                        lastSt = st
                        logger(
                            "dm status=$st bytes=$bytes reason=$rc " +
                                "t=${SystemClock.elapsedRealtime() - t0}ms",
                        )
                    }
                    when (st) {
                        DownloadManager.STATUS_SUCCESSFUL -> {
                            localUri = c.getString(
                                c.getColumnIndexOrThrow(DownloadManager.COLUMN_LOCAL_URI),
                            )
                        }
                        DownloadManager.STATUS_FAILED -> {
                            failed = true
                            reason = c.getInt(
                                c.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON),
                            )
                        }
                        else -> Unit
                    }
                }
                if (!failed && localUri == null) Thread.sleep(POLL_MS)
            }

            if (failed || localUri == null) {
                logger(
                    if (failed) {
                        "download manager failed (reason=$reason) - fallback"
                    } else {
                        "download manager timeout - fallback"
                    },
                )
                dm.remove(id)
                id = -1
                null
            } else {
                val raw = context.contentResolver.openInputStream(Uri.parse(localUri!!))
                    ?.use { it.readBytes() }
                dm.remove(id)
                id = -1
                if (raw == null || raw.isEmpty()) {
                    logger("download manager: empty file - fallback")
                    return null
                }
                // подписка - UTF-8/б64-текст; String(UTF-8) даёт те же замены,
                // что core.decodeBody (безжёсткий вариант)
                val nodes = parseNodeList(String(raw, Charsets.UTF_8), logger)
                if (nodes.isEmpty()) {
                    logger("download manager: no nodes parsed - fallback")
                    null
                } else {
                    nodes
                }
            }
        } catch (e: Throwable) {
            if (id != -1L) {
                try {
                    dm.remove(id)
                } catch (_: Throwable) {
                }
            }
            logger("download manager error (${e.javaClass.simpleName}) - fallback")
            null
        }
    }
}
