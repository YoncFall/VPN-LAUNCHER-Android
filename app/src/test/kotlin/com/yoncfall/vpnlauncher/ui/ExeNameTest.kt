package com.yoncfall.vpnlauncher.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * EXE_RE (AppViewModel): валидация имени процесса в исключениях (VPN.ps1:256).
 *
 * Регрессия E2E: UNICODE_CHARACTER_CLASS недоступен на Android и ронял
 * AppViewModel.<clinit> (ExceptionInInitializerError) на старте; фикс -
 * \p{L}\p{N} + UNICODE_CASE. Тест фиксирует обе стороны: Unicode-имена
 * проходят, а мусор - нет.
 */
class ExeNameTest {

    private fun matches(v: String) = AppViewModel.EXE_RE.matcher(v).matches()

    @Test
    fun asciiName() {
        assertTrue(matches("game.exe"))
    }

    @Test
    fun caseInsensitiveExtension() {
        assertTrue(matches("CS2.EXE"))
    }

    @Test
    fun cyrillicNameMatches() {
        // было бы false с \w (ASCII) без UNICODE_CHARACTER_CLASS
        assertTrue(matches("Игра.exe"))
    }

    @Test
    fun digitsDashSpace() {
        assertTrue(matches("my app-2.0.exe"))
    }

    @Test
    fun notExeRejected() {
        assertFalse(matches("file.txt"))
    }

    @Test
    fun pathRejected() {
        assertFalse(matches("dir/game.exe"))
    }

    @Test
    fun junkRejected() {
        assertFalse(matches("game!.exe"))
    }
}
