package com.yoncfall.vpnlauncher.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PACKAGE_RE (AppViewModel): валидация пакета Android-приложения в исключениях.
 *
 * Android-девиация вместо EXE_RE (VPN.ps1:256, .exe-имена процессов): здесь
 * исключение = пакет установленного приложения (com.example.app), эффект =
 * VpnService.Builder.addDisallowedApplication. Тест фиксирует: пакеты проходят,
 * мусор и старые .exe-записи - нет.
 */
class PackageNameTest {

    private fun matches(v: String) = AppViewModel.PACKAGE_RE.matcher(v).matches()

    @Test
    fun typicalPackage() {
        assertTrue(matches("com.whatsapp"))
    }

    @Test
    fun multiSegmentPackage() {
        assertTrue(matches("org.telegram.messenger"))
    }

    @Test
    fun digitsAndUnderscore() {
        assertTrue(matches("com.example.app2_beta"))
    }

    @Test
    fun singleWordRejected() {
        assertFalse(matches("whatsapp"))
    }

    @Test
    fun exeNameRejected() {
        assertFalse(matches("steam.exe"))
    }

    @Test
    fun spacesRejected() {
        assertFalse(matches("my app"))
    }

    @Test
    fun emptyRejected() {
        assertFalse(matches(""))
    }

    @Test
    fun trailingDotRejected() {
        assertFalse(matches("com.example."))
    }
}
