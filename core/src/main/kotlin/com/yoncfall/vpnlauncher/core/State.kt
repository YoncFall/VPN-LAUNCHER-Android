// Состояние приложения. Порт Get-VpnState/Save-VpnState (core.ps1:630-641)
// через vpn-launcher-py (state.py). Файл: <install root>/state.json.
// Поля (точно как в PS): subUrl, mode ('tun'), selected, appList, lastNodes,
// autoUrlTest.
//
// Отклонения от 1.0.6 (на поведение не влияют):
//   - PS/Python отдают из файла объект «как есть» (недостающие поля без
//     значений, лишние сохраняются); здесь загрузка собирает известные
//     поля: недостающие -> дефолты, лишние игнорируются. Файлы, записанные
//     Save-VpnState, содержат все поля и читаются одинаково;
//   - UTF-8 без BOM - как python-порт (PS 5.1 пишет BOM: у python json.loads
//     на нём ошибка -> дефолт, здесь то же самое).
package com.yoncfall.vpnlauncher.core

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/** Пустое состояние (дефолт Get-VpnState). */
data class VpnState(
    val subUrl: String = "",
    val mode: String = "tun",
    val selected: String = "",
    val appList: List<String> = emptyList(),
    val lastNodes: List<JsonObject> = emptyList(),
    val autoUrlTest: Boolean = true,
)

val DEFAULT_STATE = VpnState()

/** Файл отсутствует или битый -> дефолт (как catch в PS). */
fun loadState(path: File): VpnState {
    if (!path.isFile) return DEFAULT_STATE
    val text = try {
        path.readText(Charsets.UTF_8)
    } catch (e: Exception) {
        return DEFAULT_STATE
    }
    val obj = try {
        Json.parseToJsonElement(text).jsonObject
    } catch (e: Exception) {
        return DEFAULT_STATE
    }
    return VpnState(
        subUrl = obj.strField("subUrl") ?: DEFAULT_STATE.subUrl,
        mode = obj.strField("mode") ?: DEFAULT_STATE.mode,
        selected = obj.strField("selected") ?: DEFAULT_STATE.selected,
        appList = (obj["appList"] as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            ?: DEFAULT_STATE.appList,
        lastNodes = (obj["lastNodes"] as? JsonArray)
            ?.filterIsInstance<JsonObject>()
            ?: DEFAULT_STATE.lastNodes,
        autoUrlTest = (obj["autoUrlTest"] as? JsonPrimitive)?.booleanOrNull
            ?: DEFAULT_STATE.autoUrlTest,
    )
}

/** Save-VpnState: те же поля, JSON c отступом 2 (indent=2 в python-порте). */
fun saveState(state: VpnState, path: File) {
    val json = buildJsonObject {
        put("subUrl", state.subUrl)
        put("mode", state.mode)
        put("selected", state.selected)
        put("appList", JsonArray(state.appList.map { JsonPrimitive(it) }))
        put("lastNodes", JsonArray(state.lastNodes))
        put("autoUrlTest", state.autoUrlTest)
    }
    val text = Json {
        prettyPrint = true
        prettyPrintIndent = "  "
    }.encodeToString(JsonElement.serializer(), json)
    path.writeText(text, Charsets.UTF_8)
}

private fun JsonObject.strField(key: String): String? =
    (this[key] as? JsonPrimitive)?.contentOrNull
