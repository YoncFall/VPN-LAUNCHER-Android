// URI-хелперы. Дословный порт src/core.ps1 через vpn-launcher-py
// (vpn_launcher/core/uris.py, функции split_uri/parse_query/split_host_port/
// get_query_val/get_bool_val). Поведение 1.0.6 сохранено дословно.
package com.yoncfall.vpnlauncher.core

import java.io.ByteArrayOutputStream

private val BRACKET_HOSTPORT = Regex("^\\[(?<h>.+)\\]:(?<p>\\d+)$")
private val HOSTPORT = Regex("^(?<h>.+):(?<p>\\d+)$")
private val TRUTHY = Regex("^(1|true|yes|on)$", RegexOption.IGNORE_CASE)

/**
 * Split-Uri -> Map c ключами Scheme/Body/Query/Fragment (все ключи есть всегда).
 * Fragment процент-декодируется (как unquote в Python / PS), Query — как есть.
 */
fun splitUri(uri: String): Map<String, String> {
    val res = LinkedHashMap<String, String>()
    res["Scheme"] = ""
    res["Body"] = ""
    res["Query"] = ""
    res["Fragment"] = ""

    var rest = uri.trim()
    val i = rest.indexOf("://")
    if (i < 0) return res
    res["Scheme"] = rest.substring(0, i).lowercase()
    rest = rest.substring(i + 3)

    val h = rest.indexOf('#')
    if (h >= 0) {
        res["Fragment"] = percentDecode(rest.substring(h + 1))
        rest = rest.substring(0, h)
    }

    val q = rest.indexOf('?')
    if (q >= 0) {
        res["Query"] = rest.substring(q + 1)
        rest = rest.substring(0, q)
    }

    res["Body"] = rest
    return res
}

/**
 * urllib.parse.unquote: %XX-последовательности -> байты -> UTF-8
 * (невалидные байты -> U+FFFD, как errors="replace"); '+' НЕ превращается в пробел.
 */
fun percentDecode(s: String): String {
    if ('%' !in s) return s
    val out = ByteArrayOutputStream()
    var i = 0
    val n = s.length
    while (i < n) {
        val cp = s.codePointAt(i)
        val charLen = Character.charCount(cp)
        if (cp == '%'.code && i + 2 < n) {
            val hi = hexDigit(s[i + 1])
            val lo = hexDigit(s[i + 2])
            if (hi >= 0 && lo >= 0) {
                out.write(hi * 16 + lo)
                i += 3
                continue
            }
        }
        out.write(s.substring(i, i + charLen).toByteArray(Charsets.UTF_8))
        i += charLen
    }
    return String(out.toByteArray(), Charsets.UTF_8)
}

private fun hexDigit(c: Char): Int = when (c) {
    in '0'..'9' -> c - '0'
    in 'a'..'f' -> c - 'a' + 10
    in 'A'..'F' -> c - 'A' + 10
    else -> -1
}

/**
 * ConvertFrom-QueryString -> Map; повторяющиеся ключи: последний выигрывает;
 * пары без '=' -> пустое значение; пустой ключ не записывается.
 */
fun parseQuery(q: String): Map<String, String> {
    val out = LinkedHashMap<String, String>()
    if (q.isEmpty()) return out
    for (pair in q.split("&")) {
        if (pair.isEmpty()) continue
        val p = pair.split("=", limit = 2)
        val k = percentDecode(p[0])
        val v = if (p.size > 1) percentDecode(p[1]) else ""
        if (k.isNotEmpty()) out[k] = v
    }
    return out
}

/**
 * Split-HostPort: "host:8443" | "[::1]:443" | "host" -> (host, port=443).
 * Вход по конструкции всегда уже trim-нут (split_uri/parse_node_list).
 */
fun splitHostPort(s: String): Pair<String, Int> {
    BRACKET_HOSTPORT.matchEntire(s)?.let {
        return it.groups["h"]!!.value to it.groups["p"]!!.value.toInt()
    }
    HOSTPORT.matchEntire(s)?.let {
        return it.groups["h"]!!.value to it.groups["p"]!!.value.toInt()
    }
    return s to 443
}

/**
 * Get-QueryVal: первое имя с непустым значением, иначе default.
 * Значение обязано быть строкой; иначе — ClassCastException, который, как и
 * .lower()/.split() в Python-порте, уходит в общий catch парсера -> None.
 * (Только путь vmess может дать не-строку: JSON-значения из конфига.)
 */
fun getQueryVal(q: Map<String, Any>, names: List<String>, default: String = ""): String {
    for (n in names) {
        val v = q[n]
        if (v != null && v != "") {
            @Suppress("UNCHECKED_CAST")
            return v as String
        }
    }
    return default
}

/** Get-BoolVal: "1|true|yes|on" (регистр не важен), пусто -> default. */
fun getBoolVal(q: Map<String, Any>, names: List<String>, default: Boolean = false): Boolean {
    val v = getQueryVal(q, names, "")
    if (v == "") return default
    return TRUTHY.matches(v)
}
