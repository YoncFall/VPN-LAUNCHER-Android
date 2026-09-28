// Логика сборки конфига (порт desktop tests/test_config.py):
// ошибки режима/пустого пула, форма outbound'ов, регистрозависимость
// only_selected и дедупа, inbounds, запись файла и лог.
package com.yoncfall.vpnlauncher.core

import java.io.File
import java.io.IOException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ConfigTest {

    private fun node(tag: String): JsonObject = buildJsonObject {
        put("type", "vless")
        put("tag", tag)
        put("server", "s.example")
        put("server_port", 443)
        put("uuid", "u-1")
        put("tls", buildJsonObject {
            put("enabled", true)
            put("server_name", "s.example")
        })
    }

    private val twoNodes = listOf(node("node-1"), node("node-2"))

    private fun tempDir(): File =
        File.createTempFile("vlcfg", "").let { f -> f.delete(); f.mkdirs(); f }

    // ---------------- ошибки ----------------

    @Test
    fun `bad mode raises`() {
        try {
            buildSingBoxConfig(twoNodes, mode = "weird", installRoot = "r")
            fail("ожидали ConfigError")
        } catch (e: ConfigError) {
            assertEquals("Неизвестный режим: 'weird' (ожидается 'tun' или 'proxy')", e.message)
        }
    }

    @Test
    fun `no servers raises`() {
        try {
            buildSingBoxConfig(emptyList(), installRoot = "r")
            fail("ожидали ConfigError")
        } catch (e: ConfigError) {
            assertEquals("Нет ни одного сервера", e.message)
        }
    }

    // ---------------- форма конфига ----------------

    @Test
    fun `top level shape`() {
        val c = buildSingBoxConfig(twoNodes, installRoot = "r")
        assertEquals(setOf("log", "dns", "inbounds", "outbounds", "route", "experimental"), c.keys)
        assertEquals("info", c["log"]!!.jsonObject["level"]!!.jsonPrimitive.content)
        assertEquals("dns-out", c["dns"]!!.jsonObject["final"]!!.jsonPrimitive.content)
        assertEquals("proxy-group", c["route"]!!.jsonObject["final"]!!.jsonPrimitive.content)
        assertEquals(
            "r\\cache.db".replace('\\', File.separatorChar),
            c["experimental"]!!.jsonObject["cache_file"]!!.jsonObject["path"]!!.jsonPrimitive.content,
        )
    }

    @Test
    fun `outbound copy keys and group`() {
        val c = buildSingBoxConfig(twoNodes, installRoot = "r")
        val out = c["outbounds"]!!.jsonArray.map { it.jsonObject }
        assertEquals(4, out.size) // 2 ноды + direct + группа
        val first = out[0]
        for (k in listOf("type", "tag", "server", "server_port", "uuid", "tls")) {
            assertTrue("нет ключа $k", k in first)
        }
        assertEquals("vless", first["type"]!!.jsonPrimitive.content)
        assertEquals(443, first["server_port"]!!.jsonPrimitive.content.toInt())
        assertEquals("direct", out[2]["tag"]!!.jsonPrimitive.content)
        assertEquals("urltest", out[3]["type"]!!.jsonPrimitive.content)
        assertEquals("proxy-group", out[3]["tag"]!!.jsonPrimitive.content)
        assertEquals(2, out[3]["outbounds"]!!.jsonArray.size)
    }

    @Test
    fun `single selected node becomes selector`() {
        val c = buildSingBoxConfig(
            twoNodes, selected = listOf("node-2"), onlySelected = true, installRoot = "r",
        )
        val out = c["outbounds"]!!.jsonArray.map { it.jsonObject }
        assertEquals(3, out.size) // 1 нода + direct + группа
        val group = out[2]
        assertEquals("selector", group["type"]!!.jsonPrimitive.content)
        assertEquals("node-2", group["default"]!!.jsonPrimitive.content)
        assertEquals(1, group["outbounds"]!!.jsonArray.size)
    }

    @Test
    fun `selected without only_selected keeps all outbounds`() {
        val c = buildSingBoxConfig(twoNodes, selected = listOf("node-2"), installRoot = "r")
        val out = c["outbounds"]!!.jsonArray.map { it.jsonObject }
        assertEquals(4, out.size) // пул не режется: 2 ноды + direct + группа
        // но группа строится из selected
        assertEquals(
            listOf("node-2"),
            out[3]["outbounds"]!!.jsonArray.map { it.jsonPrimitive.content },
        )
    }

    @Test
    fun `only_selected is case insensitive like ps contains`() {
        val c = buildSingBoxConfig(
            twoNodes, selected = listOf("NODE-1"), onlySelected = true, installRoot = "r",
        )
        val out = c["outbounds"]!!.jsonArray.map { it.jsonObject }
        assertEquals(3, out.size)
        assertEquals("node-1", out[0]["tag"]!!.jsonPrimitive.content)
    }

    // ---------------- правила/inbounds ----------------

    @Test
    fun `tun inbound`() {
        val c = buildSingBoxConfig(twoNodes, mode = "tun", installRoot = "r")
        val tun = c["inbounds"]!!.jsonArray[0].jsonObject
        assertEquals("tun", tun["type"]!!.jsonPrimitive.content)
        assertEquals("vpn-launcher-tun", tun["interface_name"]!!.jsonPrimitive.content)
        assertEquals(true, tun["auto_route"]!!.jsonPrimitive.content.toBoolean())
        assertEquals(true, tun["strict_route"]!!.jsonPrimitive.content.toBoolean())
        assertEquals("mixed", tun["stack"]!!.jsonPrimitive.content)
        assertEquals(1500, tun["mtu"]!!.jsonPrimitive.content.toInt())
        assertEquals(2, tun["address"]!!.jsonArray.size)
    }

    @Test
    fun `proxy inbounds and socks port`() {
        val c = buildSingBoxConfig(twoNodes, mode = "proxy", installRoot = "r")
        val ins = c["inbounds"]!!.jsonArray.map { it.jsonObject }
        assertEquals(2, ins.size)
        assertEquals(
            mapOf("type" to "socks", "tag" to "socks-in", "listen" to "127.0.0.1", "listen_port" to "$SOCKS_PORT"),
            ins[0].mapValues { it.value.jsonPrimitive.content },
        )
        assertEquals(
            mapOf("type" to "http", "tag" to "http-in", "listen" to "127.0.0.1", "listen_port" to "${SOCKS_PORT + 1}"),
            ins[1].mapValues { it.value.jsonPrimitive.content },
        )
    }

    @Test
    fun `tun has process and local rules`() {
        val c = buildSingBoxConfig(
            twoNodes, mode = "tun", appList = listOf("mygame.exe"), installRoot = "r",
        )
        val rules = c["route"]!!.jsonObject["rules"]!!.jsonArray.map { it.jsonObject }
        // sniff + dns hijack + process + local cidr
        assertEquals(4, rules.size)
        assertEquals("sniff", rules[0]["action"]!!.jsonPrimitive.content)
        assertEquals("hijack-dns", rules[1]["action"]!!.jsonPrimitive.content)
        val process = rules[2]
        assertEquals("route", process["action"]!!.jsonPrimitive.content)
        assertEquals("direct", process["outbound"]!!.jsonPrimitive.content)
        val names = process["process_name"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertTrue(names.contains("mygame.exe"))
        assertTrue(names.contains("steam.exe"))
        assertTrue(names.contains("cs2.exe"))
        assertEquals(7, rules[3]["ip_cidr"]!!.jsonArray.size)
    }

    @Test
    fun `proxy mode skips process rule`() {
        val c = buildSingBoxConfig(twoNodes, mode = "proxy", installRoot = "r")
        val rules = c["route"]!!.jsonObject["rules"]!!.jsonArray.map { it.jsonObject }
        assertEquals(3, rules.size) // без per-app правила
    }

    // ---------------- дедуп ----------------

    @Test
    fun `duplicate app dedup keeps first position`() {
        assertEquals(listOf("a", "b"), uniq(listOf("a", "b", "a")))
    }

    @Test
    fun `app dedup is case sensitive like select object unique`() {
        // "Steam.exe" != "steam.exe" -> остаются оба (как Select-Object -Unique на PS 5.1)
        val u = uniq(listOf("steam.exe", "Steam.exe"))
        assertEquals(listOf("steam.exe", "Steam.exe"), u)
        val apps = uniq(GAME_SAFE_PROCESSES + listOf("Steam.exe"))
        assertTrue(apps.contains("Steam.exe"))
        assertTrue(apps.contains("steam.exe"))
        // пустые значения отбрасываются
        assertEquals(listOf("x"), uniq(listOf("", "x", "")))
    }

    // ---------------- запись и лог ----------------

    @Test
    fun `write config roundtrip utf8 no bom`() {
        val dir = tempDir()
        try {
            val cfg = buildSingBoxConfig(listOf(node("Сервер 1")), installRoot = "r")
            val p = writeConfig(cfg, File(dir, "config.json"))
            val bytes = p.readBytes()
            assertFalse("BOM не должен писаться", bytes.size >= 3 && bytes[0] == 0xEF.toByte() &&
                bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte())
            val text = String(bytes, Charsets.UTF_8)
            assertTrue("кириллица должна быть сырой", "Сервер 1" in text)
            assertTrue("\n" in text)
            val parsed = Json.parseToJsonElement(text).jsonObject
            assertEquals(cfg, parsed)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `new config writes and logs`() {
        val dir = tempDir()
        try {
            val logs = mutableListOf<String>()
            val p = newSingBoxConfig(
                twoNodes, mode = "tun", installRoot = "r",
                path = File(dir, "config.json"),
            ) { logs.add(it) }
            assertTrue(p.isFile)
            assertTrue(logs.any { it.startsWith("  direct-exclude apps: ") })
            assertEquals("config written: 4 outbounds, mode=tun, final=proxy-group", logs.last())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `proxy mode skips direct apps log`() {
        val dir = tempDir()
        try {
            val logs = mutableListOf<String>()
            newSingBoxConfig(
                twoNodes, mode = "proxy", installRoot = "r",
                path = File(dir, "config.json"),
            ) { logs.add(it) }
            assertFalse(logs.any { it.startsWith("  direct-exclude apps: ") })
            assertEquals("config written: 4 outbounds, mode=proxy, final=proxy-group", logs.last())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `missing engine raises io exception`() {
        try {
            testSingBoxConfig(File("nonexistent.json"), File("no-such-engine-xyz.exe"))
            fail("ожидали IOException")
        } catch (e: IOException) {
            // как FileNotFoundError в Python/PS
        }
    }
}
