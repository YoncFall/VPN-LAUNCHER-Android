package com.yoncfall.vpnlauncher.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Маска ссылки подписки для UI (безопасность): поле ввода показывает схему
 * и хост, путь с токеном скрыт - случайный скриншот/запись экрана не
 * покажет ключ доступа к серверам (GameField(mask = ::maskSubscriptionUrl)).
 */
class SubscriptionMaskTest {

    @Test
    fun httpsHidesTokenPathButKeepsHost() {
        val url = "https://sub.example.ru/subscription/bb47e201-1234-5678-9abc"
        val masked = maskSubscriptionUrl(url)
        assertEquals("https://sub.example.ru/…", masked)
        assertFalse("токен не должен попадать в маску", masked.contains("bb47e201"))
        assertTrue("хост остаётся - пользователь узнаёт сервер", masked.contains("sub.example.ru"))
    }

    @Test
    fun queryTokenHidden() {
        val masked = maskSubscriptionUrl("http://host.example:8080?token=SECRET123")
        assertEquals("http://host.example:8080/…", masked)
        assertFalse(masked.contains("SECRET123"))
    }

    @Test
    fun hostWithoutPathIsMaskedAfterSlash() {
        assertEquals("https://example.com/…", maskSubscriptionUrl("https://example.com"))
    }

    @Test
    fun emptyStaysEmptyForPlaceholder() {
        assertEquals("", maskSubscriptionUrl(""))
        assertEquals("", maskSubscriptionUrl("   "))
    }

    @Test
    fun fileUrlKeepsOnlyFileName() {
        val masked = maskSubscriptionUrl("file:///storage/emulated/0/Download/sub.txt")
        assertEquals("file://…/sub.txt", masked)
    }

    @Test
    fun localPathKeepsOnlyFileName() {
        assertEquals(
            "…/sub.txt",
            maskSubscriptionUrl("/storage/emulated/0/Download/sub.txt"),
        )
        assertEquals(
            "…/sub.txt",
            maskSubscriptionUrl("C:\\Users\\me\\Downloads\\sub.txt"),
        )
    }

    @Test
    fun garbageDoesNotLeakAsIs() {
        // нераспознанная строка маскируется целиком
        assertEquals("…", maskSubscriptionUrl("секрет без схемы"))
    }
}
