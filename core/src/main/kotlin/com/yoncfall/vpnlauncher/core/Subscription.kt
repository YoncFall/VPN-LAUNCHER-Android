// Подписки и разбор нод. Порт core.ps1:392-450 (Get-SubscriptionNodes /
// Parse-NodeList) через vpn-launcher-py (subscription.py).
//
// Отклонения от 1.0.6 (сознательные, поведение VPN не затрагивают):
//   - тело HTTP-ответа: UTF-8, иначе системная кодировка (как в desktop-порте;
//     1.0.6 всегда ANSI/cp1251);
//   - HttpURLConnection следует редиректам только внутри одной схемы (urllib
//     следовал http->https); тексты ошибок IO отличаются (журнал/исключения);
//   - SubscriptionError: RuntimeException вместо RuntimeError (в Kotlin нет
//     единого предка-исключения поведения).
package com.yoncfall.vpnlauncher.core

import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.util.Locale
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

class SubscriptionError(message: String) : RuntimeException(message)

/** Как в core.ps1:416 — сервер подписки должен видеть этот User-Agent. */
const val USER_AGENT = "sing-box/1.14.2"

/** Как WebClient.Timeout в .NET (по умолчанию 100000 мс). */
const val HTTP_TIMEOUT_MS = 100_000

// Parse-NodeList:429 — узнаваемые схемы в начале строки; PS -notmatch
// регистронезависим, флаг нужен и здесь
private val SCHEME_RE = Regex("^(vless|vmess|trojan|ss|hysteria2|hy2|tuic)://", RegexOption.IGNORE_CASE)
private val LINE_RE = Regex("\r?\n")

// PS -match регистронезависим -> флаг IGNORECASE
private val LOCAL_URL_RE = Regex("^(https?|file)://", RegexOption.IGNORE_CASE)
private val FILE_URL_RE = Regex("^file://", RegexOption.IGNORE_CASE)

/** Тело ответа -> строка: UTF-8, иначе системная кодировка (байты-замены). */
private fun decodeBody(raw: ByteArray): String {
    return try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(java.nio.ByteBuffer.wrap(raw))
            .toString()
    } catch (e: CharacterCodingException) {
        String(raw, Charset.defaultCharset())
    }
}

/** [IO.File]::ReadAllText($p, UTF8) / Python read_text: UTF-8, битые байты -> замены. */
private fun readLocal(path: File): String = path.readText(Charsets.UTF_8)

/**
 * Get-SubscriptionNodes: http(s)/file-URL или путь к файлу -> список нод.
 * HTTP: User-Agent sing-box/1.14.2, таймаут 100 с (как WebClient).
 */
fun fetchNodes(url: String, logger: (String) -> Unit = AppLog::write): List<JsonObject> {
    if (url.isEmpty()) throw SubscriptionError("Пустая ссылка на подписку")

    // локальный файл с подпиской (например подписка-зеркало .txt)
    if (!LOCAL_URL_RE.containsMatchIn(url)) {
        val p = File(url.trim('"'))
        if (p.isFile) {
            logger("fetching subscription from local file: $p")
            return parseNodeList(readLocal(p), logger)
        }
        throw SubscriptionError("Нужна http(s)-ссылка либо путь к локальному файлу подписки")
    }

    if (FILE_URL_RE.containsMatchIn(url)) {
        val p = File(percentDecode(url.substring(7)))
        logger("fetching subscription from local file: $p")
        return parseNodeList(readLocal(p), logger)
    }

    val host = try {
        URL(url).host?.takeIf { it.isNotEmpty() } ?: "?"
    } catch (e: Exception) {
        "?"
    }
    logger("fetching subscription from host: $host")

    val conn = URL(url).openConnection() as HttpURLConnection
    conn.requestMethod = "GET"
    conn.setRequestProperty("User-Agent", USER_AGENT)
    conn.connectTimeout = HTTP_TIMEOUT_MS
    conn.readTimeout = HTTP_TIMEOUT_MS
    val raw = try {
        conn.inputStream.use { it.readBytes() }
    } finally {
        conn.disconnect()
    }
    if (raw.isEmpty()) throw SubscriptionError("Пустой ответ сервера подписки")
    return parseNodeList(decodeBody(raw), logger)
}

/**
 * Parse-NodeList: текст подписки (или b64) -> список нод с display/proto.
 * Пустые строки и строки без "://" пропускаются; тег node-N по позиции
 * строки среди прошедших фильтр (нумерация с 1).
 */
fun parseNodeList(body: String, logger: (String) -> Unit = AppLog::write): List<JsonObject> {
    if (body.isEmpty()) throw SubscriptionError("Пустой текст подписки")

    // если это base64 — декодируем
    val t = body.filterNot { it.isWhitespace() }
    var text = body
    if (!SCHEME_RE.containsMatchIn(t)) {
        val dec = b64Utf8Decode(t)
        text = if ("://" in dec) dec else t
    }

    val lines = text.split(LINE_RE).map { it.trim() }.filter { "://" in it }
    val nodes = ArrayList<JsonObject>()
    for ((i, line) in lines.withIndex()) {
        val o = parseProxyUri(line, i + 1, logger) ?: continue
        val frag = splitUri(line)["Fragment"]!!
        val display = if (frag.isNotEmpty()) {
            frag
        } else {
            "${pyStr(o["server"])}:${pyStr(o["server_port"])}"
        }
        val withDisplay = LinkedHashMap(o)
        withDisplay["display"] = JsonPrimitive(display)
        withDisplay["proto"] = o["type"]!!
        nodes.add(JsonObject(withDisplay))
    }
    logger("  parsed ${nodes.size} of ${lines.size} lines")
    if (nodes.isEmpty()) throw SubscriptionError("Не удалось распознать ни одного сервера в подписке")
    return nodes
}
