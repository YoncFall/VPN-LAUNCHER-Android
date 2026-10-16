// Тесты DpiProxy (2.3.0, «ТСПУ без сервера»): SOCKS5-хендшейк, фрагментация
// ClientHello (целевой сервер получает данные несколькими порциями, итог -
// байт-в-байт как отправлен клиентом), DoH-разбор. Интеграционные: реальные
// сокеты на 127.0.0.1, без сети.
package com.yoncfall.vpnlauncher.core

import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DpiProxyTest {

    /** TLS ClientHello-ish запись: заголовок 0x16 + длина + «хэндшейк». */
    private fun tlsRecord(bodyLen: Int): ByteArray {
        val rec = ByteArray(5 + bodyLen)
        rec[0] = 0x16
        rec[1] = 0x03
        rec[2] = 0x01
        rec[3] = (bodyLen shr 8).toByte()
        rec[4] = (bodyLen and 0xff).toByte()
        for (i in 0 until bodyLen) rec[5 + i] = (i and 0xff).toByte()
        return rec
    }

    private fun readFully(inp: InputStream, n: Int): ByteArray {
        val buf = ByteArray(n)
        var off = 0
        while (off < n) {
            val r = inp.read(buf, off, n - off)
            check(r >= 0) { "EOF на $off из $n" }
            off += r
        }
        return buf
    }

    /** Целевой сервер: принимает, читает до обрыва, фиксирует размеры порций. */
    private class Target {
        val server = ServerSocket(0, 8, InetAddress.getLoopbackAddress())
        val chunks = ArrayList<Int>()
        val received = ArrayList<Byte>()

        @Volatile
        var done = false

        val thread = Thread {
            try {
                server.accept().use { s ->
                    val inp = s.getInputStream()
                    val buf = ByteArray(8192)
                    while (true) {
                        val n = inp.read(buf)
                        if (n < 0) break
                        synchronized(chunks) { chunks.add(n) }
                        for (i in 0 until n) received.add(buf[i])
                    }
                }
            } catch (_: Throwable) {
            } finally {
                done = true
            }
        }.apply {
            isDaemon = true
            start()
        }

        val port: Int get() = server.localPort

        fun stop() {
            runCatching { server.close() }
        }
    }

    @Test
    fun `socks5 client hello gets fragmented and piped intact`() {
        val target = Target()
        val proxy = DpiProxy(
            port = 0,
            resolve = { "127.0.0.1" },
            protect = { true },
            logger = {},
            gap1Ms = 30,
            gap2Ms = 20,
        )
        proxy.start()
        try {
            val record = tlsRecord(400)
            Socket("127.0.0.1", proxy.localPort).use { c ->
                c.tcpNoDelay = true
                val out = c.getOutputStream()
                val inp = c.getInputStream()
                // приветствие SOCKS5
                out.write(byteArrayOf(0x05, 0x01, 0x00))
                out.flush()
                assertEquals(0x05, inp.read())
                assertEquals(0x00, inp.read())
                // CONNECT к домену-заглушке
                val host = "test.local".toByteArray(Charsets.ISO_8859_1)
                val req = ByteArray(5 + host.size + 2)
                req[0] = 0x05
                req[1] = 0x01
                req[3] = 0x03
                req[4] = host.size.toByte()
                host.copyInto(req, 5)
                req[5 + host.size] = (target.port shr 8).toByte()
                req[6 + host.size] = (target.port and 0xff).toByte()
                out.write(req)
                out.flush()
                val rep = readFully(inp, 10)
                assertEquals(0x05, rep[0].toInt())
                assertEquals(0x00, rep[1].toInt()) // success
                // TLS-запись + сырой остаток
                out.write(record)
                out.write("GET / HTTP/1.1\r\n\r\n".toByteArray())
                out.flush()
                c.shutdownOutput()
            }
            val deadline = System.currentTimeMillis() + 5_000
            while (!target.done && System.currentTimeMillis() < deadline) Thread.sleep(20)
            val got = synchronized(target.received) { target.received.toByteArray() }
            // целостность: сервер получил байт-в-байт (TCP пересобрал поток)
            assertEquals(
                (record.toList() + "GET / HTTP/1.1\r\n\r\n".toByteArray().toList()),
                got.toList(),
            )
            // фрагментация: ClientHello пришёл несколькими TCP-сегментами,
            // а не одним (иначе ТСПУ собрал бы SNI). Точные размеры чанков
            // ненадёжны (TCP склеивает записи до flush) - важно свойство:
            // первый сегмент НЕ содержит весь хэндшейк
            val chunks = synchronized(target.chunks) { target.chunks.toList() }
            check(chunks.size >= 2) { "ожидали >=2 порций, получили $chunks" }
            check(chunks.first() < record.size) {
                "первый сегмент $chunks целиком содержит хэндшейк (без фрагментации)"
            }
        } finally {
            proxy.stop()
            target.stop()
        }
    }

    @Test
    fun `non tls traffic passes through unfragmented`() {
        val target = Target()
        val proxy = DpiProxy(port = 0, resolve = { "127.0.0.1" }, protect = { true }, logger = {})
        proxy.start()
        try {
            val payload = "plain http data".toByteArray()
            Socket("127.0.0.1", proxy.localPort).use { c ->
                val out = c.getOutputStream()
                val inp = c.getInputStream()
                out.write(byteArrayOf(0x05, 0x01, 0x00))
                out.flush()
                readFully(inp, 2)
                val host = "test.local".toByteArray(Charsets.ISO_8859_1)
                val req = ByteArray(5 + host.size + 2)
                req[0] = 0x05
                req[1] = 0x01
                req[3] = 0x03
                req[4] = host.size.toByte()
                host.copyInto(req, 5)
                req[5 + host.size] = (target.port shr 8).toByte()
                req[6 + host.size] = (target.port and 0xff).toByte()
                out.write(req)
                out.flush()
                readFully(inp, 10)
                out.write(payload)
                out.flush()
                c.shutdownOutput()
            }
            val deadline = System.currentTimeMillis() + 5_000
            while (!target.done && System.currentTimeMillis() < deadline) Thread.sleep(20)
            val got = synchronized(target.received) { target.received.toByteArray() }
            assertEquals(payload.toList(), got.toList())
        } finally {
            proxy.stop()
            target.stop()
        }
    }

    // ------------------------------------------------------------ DoH-разбор

    @Test
    fun `parse doh picks first a record`() {
        val json = """{"Status":0,"Answer":[{"name":"d.test.","type":5,"data":"c.test."},
            {"name":"c.test.","type":1,"TTL":300,"data":"93.184.216.34"}]}"""
        assertEquals("93.184.216.34", parseDohIp(json))
    }

    @Test
    fun `parse doh garbage returns null`() {
        assertNull(parseDohIp("<html>blocked</html>"))
        assertNull(parseDohIp("""{"Status":0}"""))
    }

    // ----------------------------------------- адресная форма (заглушка резолва)

    @Test
    fun `ipv4 target skips resolver`() {
        val target = Target()
        val proxy = DpiProxy(
            port = 0,
            resolve = { error("резолв не должен вызываться для IP") },
            protect = { true },
            logger = {},
        )
        proxy.start()
        try {
            Socket("127.0.0.1", proxy.localPort).use { c ->
                val out = c.getOutputStream()
                val inp = c.getInputStream()
                out.write(byteArrayOf(0x05, 0x01, 0x00))
                out.flush()
                readFully(inp, 2)
                val lo = InetAddress.getLoopbackAddress().address
                val req = byteArrayOf(
                    0x05, 0x01, 0x00, 0x01,
                    lo[0], lo[1], lo[2], lo[3],
                    (target.port shr 8).toByte(), (target.port and 0xff).toByte(),
                )
                out.write(req)
                out.flush()
                val rep = readFully(inp, 10)
                assertEquals(0x00, rep[1].toInt())
            }
        } finally {
            proxy.stop()
            target.stop()
        }
    }

    @Test
    fun `unknown atyp gets address type error`() {
        val proxy = DpiProxy(port = 0, resolve = { "127.0.0.1" }, protect = { true }, logger = {})
        proxy.start()
        try {
            Socket("127.0.0.1", proxy.localPort).use { c ->
                val out = c.getOutputStream()
                val inp = c.getInputStream()
                out.write(byteArrayOf(0x05, 0x01, 0x00))
                out.flush()
                readFully(inp, 2)
                out.write(byteArrayOf(0x05, 0x01, 0x00, 0x09))
                out.flush()
                val rep = readFully(inp, 10)
                assertEquals(0x05, rep[0].toInt())
                assertEquals(0x08, rep[1].toInt()) // address type not supported
            }
        } finally {
            proxy.stop()
        }
    }
}
