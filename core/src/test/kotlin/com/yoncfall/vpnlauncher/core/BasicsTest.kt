// Базовые проверки ядра + целостность кириллицы в строковых константах.
// Второе важно: daemon Gradle работает с file.encoding=Cp1251 (нужно для
// @argfile test-worker'а) — тест ловит порчу русских строк при компиляции.
package com.yoncfall.vpnlauncher.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BasicsTest {

    @Test
    fun `b64 utf8 roundtrip with cyrillic`() {
        val s = "Сервер-1:443 — тест"
        assertEquals(s, b64Utf8Decode(b64Utf8Encode(s)))
    }

    @Test
    fun `b64 decode garbage returns empty`() {
        assertEquals("", b64Utf8Decode("не base64!!")) // неалфавитный символ
        assertEquals("", b64Utf8Decode("A")) // rem==1
        // rem==3 -> паддинг валиден, 3 нуля: как Python b64decode("AAA=")
        assertEquals("\u0000\u0000", b64Utf8Decode("AAA"))
    }

    @Test
    fun `percent decode keeps plus and decodes utf8`() {
        assertEquals("a b+c", percentDecode("a%20b+c")) // '+' не трогаем
        assertEquals("файл.txt", percentDecode("%D1%84%D0%B0%D0%B9%D0%BB.txt"))
        assertEquals("%zz%", percentDecode("%zz%")) // невалидные последовательности как есть
    }

    @Test
    fun `split uri fragment and query`() {
        val u = splitUri("vless://uuid@host:443?type=ws#My%20Node")
        assertEquals("vless", u["Scheme"])
        assertEquals("uuid@host:443", u["Body"])
        assertEquals("type=ws", u["Query"])
        assertEquals("My Node", u["Fragment"])
    }

    @Test
    fun `split host port variants`() {
        assertEquals("h.example" to 8443, splitHostPort("h.example:8443"))
        assertEquals("::1" to 443, splitHostPort("[::1]:443")) // скобки снимаются, как в Python
        assertEquals("plain" to 443, splitHostPort("plain"))
    }

    @Test
    fun `russian messages survive compilation`() {
        // подпись ошибок должна дойти до рантайма без порчи encoding'а
        val e1 = runCatching { parseNodeList("") { } }.exceptionOrNull()
        assertTrue(e1 is SubscriptionError)
        assertEquals("Пустой текст подписки", e1!!.message)

        val e2 = runCatching { fetchNodes("") { } }.exceptionOrNull()
        assertEquals("Пустая ссылка на подписку", e2!!.message)
    }

    @Test
    fun `parse logs counts line as python port`() {
        val lines = mutableListOf<String>()
        runCatching { parseNodeList("vless://a@b:443#t\r\n\r\nплохая строка") { lines.add(it) } }
        // одна пригодная строка из трёх (пустые и без :// фильтруются)
        assertEquals(listOf("  parsed 1 of 1 lines"), lines)
    }
}
