// TLS- и транспорт-блоки. Порт core.ps1:105-182 (New-TlsBlock /
// New-TransportBlock) через vpn-launcher-py. Поведение 1.0.6 дословно.
package com.yoncfall.vpnlauncher.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * New-TlsBlock -> JsonObject | null (в PS: хеш-таблица или $null).
 * q: словарь строк (путь vmess может дать не-строку — тогда getQueryVal
 * бросает ClassCastException, и парсер вернёт None, как в Python-порте).
 */
fun newTlsBlock(q: Map<String, Any>, defaultSni: String, tlsByDefault: Boolean): JsonObject? {
    val sec = getQueryVal(q, listOf("security"), "").lowercase()
    val isReality = sec == "reality"
    val enabled = tlsByDefault || sec == "tls" || isReality
    if (!enabled) return null

    val sni = getQueryVal(q, listOf("sni", "peer", "host"), defaultSni)
    val fp = getQueryVal(q, listOf("fp"), "")
    val insec = getBoolVal(q, listOf("allowInsecure", "insecure", "allow_insecure"), false)
    val alpn = getQueryVal(q, listOf("alpn"), "")

    val tls = LinkedHashMap<String, JsonElement>()
    tls["enabled"] = JsonPrimitive(true)
    tls["server_name"] = JsonPrimitive(sni)
    if (insec) tls["insecure"] = JsonPrimitive(true)
    if (fp.isNotEmpty()) {
        tls["utls"] = buildJsonObject {
            put("enabled", true)
            put("fingerprint", fp)
        }
    }
    if (alpn.isNotEmpty()) {
        tls["alpn"] = JsonArray(alpn.split(",").map { JsonPrimitive(it) })
    }

    if (isReality) {
        val pbk = getQueryVal(q, listOf("pbk", "public-key", "publicKey"), "")
        val sid = getQueryVal(q, listOf("sid", "short-id", "shortId"), "")
        val r = LinkedHashMap<String, JsonElement>()
        r["enabled"] = JsonPrimitive(true)
        if (pbk.isNotEmpty()) r["public_key"] = JsonPrimitive(pbk)
        if (sid.isNotEmpty()) r["short_id"] = JsonPrimitive(sid)
        tls["reality"] = JsonObject(r)
        if (sni.isEmpty()) {
            tls["server_name"] = JsonPrimitive(getQueryVal(q, listOf("host"), ""))
        }
    }
    return JsonObject(tls)
}

/** New-TransportBlock -> JsonObject | null. */
fun newTransportBlock(q: Map<String, Any>, defaultHost: String): JsonObject? {
    val net = getQueryVal(q, listOf("type", "net", "network", "obfs"), "tcp").lowercase()
    val path = getQueryVal(q, listOf("path"), "")
    val hst = getQueryVal(q, listOf("host"), defaultHost)
    val svc = getQueryVal(q, listOf("serviceName", "servicename"), "")
    val hType = getQueryVal(q, listOf("headerType"), "")

    if (net == "ws" || net == "websocket") {
        return buildJsonObject {
            put("type", "ws")
            if (path.isNotEmpty()) put("path", path)
            if (hst.isNotEmpty()) {
                put("headers", buildJsonObject { put("Host", hst) })
            }
        }
    }
    if (net == "grpc") {
        return buildJsonObject {
            put("type", "grpc")
            if (svc.isNotEmpty()) put("service_name", svc)
        }
    }
    if (net == "h2") {
        return buildJsonObject {
            put("type", "http")
            if (hst.isNotEmpty()) put("host", JsonArray(hst.split(",").map { JsonPrimitive(it) }))
            if (path.isNotEmpty()) put("path", path)
        }
    }
    if (net == "http") {
        if (hType != "http") return null
        return buildJsonObject {
            put("type", "http")
            if (path.isNotEmpty()) put("path", JsonArray(path.split(",").map { JsonPrimitive(it) }))
            if (hst.isNotEmpty()) put("host", JsonArray(hst.split(",").map { JsonPrimitive(it) }))
        }
    }
    if (net == "httpupgrade") {
        return buildJsonObject {
            put("type", "httpupgrade")
            put("host", hst)
            if (path.isNotEmpty()) put("path", path)
        }
    }
    if (net == "quic") {
        return buildJsonObject { put("type", "quic") }
    }
    return null
}
