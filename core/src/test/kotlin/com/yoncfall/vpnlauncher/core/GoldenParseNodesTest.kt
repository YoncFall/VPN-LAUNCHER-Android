// Паритет с PowerShell 1.0.6: golden-файлы те же, что в vpn-launcher-py
// (снимаются tools/make_golden.ps1 оригинальным core.ps1 на PS 5.1;
// синхронизация — tools/sync-golden.ps1). Сверяем вывод Kotlin-порта с
// выводом PS-версии на тех же фикстурах (структурное сравнение JsonElement,
// как dict-равенство в Python-тестах — порядок ключей не важен).
package com.yoncfall.vpnlauncher.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import org.junit.Assert.assertEquals
import org.junit.Test

class GoldenParseNodesTest {

    private fun bytes(path: String): ByteArray =
        requireNotNull(javaClass.getResourceAsStream(path)) { "нет ресурса $path" }
            .use { it.readBytes() }

    private fun text(path: String): String = String(bytes(path), Charsets.UTF_8)

    private fun golden(name: String): JsonElement =
        // PS 5.1 пишет golden в UTF-8 c BOM — снимаем его перед парсером
        Json.parseToJsonElement(text("/golden/$name").removePrefix("\uFEFF"))

    private fun fixture(name: String): String = text("/fixtures/$name")

    @Test
    fun `parse plain subscription matches golden`() {
        val actual = parseNodeList(fixture("subscription.txt")) { }
        assertEquals(golden("parse-nodes.json"), JsonArray(actual))
    }

    @Test
    fun `parse b64 subscription matches golden`() {
        val actual = parseNodeList(fixture("subscription-b64.txt")) { }
        assertEquals(golden("parse-nodes-b64.json"), JsonArray(actual))
    }
}
