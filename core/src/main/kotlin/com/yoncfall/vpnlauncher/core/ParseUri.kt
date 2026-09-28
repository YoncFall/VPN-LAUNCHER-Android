// Парсеры протоколов. Дословный порт core.ps1:186-388 (ConvertFrom-ProxyUri)
// через vpn-launcher-py (parse_proxy_uri). Сохранены баги 1.0.6:
//   - vmess: при tls/reality q ПОЛНОСТЬЮ пересобирается из security/sni/fp/alpn,
//     transport у vmess+tls не появляется;
//   - vmess: uuid = id || ps (ps как фоллбэк);
//   - null/пустые поля попадают в JSON как есть (как ConvertTo-Json в PS).
// Стек-зависимый текст исключения в журнале может отличаться (только журнал).
// Микро-отклонение (edge только с числами в vmess-JSON, в природе не встречается):
// там, где Python-порт ПРОПУСКАЕТ не-строковое поле (например числовой path в ws
// без tls), здесь чтение поля как String бросает ClassCastException -> нода
// отбрасывается целиком.
package com.yoncfall.vpnlauncher.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

// ------------------------- JSON-малослова для парсера -------------------------

private fun str(v: String) = JsonPrimitive(v)

/** truthiness как в Python: null/false/0/""/пустые коллекции -> false. */
private fun pyTruthy(v: JsonElement?): Boolean = when {
    v == null || v is JsonNull -> false
    v is JsonPrimitive -> when {
        v.isString -> v.content.isNotEmpty()
        v.content == "true" || v.content == "false" -> v.content == "true"
        else -> v.content.toDoubleOrNull()?.let { it != 0.0 } ?: true
    }
    v is JsonArray -> v.isNotEmpty()
    v is JsonObject -> v.isNotEmpty()
    else -> true
}

/** int(...) как в Python: строка/число/bool; ошибка -> исключение (-> None). */
private fun pyInt(v: JsonElement): Int {
    val p = v as? JsonPrimitive
        ?: throw IllegalArgumentException("invalid literal for int(): $v")
    if (p.isString) {
        val s = p.content.trim()
        return s.toIntOrNull()
            ?: throw IllegalArgumentException("invalid literal for int(): '$s'")
    }
    if (p.content == "true") return 1
    if (p.content == "false") return 0
    val d = p.content.toDoubleOrNull()
        ?: throw IllegalArgumentException("invalid literal for int(): ${p.content}")
    return d.toInt()
}

/** Python f"{value}" для JSON-значения (str/int/float/bool/null). internal — нужен parse_node_list. */
internal fun pyStr(v: JsonElement?): String = when {
    v == null || v is JsonNull -> "null"
    v is JsonPrimitive && v.isString -> v.content
    else -> v!!.jsonPrimitive.content
}

// ------------------------- сам парсер -------------------------

/**
 * ConvertFrom-ProxyUri -> JsonObject ноды (ключи type/tag/server/...) | null.
 * Индекс нумерует тег node-N с 1, как Parse-NodeList.
 */
fun parseProxyUri(
    uri: String,
    index: Int,
    logger: (String) -> Unit = AppLog::write,
): JsonObject? {
    val u = splitUri(uri)
    val tag = "node-$index"

    return try {
        when (u["Scheme"]) {
            "vless" -> {
                val body = u["Body"]!!
                val at = body.lastIndexOf('@')
                if (at < 0) return null
                val uuid = body.substring(0, at)
                val (host, port) = splitHostPort(body.substring(at + 1))
                val q = parseQuery(u["Query"]!!)

                val ob = LinkedHashMap<String, JsonElement>()
                ob["type"] = str("vless")
                ob["tag"] = str(tag)
                ob["server"] = str(host)
                ob["server_port"] = JsonPrimitive(port)
                ob["uuid"] = str(uuid)

                val flow = getQueryVal(q, listOf("flow"), "")
                if (flow.isNotEmpty()) ob["flow"] = str(flow)
                newTlsBlock(q, host, false)?.let { ob["tls"] = it }
                newTransportBlock(q, host)?.let { ob["transport"] = it }
                JsonObject(ob)
            }

            "vmess" -> {
                // vmess://base64(json)
                val raw = b64Utf8Decode(u["Body"]!!)
                if (raw.isEmpty() || !raw.trimStart().startsWith("{")) return null
                val jEl = try {
                    Json.parseToJsonElement(raw)
                } catch (e: Exception) {
                    return null
                }
                if (jEl !is JsonObject) return null
                val j = jEl

                // srv/uuid храним СЫРЫМ значением, как Python (j["add"] и т.п.)
                val srvEl = j["add"].takeIf { pyTruthy(it) } ?: j["address"].takeIf { pyTruthy(it) }
                    ?: return null
                val srv = pyStr(srvEl) // строка-подстановка для хелперов/тега
                val port = if (pyTruthy(j["port"])) pyInt(j["port"]!!) else 443
                val uuidEl = j["id"].takeIf { pyTruthy(it) } ?: j["ps"].takeIf { pyTruthy(it) }

                val ob = LinkedHashMap<String, JsonElement>()
                ob["type"] = str("vmess")
                ob["tag"] = str(tag)
                ob["server"] = srvEl
                ob["server_port"] = JsonPrimitive(port)
                ob["uuid"] = uuidEl ?: JsonNull
                ob["alter_id"] = JsonPrimitive(0)

                val aidEl = j["aid"].takeIf { pyTruthy(it) }
                if (aidEl != null && pyInt(aidEl) > 0) {
                    ob["alter_id"] = JsonPrimitive(pyInt(aidEl))
                }

                val q0 = LinkedHashMap<String, Any>()
                for (k in listOf("net", "type", "host", "path", "tls", "sni", "alpn", "fp", "scy")) {
                    val v = j[k]
                    if (pyTruthy(v)) {
                        // строка -> String (обычный путь); не-строка -> сырой
                        // элемент: чтение его как String упадёт, как .lower()
                        // в Python -> общий catch -> None
                        q0[k] = if (v is JsonPrimitive && v.isString) v.content else v!!
                    }
                }
                var q: Map<String, Any> = q0
                val tlsVal = j["tls"]
                if (tlsVal is JsonPrimitive && (tlsVal.content == "tls" || tlsVal.content == "reality")) {
                    // как в PS: q ПОЛНОСТЬЮ пересобирается из этой строки,
                    // net/type/host/path теряются (сохранённый баг 1.0.6)
                    var qs = "security=${tlsVal.content}"
                    val sni = j["sni"]
                    if (pyTruthy(sni)) qs += "&sni=${pyStr(sni)}"
                    val fp = j["fp"]
                    if (pyTruthy(fp)) qs += "&fp=${pyStr(fp)}"
                    val alpn = j["alpn"]
                    if (pyTruthy(alpn)) qs += "&alpn=${pyStr(alpn)}"
                    val nq = parseQuery(qs)
                    newTlsBlock(nq, srv, false)?.let { ob["tls"] = it }
                    q = nq
                }
                newTransportBlock(q, srv)?.let { ob["transport"] = it }
                JsonObject(ob)
            }

            "trojan" -> {
                val body = u["Body"]!!
                val at = body.lastIndexOf('@')
                if (at < 0) return null
                val pw = body.substring(0, at)
                val (host, port) = splitHostPort(body.substring(at + 1))
                val q = parseQuery(u["Query"]!!)

                val ob = LinkedHashMap<String, JsonElement>()
                ob["type"] = str("trojan")
                ob["tag"] = str(tag)
                ob["server"] = str(host)
                ob["server_port"] = JsonPrimitive(port)
                ob["password"] = str(pw)
                newTlsBlock(q, host, true)?.let { ob["tls"] = it }
                newTransportBlock(q, host)?.let { ob["transport"] = it }
                JsonObject(ob)
            }

            "ss" -> {
                val body = u["Body"]!!
                val plain: String
                val host: String
                val port: Int
                if ('@' in body) {
                    val at = body.lastIndexOf('@')
                    val userinfo = body.substring(0, at)
                    val hp = splitHostPort(body.substring(at + 1))
                    host = hp.first
                    port = hp.second
                    val dec = b64Utf8Decode(userinfo)
                    plain = if (':' in dec) dec else userinfo
                } else {
                    val dec = b64Utf8Decode(body)
                    if ('@' !in dec) return null
                    val at = dec.lastIndexOf('@')
                    plain = dec.substring(0, at)
                    val hp = splitHostPort(dec.substring(at + 1))
                    host = hp.first
                    port = hp.second
                }

                val ci = plain.indexOf(':')
                if (ci < 0) return null
                val method = plain.substring(0, ci)
                val password = plain.substring(ci + 1)

                val q = parseQuery(u["Query"]!!)
                val ob = LinkedHashMap<String, JsonElement>()
                ob["type"] = str("shadowsocks")
                ob["tag"] = str(tag)
                ob["server"] = str(host)
                ob["server_port"] = JsonPrimitive(port)
                ob["method"] = str(method)
                ob["password"] = str(password)
                if ("plugin" in q) {
                    val pl = q["plugin"]!!
                    if ("obfs-local" in pl) {
                        ob["plugin"] = str("obfs-local")
                        ob["plugin_opts"] = str("obfs=http;obfs-host=${q["plugin-opts"] ?: ""}")
                    } else if ("v2ray-plugin" in pl) {
                        ob["plugin"] = str("v2ray-plugin")
                        ob["plugin_opts"] = str("mode=websocket")
                    }
                }
                JsonObject(ob)
            }

            "hysteria2", "hy2" -> {
                val body = u["Body"]!!
                val at = body.lastIndexOf('@')
                val pw = if (at >= 0) body.substring(0, at) else ""
                val hp = splitHostPort(if (at >= 0) body.substring(at + 1) else body)
                val host = hp.first
                val port = hp.second
                val q = parseQuery(u["Query"]!!)

                val ob = LinkedHashMap<String, JsonElement>()
                ob["type"] = str("hysteria2")
                ob["tag"] = str(tag)
                ob["server"] = str(host)
                ob["server_port"] = JsonPrimitive(port)
                ob["password"] = str(pw)

                val sni = getQueryVal(q, listOf("sni", "peer"), host)
                val insec = getBoolVal(q, listOf("insecure", "allowInsecure"), false)
                val tls = LinkedHashMap<String, JsonElement>()
                tls["enabled"] = JsonPrimitive(true)
                tls["server_name"] = str(sni)
                if (insec) tls["insecure"] = JsonPrimitive(true)
                val alpn = getQueryVal(q, listOf("alpn"), "")
                if (alpn.isNotEmpty()) {
                    tls["alpn"] = JsonArray(alpn.split(",").map { JsonPrimitive(it) })
                }
                ob["tls"] = JsonObject(tls)

                val obfs = getQueryVal(q, listOf("obfs"), "")
                var obfsPw = getQueryVal(q, listOf("obfs-password", "obfs_password"), "")
                if (obfsPw.isEmpty()) obfsPw = getQueryVal(q, listOf("obfsParam"), "")
                if (obfs.isNotEmpty() && obfsPw.isNotEmpty()) {
                    ob["obfs"] = buildJsonObject {
                        put("type", "salamander")
                        put("password", obfsPw)
                    }
                }
                JsonObject(ob)
            }

            "tuic" -> {
                val body = u["Body"]!!
                val at = body.lastIndexOf('@')
                if (at < 0) return null
                val cred = body.substring(0, at)
                val hp = splitHostPort(body.substring(at + 1))
                val host = hp.first
                val port = hp.second
                val q = parseQuery(u["Query"]!!)
                val ci = cred.indexOf(':')
                val uuid = if (ci >= 0) cred.substring(0, ci) else cred
                val pw = if (ci >= 0) cred.substring(ci + 1) else ""

                val ob = LinkedHashMap<String, JsonElement>()
                ob["type"] = str("tuic")
                ob["tag"] = str(tag)
                ob["server"] = str(host)
                ob["server_port"] = JsonPrimitive(port)
                ob["uuid"] = str(uuid)
                ob["password"] = str(pw)
                ob["congestion_control"] = str(getQueryVal(q, listOf("congestion_control"), "bbr"))

                val sni = getQueryVal(q, listOf("sni", "peer"), host)
                val insec = getBoolVal(q, listOf("insecure", "allowInsecure"), false)
                val tls = LinkedHashMap<String, JsonElement>()
                tls["enabled"] = JsonPrimitive(true)
                tls["server_name"] = str(sni)
                if (insec) tls["insecure"] = JsonPrimitive(true)
                val alpn = getQueryVal(q, listOf("alpn"), "")
                if (alpn.isNotEmpty()) {
                    tls["alpn"] = JsonArray(alpn.split(",").map { JsonPrimitive(it) })
                }
                ob["tls"] = JsonObject(tls)
                JsonObject(ob)
            }

            // http/socks и любые неизвестные схемы -> null (default в PS-switch)
            else -> null
        }
    } catch (exc: Exception) {
        // как catch в ConvertFrom-ProxyUri (текст исключения — только журнал)
        logger("parse error [${u["Scheme"]}] ${uri.take(60)}: ${exc.message ?: exc}")
        null
    }
}
