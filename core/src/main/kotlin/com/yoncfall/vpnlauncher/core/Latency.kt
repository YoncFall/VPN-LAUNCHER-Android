// TCP-пинг нод. Дословный порт Measure-NodeLatency (core.ps1:587-618) через
// vpn-launcher-py (latency.py): TCP connect, до attempts попыток, берём лучший
// результат, ранний выход сразу, если результат < 60 мс. Миллисекунды или -1.
package com.yoncfall.vpnlauncher.core

import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

const val FAST_MS = 60 // порог раннего выхода, как в PS

/**
 * `node` — объект с ключами "server" и "server_port" (как у PS-нод).
 * Неудача первой попытки -> break (python: create_connection -> OSError);
 * недостижимый хост ждёт весь timeoutMs на каждую попытку - как в PS.
 */
fun measureNodeLatency(
    node: JsonObject,
    timeoutMs: Int = 2500,
    attempts: Int = 2,
): Int {
    val host = node["server"]!!.jsonPrimitive.content
    val port = node["server_port"]!!.jsonPrimitive.content.toInt()
    var best = -1
    var attempt = 0
    while (attempt < attempts) {
        attempt++
        val started = System.nanoTime()
        val sock = Socket()
        try {
            sock.connect(InetSocketAddress(host, port), timeoutMs)
        } catch (e: Exception) {
            closeQuietly(sock)
            break
        }
        val ms = ((System.nanoTime() - started) / 1_000_000L).toInt()
        closeQuietly(sock)
        if (best < 0 || ms < best) best = ms
        if (best < FAST_MS) break
    }
    return best
}

private fun closeQuietly(sock: Socket) = try {
    sock.close()
} catch (e: IOException) {
    // сокет уже закрыт/не открылся - результат не меняет
}
