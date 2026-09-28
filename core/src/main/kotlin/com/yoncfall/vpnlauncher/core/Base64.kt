// Base64. Порт core.ps1:35-51 (ConvertFrom-B64Utf8 / ConvertTo-B64Utf8)
// через vpn-launcher-py (b64_utf8_decode / b64_utf8_encode).
package com.yoncfall.vpnlauncher.core

import java.util.Base64 as JBase64

/**
 * ConvertFrom-B64Utf8: trim, убрать пробельные, url-safe -> standard, паддинг,
 * base64 -> UTF-8 (невалидные байты -> U+FFFD, как Encoding.UTF8.GetString);
 * "" при любой ошибке (невалидный символ, rem==1).
 */
fun b64Utf8Decode(s: String): String {
    if (s.isEmpty()) return ""
    val t = s.trim().filterNot { it.isWhitespace() }
    val u = t.replace('-', '+').replace('_', '/')
    val rem = u.length % 4
    val padded = when (rem) {
        2 -> u + "=="
        3 -> u + "="
        1 -> return ""
        else -> u
    }
    val raw = try {
        // базовый декодер строг, как b64decode(..., validate=True): чужие символы -> ошибка
        JBase64.getDecoder().decode(padded)
    } catch (e: IllegalArgumentException) {
        return ""
    }
    return String(raw, Charsets.UTF_8)
}

/** ConvertTo-B64Utf8: UTF-8 -> standard base64 (с паддингом). */
fun b64Utf8Encode(s: String): String =
    JBase64.getEncoder().encodeToString(s.toByteArray(Charsets.UTF_8))
