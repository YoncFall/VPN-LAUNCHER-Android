// Тесты TCP-пинга. Зеркало vpn-launcher-py/tests/test_latency.py.
package com.yoncfall.vpnlauncher.core

import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import kotlin.concurrent.thread
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LatencyTest {

    private fun node(port: Int) = buildJsonObject {
        put("server", "127.0.0.1")
        put("server_port", port)
    }

    @Test
    fun `latency to open port is small`() {
        val srv = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        val port = srv.localPort
        thread(isDaemon = true) {
            try {
                while (true) {
                    srv.accept().close()
                }
            } catch (e: IOException) {
                // сокет закрыт в finally
            }
        }
        try {
            val ms = measureNodeLatency(node(port))
            // localhost: срабатывает ранний выход PS-алгоритма (< 60 мс)
            assertTrue("ms=$ms", ms in 0 until FAST_MS)
        } finally {
            srv.close()
        }
    }

    @Test
    fun `latency refused returns minus one`() {
        // зарезервированный порт: снимаем bind (его может перехватить другой
        // слушатель) и ожидаем отказ в соединении
        val s = ServerSocket(0, 0, InetAddress.getByName("127.0.0.1"))
        val port = s.localPort
        s.close()
        assertEquals(-1, measureNodeLatency(node(port), timeoutMs = 500))
    }
}
