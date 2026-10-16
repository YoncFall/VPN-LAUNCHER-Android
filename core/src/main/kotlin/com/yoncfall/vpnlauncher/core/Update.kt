// Обновления (2.1.0): проверка новой версии на сайте (apps.json).
//
// Источник версий - apps.json на GitHub Pages: он обновляется при каждом
// релизе (тот же файл, что показывает версии на сайте) и не имеет
// rate-limit'ов GitHub API (общие exit-IP VPN делили бы лимит на всех).
// Логика разбора/сравнения отделена от сети: fetch живёт в AppViewModel,
// здесь - только чистые функции (как CLEAN_DOMAINS - тесты в :core:test).
package com.yoncfall.vpnlauncher.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

/** apps.json на GitHub Pages (тот же, что сайт показывает как «Версия»). */
const val APPS_JSON_URL = "https://yoncfall.github.io/apps.json"

/** slug Android-приложения на сайте. */
const val SLUG = "vpn-launcher-android"

/** 'v2.0.5' -> [2, 0, 5]; нечисловые хвосты игнорируются. */
fun parseVersion(text: String): List<Int> = Regex("\\d+").findAll(text).map { it.value.toInt() }.toList()

/** true, если latest строго новее current (сравнение по числам, не строкам). */
fun isNewer(latest: String, current: String): Boolean {
    val a = parseVersion(latest)
    val b = parseVersion(current)
    val n = maxOf(a.size, b.size)
    val pa = a + List(n - a.size) { 0 }
    val pb = b + List(n - b.size) { 0 }
    for (i in 0 until n) {
        if (pa[i] != pb[i]) return pa[i] > pb[i]
    }
    return false
}

/**
 * Найденная новая версия: [apkUrl] - универсальный APK (все ABI),
 * [apkUrlAbi] - APK под arm64-v8a (меньше); [pageUrl] - страница релиза.
 */
data class UpdateInfo(
    val version: String,
    val apkUrl: String,
    val apkUrlAbi: String,
    val pageUrl: String,
)

/** Найти приложение по slug в apps.json (categories[].apps[]). */
private fun pickApp(payload: JsonObject, slug: String): JsonObject? {
    val categories = payload["categories"] as? JsonArray ?: return null
    for (cat in categories) {
        val apps = (cat as? JsonObject)?.get("apps") as? JsonArray ?: continue
        for (app in apps) {
            val obj = app as? JsonObject ?: continue
            val s = (obj["slug"] as? JsonPrimitive)?.contentOrNull
            if (s == slug) return obj
        }
    }
    return null
}

private fun strField(app: JsonObject, key: String): String =
    (app[key] as? JsonPrimitive)?.contentOrNull ?: ""

/**
 * Разобрать apps.json; UpdateInfo только если там версия новее current.
 * Битый JSON / нет slug / версия не новее -> null (тихо, без плашки).
 */
fun parseLatest(payloadText: String, slug: String, current: String): UpdateInfo? {
    val payload = try {
        kotlinx.serialization.json.Json.parseToJsonElement(payloadText).jsonObject
    } catch (e: Exception) {
        return null
    }
    val app = pickApp(payload, slug) ?: return null
    val latest = strField(app, "version")
    if (latest.isEmpty() || !isNewer(latest, current)) return null
    return UpdateInfo(
        version = latest,
        apkUrl = strField(app, "download"),
        apkUrlAbi = strField(app, "downloadZip"),
        pageUrl = strField(app, "releasePage"),
    )
}
