// Паритет с PowerShell 1.0.6: golden-файлы те же, что в vpn-launcher-py
// (снимаются tools/make_golden.ps1 оригинальным core.ps1 на PS 5.1;
// синхронизация — tools/sync-golden.ps1). Сверяем вывод Kotlin-порта с
// выводом PS-версии на тех же фикстурах (структурное сравнение JsonElement,
// как dict-равенство в Python-тестах — порядок ключей не важен).
package com.yoncfall.vpnlauncher.core

import kotlinx.serialization.json.JsonArray
import org.junit.Assert.assertEquals
import org.junit.Test

class GoldenParseNodesTest {

    @Test
    fun `parse plain subscription matches golden`() {
        val actual = parseNodeList(fixtureText("subscription.txt")) { }
        assertEquals(goldenElement("parse-nodes.json"), JsonArray(actual))
    }

    @Test
    fun `parse b64 subscription matches golden`() {
        val actual = parseNodeList(fixtureText("subscription-b64.txt")) { }
        assertEquals(goldenElement("parse-nodes-b64.json"), JsonArray(actual))
    }
}
