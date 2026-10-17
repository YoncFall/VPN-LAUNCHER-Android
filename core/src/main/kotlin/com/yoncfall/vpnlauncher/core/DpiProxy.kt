// Локальный SOCKS5-фрагментатор TLS (2.3.0, режим «ТСПУ без сервера»).
//
// sing-box в TUN-режиме шлёт сюда соединения к заблокированным доменам
// (route-правило -> socks-аутбаунд на 127.0.0.1:DPI_PROXY_PORT). Прокси:
//   1. резолвит домен через DoH (провайдерский DNS отравляется - DoH нет);
//   2. соединяется с целью напрямую; сокет ОБЯЗАТЕЛЬНО проходит
//      VpnService.protect() - иначе туннель увёл бы его обратно в прокси
//      (петля: прокси -> tun -> route-правило -> прокси -> ...);
//   3. дробит первый TLS-запись (ClientHello) на TCP-сегменты с паузами -
//      ТСПУ не собирает SNI из неполного хэндшейка и пропускает соединение;
//   4. пайпит остаток сырым потоком в обе стороны.
//
// Это обход блокировки, а не приватность: наружу трафик идёт с реального IP.
package com.yoncfall.vpnlauncher.core

import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.HttpsURLConnection
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Разбор JSON-ответа DoH (dns.google / cloudflare): первый A-запись -> IP. */
fun parseDohIp(json: String): String? {
    val obj = try {
        Json.parseToJsonElement(json).jsonObject
    } catch (_: Throwable) {
        return null
    }
    val answers = obj["Answer"] as? JsonArray ?: return null
    for (a in answers) {
        val o = a.jsonObject
        if (o["type"]?.jsonPrimitive?.intOrNull == 1) {
            return o["data"]?.jsonPrimitive?.content
        }
    }
    return null
}

private val DOH_URLS = listOf(
    "https://dns.google/resolve?name={host}&type=A",
    "https://cloudflare-dns.com/dns-query?name={host}&type=A",
    "https://dns.quad9.net/dns-query?name={host}&type=A",
)

/** DoH-резолвер с фолбэками: google -> cloudflare -> системный DNS. */
fun dohResolve(host: String): String {
    for (template in DOH_URLS) {
        try {
            val conn = URL(template.replace("{host}", host)).openConnection() as HttpsURLConnection
            conn.connectTimeout = 4_000
            conn.readTimeout = 4_000
            conn.setRequestProperty("Accept", "application/dns-json")
            val text = conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
            parseDohIp(text)?.let { return it }
        } catch (_: Throwable) {
            // следующий провайдер
        }
    }
    return InetAddress.getByName(host).hostAddress ?: error("DNS не разрешил: $host")
}

/**
 * Схема дробления ClientHello (2.4.0, автоподбор): [splits] - сколько байт
 * писать подряд, [gaps] - пауза (мс) после каждого сегмента; заголовок
 * TLS-записи пишется вместе с первым сегментом. Порядок в enum = порядок
 * перебора (от быстрой к тяжёлой); рабочая запоминается в state.json
 * (fragStrategy). Как подбор алгоритмов winws на ПК, но по схемам
 * фрагментации - Android даёт только TUN, сырых пакетов нет.
 */
enum class FragStrategy(
    val id: String,
    val desc: String,
    val splits: List<Int>,
    val gaps: List<Long>,
) {
    FAST("fast", "быстрая", listOf(1, 3), listOf(40, 30)),
    RAMP("ramp", "нарастающая", listOf(1, 2, 4, 8), listOf(10, 20, 40, 60)),
    TINY("tiny", "мелкие куски", listOf(1, 1, 2, 2, 4, 4), List(6) { 20 }),
    // 48 порций по 1 байту: переупорядочиватель ТСПУ часто ограничен числом
    // фрагментов на поток и после лимита выбрасывает буфер - SNI не собирается
    MANY("many", "48 мелких фрагментов", List(48) { 1 }, List(48) { 8 }),
    SLOW("slow", "длинные паузы", listOf(1, 3), listOf(300, 200)),
    // пауза длиннее типичного окна переупорядочивания (~1с у части ТСПУ)
    LONG("long", "пауза 1.5с", listOf(1), listOf(1_500)),
    DEEP("deep", "тяжёлая", listOf(1, 1, 1, 5), listOf(200, 200, 200, 100));

    companion object {
        /** Неизвестный id (старый state.json) -> FAST (как в 2.3.0). */
        fun byId(id: String?): FragStrategy = values().firstOrNull { it.id == id } ?: FAST
    }
}

/**
 * Результат пробы (2.4.1): ГДЕ порвалась цепочка обхода - этого требует
 * диагностика «не нашёл рабочую схему» (в лог и в диалог подбора).
 */
enum class ProbeResult {
    OK, // TLS-хендшейк прошёл - ТСПУ пропустил
    DNS, // резолв не ответил (ни DoH, ни системный)
    TCP, // соединение не установлено (не тот IP / обрыв на синхронизации)
    TLS, // хендшейк оборван - ТСПУ прочитал SNI и зарезал
}

/**
 * TLS-проба через всю цепочку обхода (2.4.0): сокет приложения идёт в tun ->
 * sing-box (sniff SNI) -> фрагментатор -> настоящий сервер. Резолв - DoH,
 * не отравлен провайдером. Подробная версия: говорит, на какой стадии отказ.
 *
 * Сокет не protect() - он обязан идти через туннель, иначе проба ничего
 * не проверяет.
 */
fun probeTlsDetailed(
    host: String,
    resolve: (String) -> String = ::dohResolve,
    timeoutMs: Long = 5_000,
): ProbeResult {
    val ip = try {
        resolve(host)
    } catch (e: Throwable) {
        AppLog.write("проба $host: DNS не разрешился: ${e.message}")
        return ProbeResult.DNS
    }
    return try {
        Socket().use { sock ->
            try {
                sock.connect(InetSocketAddress(ip, 443), timeoutMs.toInt())
            } catch (e: Throwable) {
                AppLog.write("проба $host: TCP не прошёл ($ip): ${e.javaClass.simpleName}: ${e.message}")
                return ProbeResult.TCP
            }
            sock.soTimeout = timeoutMs.toInt()
            val ssl = (javax.net.ssl.SSLSocketFactory.getDefault() as javax.net.ssl.SSLSocketFactory)
                .createSocket(sock, host, 443, true) as javax.net.ssl.SSLSocket
            try {
                ssl.use {
                    // SNI: Android Conscrypt знает setHostname; на прочих JVM - no-op,
                    // хост уже передан в createSocket
                    runCatching {
                        it.javaClass.getMethod("setHostname", String::class.java).invoke(it, host)
                    }
                    it.startHandshake()
                }
            } catch (e: Throwable) {
                AppLog.write("проба $host: хендшейк оборван ($ip): ${e.javaClass.simpleName}: ${e.message}")
                return ProbeResult.TLS
            }
        }
        ProbeResult.OK
    } catch (e: Throwable) {
        AppLog.write("проба $host: сбой ($ip): ${e.javaClass.simpleName}: ${e.message}")
        ProbeResult.TLS
    }
}

/** Короткая булева версия пробы: true = ТСПУ пропустил хендшейк. */
fun probeTls(
    host: String,
    resolve: (String) -> String = ::dohResolve,
    timeoutMs: Long = 5_000,
): Boolean = probeTlsDetailed(host, resolve, timeoutMs) == ProbeResult.OK

/**
 * SOCKS5-сервер на 127.0.0.1 с фрагментацией ClientHello.
 *
 * @param port 0 - эфемерный порт (для тестов), смотри [localPort].
 * @param resolve домен -> IP (в проде [dohResolve]; в тестах заглушка).
 * @param protect защита сокета от маршрута VPN (VpnService.protect);
 *   false = закрыть соединение (страховка от петли).
 */
class DpiProxy(
    private val port: Int = DPI_PROXY_PORT,
    private val resolve: (String) -> String = ::dohResolve,
    private val protect: (Socket) -> Boolean = { true },
    private val logger: (String) -> Unit = AppLog::write,
) {

    private val running = AtomicBoolean(false)
    private var server: ServerSocket? = null
    private var pool: java.util.concurrent.ExecutorService? = null
    private val resolveCache = ConcurrentHashMap<String, String>()

    /**
     * Текущая схема дробления; автоподбор (2.4.0) меняет на лету между
     * пробами - @Volatile, читается на каждом соединении.
     */
    @Volatile
    var strategy: FragStrategy = FragStrategy.FAST

    /** Фактический порт (после start; для port=0 - эфемерный). */
    val localPort: Int get() = server?.localPort ?: port

    fun start() {
        if (!running.compareAndSet(false, true)) return
        val ss = ServerSocket()
        ss.bind(InetSocketAddress("127.0.0.1", port), 64)
        server = ss
        pool = Executors.newCachedThreadPool { r ->
            Thread(r, "dpi-frag").apply { isDaemon = true }
        }
        pool!!.execute {
            while (running.get()) {
                try {
                    val c = ss.accept()
                    pool!!.execute { handle(c) }
                } catch (e: Throwable) {
                    if (running.get()) logger("dpi-proxy accept: ${e.message}")
                }
            }
        }
        logger("dpi-proxy listening on 127.0.0.1:${ss.localPort}")
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        try {
            server?.close()
        } catch (_: Throwable) {
        }
        server = null
        pool?.shutdownNow()
        pool = null
        logger("dpi-proxy stopped")
    }

    // ------------------------------------------------------------ обработка

    private fun handle(client: Socket) {
        var target: Socket? = null
        try {
            client.soTimeout = 15_000 // фаза SOCKS-хендшейка
            client.tcpNoDelay = true
            val cin = client.getInputStream()
            val cout = client.getOutputStream()

            // приветствие: 0x05 NMETHODS METHODS... -> 0x05 0x00 (без auth)
            val greet = readFully(cin, 2) ?: return
            if (greet[0] != 5.toByte()) return
            val nMethods = greet[1].toInt() and 0xff
            if (nMethods > 0 && readFully(cin, nMethods) == null) return
            cout.write(byteArrayOf(0x05, 0x00))
            cout.flush()

            // запрос: 0x05 CMD 0x00 ATYP ADDR PORT
            val req = readFully(cin, 4) ?: return
            if (req[0] != 5.toByte()) return
            val cmd = req[1].toInt() and 0xff
            val atyp = req[3].toInt() and 0xff
            var host: String? = null
            var ip: String? = null
            when (atyp) {
                1 -> { // IPv4
                    val b = readFully(cin, 4) ?: return
                    ip = "${b[0].toInt() and 0xff}.${b[1].toInt() and 0xff}." +
                        "${b[2].toInt() and 0xff}.${b[3].toInt() and 0xff}"
                }

                3 -> { // домен (типовой случай: sing-box передаёт sniffed-домен)
                    val l = cin.read()
                    if (l <= 0) return
                    val b = readFully(cin, l) ?: return
                    host = String(b, Charsets.ISO_8859_1)
                }

                4 -> { // IPv6
                    val b = readFully(cin, 16) ?: return
                    ip = InetAddress.getByAddress(b).hostAddress
                }

                else -> {
                    socksReply(cout, 0x08) // address type not supported
                    return
                }
            }
            val pb = readFully(cin, 2) ?: return
            val dport = ((pb[0].toInt() and 0xff) shl 8) or (pb[1].toInt() and 0xff)
            if (cmd != 1) {
                socksReply(cout, 0x07) // command not supported (только CONNECT)
                return
            }

            val targetIp = ip ?: resolveCached(host!!)
            val t = Socket()
            target = t
            t.tcpNoDelay = true
            if (!protect(t)) {
                logger("dpi-proxy: protect failed - закрываю (страховка от петли)")
                socksReply(cout, 0x05)
                return
            }
            try {
                t.connect(InetSocketAddress(targetIp, dport), 10_000)
            } catch (_: Throwable) {
                socksReply(cout, 0x05) // connection refused / timeout
                return
            }
            socksReply(cout, 0x00)
            client.soTimeout = 0 // долгие соединения (discord-вебсокет)
            logger("dpi-proxy: ${host ?: ip}:$dport -> $targetIp (fragment)")
            fragmentAndPipe(cin, cout, t)
        } catch (e: Throwable) {
            logger("dpi-proxy conn: ${e.message}")
        } finally {
            try {
                client.close()
            } catch (_: Throwable) {
            }
            try {
                target?.close()
            } catch (_: Throwable) {
            }
        }
    }

    private fun resolveCached(host: String): String =
        resolveCache.getOrPut(host) { resolve(host) }

    /**
     * Дробит первый TLS-запись (ClientHello) по текущей [strategy] и пайпит
     * остаток.
     *
     * Заголовок записи пишется вместе с первым сегментом; далее - сегменты
     * схемы с паузами, хвост одной порцией. ТСПУ видит неполный ClientHello
     * без SNI и пропускает; сервер собирает TCP-поток и получает валидный
     * хэндшейк. Схему меняет автоподбор между пробами.
     */
    private fun fragmentAndPipe(cin: InputStream, cout: OutputStream, target: Socket) {
        val tin = target.getInputStream()
        val tout = target.getOutputStream()
        try {
            val head = readFully(cin, 5) // заголовок TLS-записи
            if (head == null || head.isEmpty()) return
            if (head.size == 5 && head[0] == 0x16.toByte()) {
                val recLen = ((head[3].toInt() and 0xff) shl 8) or (head[4].toInt() and 0xff)
                val body = if (recLen > 0) readFully(cin, recLen) else ByteArray(0)
                if (body == null) {
                    tout.write(head)
                    tout.flush()
                    return
                }
                val st = strategy
                var off = 0
                for (i in st.splits.indices) {
                    if (off >= body.size) break
                    val n = minOf(st.splits[i], body.size - off)
                    if (i == 0) tout.write(head)
                    tout.write(body, off, n)
                    tout.flush()
                    off += n
                    if (off < body.size && i < st.gaps.size && st.gaps[i] > 0) {
                        Thread.sleep(st.gaps[i])
                    }
                }
                if (off == 0) tout.write(head) // body пуст - просто заголовок
                if (off < body.size) tout.write(body, off, body.size - off)
                tout.flush()
            } else {
                // не TLS - пропускаем как есть
                tout.write(head)
                tout.flush()
            }
        } catch (_: Throwable) {
            return
        }
        val up = Thread {
            pump(cin, tout, target)
        }.apply {
            isDaemon = true
            start()
        }
        pump(tin, cout, target)
        up.join(2_000)
    }

    private fun pump(input: InputStream, output: OutputStream, target: Socket) {
        val buf = ByteArray(16384)
        try {
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                output.write(buf, 0, n)
            }
        } catch (_: Throwable) {
            // обрыв - норма
        } finally {
            try {
                target.close()
            } catch (_: Throwable) {
            }
        }
    }

    // ------------------------------------------------------------ утилиты

    private fun socksReply(out: OutputStream, status: Int) {
        out.write(byteArrayOf(0x05, status.toByte(), 0x00, 0x01, 0, 0, 0, 0, 0, 0))
        out.flush()
    }

    /** Ровно n байт или null при раннем EOF. */
    private fun readFully(input: InputStream, n: Int): ByteArray? {
        if (n <= 0) return ByteArray(0)
        val buf = ByteArray(n)
        var off = 0
        while (off < n) {
            val r = input.read(buf, off, n - off)
            if (r < 0) return if (off == 0) null else buf.copyOf(off)
            off += r
        }
        return buf
    }
}
