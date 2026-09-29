// Тесты состояния (зеркало vpn-launcher-py/tests/test_state.py):
// отсутствие файла -> дефолт, битый файл -> дефолт, roundtrip.
package com.yoncfall.vpnlauncher.core

import java.io.File
import java.nio.file.Files
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

class StateTest {

    private fun tmpDir(): File =
        Files.createTempDirectory("vpn-state").toFile().apply { deleteOnExit() }

    @Test
    fun `missing file returns defaults`() {
        val dir = tmpDir()
        val st = loadState(File(dir, "state.json"))
        assertEquals(DEFAULT_STATE, st)
        // результат неизменяем - повторная загрузка пуста
        // (как новый объект из PS на каждый вызов)
        assertEquals(emptyList<String>(), loadState(File(dir, "state.json")).appList)
    }

    @Test
    fun `corrupt file returns defaults`() {
        val p = File(tmpDir(), "state.json")
        p.writeText("{oops", Charsets.UTF_8)
        assertEquals(DEFAULT_STATE, loadState(p))
    }

    @Test
    fun `roundtrip`() {
        val p = File(tmpDir(), "state.json")
        val st = DEFAULT_STATE.copy(
            subUrl = "https://example.com/sub?a=1&b=2",
            selected = "node-1,node-2",
            appList = listOf("game.exe", "some app.exe"),
            autoUrlTest = false,
        )
        saveState(st, p)
        val raw = Json.parseToJsonElement(p.readText(Charsets.UTF_8)).jsonObject
        assertEquals("tun", raw["mode"]!!.jsonPrimitive.content)
        assertEquals(st, loadState(p))
    }
}
