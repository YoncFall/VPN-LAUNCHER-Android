// Тесты обновления (2.1.0): сравнение версий и разбор apps.json.
package com.yoncfall.vpnlauncher.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateTest {

    @Test
    fun parseVersionIgnoresNoise() {
        assertEquals(listOf(2, 0, 5), parseVersion("2.0.5"))
        assertEquals(listOf(2, 0, 5), parseVersion("v2.0.5-beta"))
        assertEquals(emptyList<Int>(), parseVersion(""))
    }

    @Test
    fun isNewerComparesNumbersNotStrings() {
        assertTrue(isNewer("2.0.10", "2.0.9")) // лексикографически 2.0.10 < 2.0.9
        assertTrue(isNewer("2.1.0", "2.0.4"))
        assertFalse(isNewer("2.0.4", "2.0.4"))
        assertFalse(isNewer("2.0.3", "2.0.4"))
        assertTrue(isNewer("2.1", "2.0.4")) // короткая версия дополняется нулями
    }

    private val appsJson = """
        {"categories": [{"id": "vpn", "apps": [
          {"slug": "vpn-launcher", "version": "2.3.3", "download": "https://x/Setup.exe"},
          {"slug": "vpn-launcher-android", "version": "2.0.5",
           "download": "https://x/VPN-LAUNCHER-universal.apk",
           "downloadZip": "https://x/VPN-LAUNCHER-arm64-v8a.apk",
           "releasePage": "https://x/releases"}
        ]}]}
    """.trimIndent()

    @Test
    fun parseLatestReturnsInfoForNewerVersion() {
        val info = parseLatest(appsJson, SLUG, "2.0.4")
        assertEquals("2.0.5", info?.version)
        assertEquals("https://x/VPN-LAUNCHER-universal.apk", info?.apkUrl)
        assertEquals("https://x/VPN-LAUNCHER-arm64-v8a.apk", info?.apkUrlAbi)
        assertEquals("https://x/releases", info?.pageUrl)
    }

    @Test
    fun parseLatestQuietWhenNotNewerOrBroken() {
        assertNull(parseLatest(appsJson, SLUG, "2.0.5")) // версия не новее
        assertNull(parseLatest(appsJson, SLUG, "3.0.0")) // установка назад - не предлагаем
        assertNull(parseLatest("не json", SLUG, "1.0"))
        assertNull(parseLatest("{}", SLUG, "1.0"))
        assertNull(parseLatest(appsJson, "нет-такого", "1.0"))
    }
}
