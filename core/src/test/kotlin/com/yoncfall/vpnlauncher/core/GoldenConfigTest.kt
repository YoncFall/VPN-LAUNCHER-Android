// Паритет с PowerShell 1.0.6: golden-файлы те же, что в vpn-launcher-py
// (снимаются tools/make_golden.ps1 оригинальным core.ps1 на PS 5.1).
// Сверяем вывод Kotlin-порта с выводом PS-версии на тех же фикстурах
// (структурное сравнение JsonElement, как dict-равенство в Python-тестах).
//
// Включает golden сборки конфига (CONFIG_CASES desktop-теста) и прогон
// сгенерированных конфигов через `sing-box check` (движок ищется как в
// vpn-launcher-py/conftest.py: SING_BOX_EXE -> %TEMP%\opencode\singbox;
// тест пропускается, если движка нет).
package com.yoncfall.vpnlauncher.core

import java.io.File
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test

class GoldenConfigTest {

    private val nodes: List<JsonObject> by lazy {
        parseNodeList(fixtureText("subscription.txt")) { }
    }

    /**
     * Конфиг в том виде, какой дал бы оригинальный core.ps1 1.0.6.
     *
     * Golden сняты PowerShell-версией, у которой нет правила чистых домен
     * (CLEAN_DOMAINS, см. core/Config.kt): с 13.10.2026 Kotlin-версия
     * сознательно уходит от паритета, чтобы антифрод ВК/Ozon/WB не видел IP
     * туннеля. Для сравнения с эталоном это правило вырезается; фактическое
     * содержимое правила проверяет ConfigTest.clean domains route direct
     * after sniff before per-app.
     */
    private fun psParityView(built: JsonObject): JsonObject {
        val rules = built["route"]!!.jsonObject["rules"]!!.jsonArray
        val kept = JsonArray(rules.filter { r -> "domain" !in r.jsonObject })
        val route = buildJsonObject {
            built["route"]!!.jsonObject.forEach { (k, v) ->
                put(k, if (k == "rules") kept else v)
            }
        }
        return buildJsonObject {
            built.forEach { (k, v) -> put(k, if (k == "route") route else v) }
        }
    }

    @Test
    fun `config tun matches golden`() {
        val built = buildSingBoxConfig(
            nodes,
            mode = "tun",
            appList = listOf("mygame.exe", "steam.exe"),
            installRoot = "vpn-golden-root",
        )
        assertEquals(goldenElement("config-tun.json"), psParityView(built))
    }

    @Test
    fun `config proxy selected matches golden`() {
        val built = buildSingBoxConfig(
            nodes,
            selected = listOf("node-1", "node-8", "node-16"),
            mode = "proxy",
            onlySelected = true,
            installRoot = "vpn-golden-root",
        )
        assertEquals(goldenElement("config-proxy-selected.json"), psParityView(built))
    }

    @Test
    fun `config single matches golden`() {
        val built = buildSingBoxConfig(
            nodes,
            selected = listOf("node-7"),
            mode = "tun",
            onlySelected = true,
            installRoot = "vpn-golden-root",
        )
        assertEquals(goldenElement("config-single.json"), psParityView(built))
    }

    @Test
    fun `generated configs pass sing-box check`() {
        val exe = discoverSingBox()
        Assume.assumeTrue("sing-box.exe не найден (задайте SING_BOX_EXE)", exe != null)

        val tmp = File.createTempFile("vlgolden", "").let { f -> f.delete(); f.mkdirs(); f }
        try {
            // case 1: tun + app_list
            val c1 = buildSingBoxConfig(
                nodes, mode = "tun",
                appList = listOf("mygame.exe", "steam.exe"), installRoot = "vpn-golden-root",
            )
            assertCheck(testSingBoxConfig(writeConfig(c1, File(tmp, "config-tun.json")), exe!!))
            // case 2: proxy + selected/onlySelected
            val c2 = buildSingBoxConfig(
                nodes, selected = listOf("node-1", "node-8", "node-16"),
                mode = "proxy", onlySelected = true, installRoot = "vpn-golden-root",
            )
            assertCheck(testSingBoxConfig(writeConfig(c2, File(tmp, "config-proxy-selected.json")), exe!!))
            // case 3: single node -> selector
            val c3 = buildSingBoxConfig(
                nodes, selected = listOf("node-7"),
                mode = "tun", onlySelected = true, installRoot = "vpn-golden-root",
            )
            assertCheck(testSingBoxConfig(writeConfig(c3, File(tmp, "config-single.json")), exe!!))
        } finally {
            tmp.deleteRecursively()
        }
    }

    private fun assertCheck(result: Pair<Boolean, String>) {
        assertTrue("sing-box check failed: ${result.second}", result.first)
    }

    /** env SING_BOX_EXE -> %TEMP%/opencode/singbox/**/sing-box.exe (последний), как conftest.py. */
    private fun discoverSingBox(): File? {
        System.getenv("SING_BOX_EXE")?.let { if (File(it).isFile) return File(it) }
        val root = File(System.getProperty("java.io.tmpdir"), "opencode/singbox")
        if (root.isDirectory) {
            val hits = root.walkTopDown()
                .filter { it.isFile && it.name == "sing-box.exe" }
                .sortedBy { it.path }
                .toList()
            if (hits.isNotEmpty()) return hits.last()
        }
        return null
    }
}
