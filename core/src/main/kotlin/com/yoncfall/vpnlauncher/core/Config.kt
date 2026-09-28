// Генерация и проверка конфига sing-box. Порт src/core.ps1:457-626 через
// vpn-launcher-py (config.py: GAME_SAFE_PROCESSES / build_sing_box_config /
// write_config / new_sing_box_config / test_sing_box_config).
//
// Отклонения от desktop-порта (сознательные, поведение конфига не меняют):
//   - installRoot обязателен (там default = paths.install_root() — desktop-специфика;
//     Android передаёт каталог файлов приложения);
//   - writeConfig: путь обязателен (desktop default = CONFIG_FILE);
//   - testSingBoxConfig: путь к движку обязателен; таймаут -> ConfigError
//     (desktop - subprocess.TimeoutExpired), отсутствие движка -> IOException
//     (desktop - FileNotFoundError); creationflags не нужны (нет окна консоли);
//   - casefold() в only_selected -> lowercase() (locale-independent): различия
//     только для Unicode-спецфолдов (ß->ss); теги node-N и обычные имена не
//     отличаются;
//   - лог через AppLog (desktop write_log пишет в файл приложения).
package com.yoncfall.vpnlauncher.core

import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

// Процессы, которые ВСЕГДА идут напрямую, минуя туннель.
// Игры и античиты не должны видеть подмену маршрута/адреса, а Steam и EAC/BE
// не должны получать соединения из-за VPN. Список нельзя потерять - он в коде.
val GAME_SAFE_PROCESSES: List<String> = listOf(
    "steam.exe", "steamwebhelper.exe", "steamservice.exe", "steamerrorreporter.exe", "gameoverlayui64.exe",
    "RustClient.exe", "Rust.exe", "UnityCrashHandler64.exe",
    "EasyAntiCheat.exe", "EasyAntiCheat_EOS.exe", "EasyAntiCheat_Setup.exe", "EACLauncher.exe",
    "DayZ_x64.exe", "DayZDiag_x64.exe", "DayZLauncher.exe", "DayZ_BE.exe", "BEService_x64.exe", "DayZServer_x64.exe",
    "Phasmophobia.exe", "REPO.exe", "ONCE_HUMAN.exe",
    "GTA5.exe", "GTA5_3258.exe", "ragemp_v.exe", "cs2.exe",
)

const val DEFAULT_TEST_URL = "https://www.gstatic.com/generate_204"

/** vpn_launcher.paths.SOCKS_PORT. */
const val SOCKS_PORT = 20808

// Ключи ноды, переносимые в outbound как есть (core.ps1:486)
private val NODE_KEYS = listOf(
    "uuid", "password", "method", "alter_id", "flow", "plugin", "plugin_opts",
    "congestion_control", "tls", "transport", "obfs",
)

// Локальные сети идут напрямую (core.ps1:536)
private val LOCAL_CIDRS = listOf(
    "127.0.0.0/8", "10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16",
    "224.0.0.0/4", "fe80::/10", "fc00::/7",
)

private val ANSI_RE = Regex("\u001B\\[[0-9;]*m")

/** PS `throw` из New-SingBoxConfig. */
class ConfigError(message: String) : RuntimeException(message)

/** Select-Object -Unique (core.ps1:523): регистрозависимо, первый выигрывает. */
fun uniq(items: List<String>): List<String> {
    val out = ArrayList<String>()
    for (x in items) {
        if (x.isNotEmpty() && x !in out) out.add(x)
    }
    return out
}

/**
 * New-SingBoxConfig без записи файла -> JsonObject конфига (для golden-тестов).
 *
 * installRoot - корень для cache.db (по умолчанию paths.install_root() в desktop-порте,
 * здесь параметр обязателен); в тестах передаётся фиктивный путь.
 */
fun buildSingBoxConfig(
    nodes: List<JsonObject>,
    selected: List<String> = emptyList(),
    mode: String = "tun",
    appList: List<String> = emptyList(),
    testUrl: String = DEFAULT_TEST_URL,
    onlySelected: Boolean = false,
    installRoot: String,
): JsonObject {
    if (mode != "tun" && mode != "proxy") {
        throw ConfigError("Неизвестный режим: '$mode' (ожидается 'tun' или 'proxy')")
    }

    var pool: List<JsonObject> = nodes
    if (onlySelected && selected.isNotEmpty()) {
        // как PS `-contains` (регистронезависимо)
        val sel = selected.map { it.lowercase() }.toSet()
        pool = nodes.filter { n -> n["tag"]!!.jsonPrimitive.content.lowercase() in sel }
    }

    val out = ArrayList<JsonElement>()
    val tags = ArrayList<String>()

    for (n in pool) {
        val o = LinkedHashMap<String, JsonElement>()
        o["type"] = n["type"]!!
        o["tag"] = n["tag"]!!
        o["server"] = n["server"]!!
        o["server_port"] = n["server_port"]!!
        for (k in NODE_KEYS) {
            val v = n[k]
            if (v != null && v !is JsonNull) o[k] = v
        }
        out.add(JsonObject(o))
        tags.add(n["tag"]!!.jsonPrimitive.content)
    }

    // direct для процесса самого sing-box и служебных адресов
    out.add(buildJsonObject {
        put("type", "direct")
        put("tag", "direct")
    })

    // группа выбора
    val useTags = if (selected.isNotEmpty()) selected else tags
    when {
        useTags.size > 1 -> out.add(buildJsonObject {
            put("type", "urltest")
            put("tag", "proxy-group")
            put("outbounds", JsonArray(useTags.map { JsonPrimitive(it) }))
            put("url", testUrl)
            put("interval", "10m")
            put("tolerance", 50)
            put("interrupt_exist_connections", true)
        })
        useTags.size == 1 -> out.add(buildJsonObject {
            put("type", "selector")
            put("tag", "proxy-group")
            put("outbounds", JsonArray(listOf(JsonPrimitive(useTags[0]))))
            put("default", useTags[0])
            put("interrupt_exist_connections", true)
        })
        else -> throw ConfigError("Нет ни одного сервера")
    }

    // маршрутизация
    val rules = ArrayList<JsonElement>()
    rules.add(buildJsonObject { put("action", "sniff") })
    rules.add(buildJsonObject {
        put("protocol", "dns")
        put("action", "hijack-dns")
    })

    // per-app: перечисленные приложения идут НАПРЯМУЮ (важно для игр/античита)
    // action обязателен с sing-box 1.11, outbound внутри правила помечен deprecated
    val directApps = uniq(GAME_SAFE_PROCESSES + appList)
    if (mode == "tun" && directApps.isNotEmpty()) {
        rules.add(buildJsonObject {
            put("action", "route")
            put("process_name", JsonArray(directApps.map { JsonPrimitive(it) }))
            put("outbound", "direct")
        })
    }

    // локальные сети идём напрямую (в TUN это обязательно)
    rules.add(buildJsonObject {
        put("action", "route")
        put("ip_cidr", JsonArray(LOCAL_CIDRS.map { JsonPrimitive(it) }))
        put("outbound", "direct")
    })

    val inbounds = ArrayList<JsonElement>()
    if (mode == "tun") {
        inbounds.add(buildJsonObject {
            put("type", "tun")
            put("tag", "tun-in")
            put("interface_name", "vpn-launcher-tun")
            put("address", JsonArray(listOf(JsonPrimitive("172.19.0.1/30"), JsonPrimitive("fdfe:dcba:9876::1/126"))))
            put("mtu", 1500)
            put("auto_route", true)
            put("strict_route", true)
            put("stack", "mixed")
        })
    } else {
        inbounds.add(buildJsonObject {
            put("type", "socks")
            put("tag", "socks-in")
            put("listen", "127.0.0.1")
            put("listen_port", SOCKS_PORT)
        })
        inbounds.add(buildJsonObject {
            put("type", "http")
            put("tag", "http-in")
            put("listen", "127.0.0.1")
            put("listen_port", SOCKS_PORT + 1)
        })
    }

    val root = File(installRoot)
    return buildJsonObject {
        put("log", buildJsonObject {
            put("level", "info")
            put("timestamp", true)
        })
        put("dns", buildJsonObject {
            put("servers", JsonArray(listOf(
                buildJsonObject {
                    put("tag", "dns-local")
                    put("type", "local")
                },
                buildJsonObject {
                    put("tag", "dns-out")
                    put("type", "udp")
                    put("server", "1.1.1.1")
                    put("server_port", 53)
                },
            )))
            put("final", "dns-out")
        })
        put("inbounds", JsonArray(inbounds))
        put("outbounds", JsonArray(out))
        put("route", buildJsonObject {
            put("rules", JsonArray(rules))
            put("auto_detect_interface", true)
            put("default_domain_resolver", buildJsonObject { put("server", "dns-local") })
            put("final", "proxy-group")
        })
        put("experimental", buildJsonObject {
            put("cache_file", buildJsonObject {
                put("enabled", true)
                put("path", File(root, "cache.db").path)
            })
        })
    }
}

/** [IO.File]::WriteAllText UTF-8 без BOM; JSON с 4 пробелами (как ConvertTo-Json), с \n на конце. */
fun writeConfig(cfg: JsonObject, path: File): File {
    val json = Json { prettyPrint = true }
    path.writeText(json.encodeToString(JsonElement.serializer(), cfg) + "\n")
    return path
}

/**
 * New-SingBoxConfig дословно: сборка + запись файла + лог -> путь к config.json.
 * PS логирует список direct-apps при сборке правил (core.ps1:530).
 */
fun newSingBoxConfig(
    nodes: List<JsonObject>,
    selected: List<String> = emptyList(),
    mode: String = "tun",
    appList: List<String> = emptyList(),
    testUrl: String = DEFAULT_TEST_URL,
    onlySelected: Boolean = false,
    installRoot: String,
    path: File,
    logger: (String) -> Unit = AppLog::write,
): File {
    val cfg = buildSingBoxConfig(nodes, selected, mode, appList, testUrl, onlySelected, installRoot)
    val p = writeConfig(cfg, path)
    if (mode == "tun") {
        val directApps = uniq(GAME_SAFE_PROCESSES + appList)
        if (directApps.isNotEmpty()) {
            logger("  direct-exclude apps: " + directApps.joinToString(", "))
        }
    }
    val outCount = cfg["outbounds"]!!.jsonArray.size
    val finalTag = cfg["route"]!!.jsonObject["final"]!!.jsonPrimitive.content
    logger("config written: $outCount outbounds, mode=$mode, final=$finalTag")
    return p
}

/**
 * Test-SingBoxConfig: `sing-box check -c <path>` -> (ok, stderr-текст).
 * IOException, если движок не найден (как Start-Process в PS).
 *
 * stderr читается ПОСЛЕ waitFor: вывод `check` мал (<64К буфера pipe),
 * переполнение невозможно — как communicate() в Python с фиксированным таймаутом.
 */
fun testSingBoxConfig(path: File, singBox: File, timeoutMs: Long = 30_000): Pair<Boolean, String> {
    val proc = ProcessBuilder(singBox.path, "check", "-c", path.path).start()
    if (!proc.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
        proc.destroyForcibly()
        throw ConfigError("sing-box check не завершился за $timeoutMs мс")
    }
    val errBytes = proc.errorStream.readBytes()
    proc.inputStream.readBytes() // дочитать stdout (после завершения - сразу EOF)
    var err = String(errBytes, Charsets.UTF_8)
    err = ANSI_RE.replace(err, "").trim()
    if (err.isEmpty()) err = "без вывода"
    return (proc.exitValue() == 0) to err
}
