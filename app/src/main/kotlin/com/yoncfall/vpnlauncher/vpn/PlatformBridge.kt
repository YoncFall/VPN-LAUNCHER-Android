// Мост libbox.PlatformInterface к Android-сервису. Порт SFA
// bg/PlatformInterfaceWrapper.kt под API libbox 1.14.1 (27 методов; в dev-SFA
// есть ещё usePlatformAutoRedirect/createAutoRedirect — их в 1.14.1 нет).
//
// Отклонения от SFA (документированные):
//   - usePlatformShell=false: нет root/Shizuku — shell/sftp/ssh-ветки всегда
//     error(...); движок их не зовёт, пока флаг false;
//   - usePlatformBridge=false: нет root — createBridge не вызывается;
//   - readWIFIState=null: правила wifi_name не используются (запрос локации
//     для SSID не нужен);
//   - localDNSTransport=null: DNS целиком в конфиге (golden-паритет);
//   - start/closeNeighborMonitor — no-op: подписки на neighbour-таблицу нет;
//   - inet4/6RouteExcludeAddress игнорируются: Android не умеет
//     addRoute-except — приватные сети входят в TUN, их режет первое правило
//     LOCAL_CIDRS конфига и пускает напрямую через protected-сокет;
//   - DNS-серверы в Builder не добавляются: системный DNS уходит в TUN
//     (route 0.0.0.0/0) и перехватывается правилом hijack из конфига;
//   - HTTP-прокси TunOptions не пробрасывается в систему (в конфиге 1.0.6
//     его нет).
package com.yoncfall.vpnlauncher.vpn

import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Process
import android.system.OsConstants
import androidx.annotation.RequiresApi
import io.nekohasekai.libbox.BridgeOptions
import io.nekohasekai.libbox.BridgeSession
import io.nekohasekai.libbox.ConnectionOwner
import io.nekohasekai.libbox.InterfaceUpdateListener
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.LocalDNSTransport
import io.nekohasekai.libbox.NeighborUpdateListener
import io.nekohasekai.libbox.NetworkInterfaceIterator
import io.nekohasekai.libbox.PlatformInterface
import io.nekohasekai.libbox.PlatformUser
import io.nekohasekai.libbox.RoutePrefixIterator
import io.nekohasekai.libbox.ShellSession
import io.nekohasekai.libbox.StringIterator
import io.nekohasekai.libbox.TunOptions
import io.nekohasekai.libbox.WIFIState
import java.net.InetSocketAddress

class PlatformBridge(private val service: VpnServiceImpl) : PlatformInterface {

    private val connectivity: ConnectivityManager
        get() = service.connectivity

    // ------------------------------------------------------------------ туннель

    override fun usePlatformAutoDetectInterfaceControl(): Boolean = true

    override fun autoDetectInterfaceControl(fd: Int) {
        if (!service.protect(fd)) {
            error("android: protect($fd) failed")
        }
    }

    override fun openTun(options: TunOptions): Int {
        if (android.net.VpnService.prepare(service) != null) {
            error("android: missing vpn permission")
        }
        val builder = service.newTunBuilder()
            .setSession("VPN LAUNCHER")
            .setMtu(options.mtu)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            builder.setMetered(false)
        }

        // адреса TUN (из конфига: interface_address)
        var hasAddress = false
        forEachPrefix(options.inet4Address) { addr, prefix ->
            builder.addAddress(addr, prefix)
            hasAddress = true
        }
        forEachPrefix(options.inet6Address) { addr, prefix ->
            builder.addAddress(addr, prefix)
            hasAddress = true
        }
        if (!hasAddress) {
            error("android: tun: нет адресов в TunOptions")
        }

        // маршруты (auto-route); exclude-списки игнорируются — см. докстринг
        var hasRoute = false
        forEachPrefix(options.inet4RouteAddress) { addr, prefix ->
            builder.addRoute(addr, prefix)
            hasRoute = true
        }
        forEachPrefix(options.inet6RouteAddress) { addr, prefix ->
            builder.addRoute(addr, prefix)
            hasRoute = true
        }
        if (!hasRoute && options.autoRoute) {
            builder.addRoute("0.0.0.0", 0)
            builder.addRoute("::", 0)
        }

        // per-app: include приоритетнее exclude (как в SFA)
        val include = collectStrings(options.includePackage)
        if (include.isNotEmpty()) {
            include.forEach { pkg ->
                runCatching { builder.addAllowedApplication(pkg) }
            }
        } else {
            collectStrings(options.excludePackage).forEach { pkg ->
                runCatching { builder.addDisallowedApplication(pkg) }
            }
        }

        val pfd = builder.establish() ?: error("android: establish() failed (разрешение VPN отозвано?)")
        // SFA: без detachFd - pfd хранится в сервисе и закрывается при остановке
        // (E2E: с detachFd tun переживал closeService/close, т.к. Go этот fd не
        // закрывает, и система держала сервис/интерфейс вечно)
        service.tunDescriptor?.let { old -> runCatching { old.close() } }
        service.tunDescriptor = pfd
        return pfd.fd
    }

    override fun registerMyInterface(name: String?) {
        // имя интерфейса TUN движок узнаёт из конфига (interface_name) — no-op
    }

    override fun underNetworkExtension(): Boolean = false
    override fun includeAllNetworks(): Boolean = false

    // -------------------------------------------------------------------- сеть

    override fun startDefaultInterfaceMonitor(listener: InterfaceUpdateListener?) {
        NetworkMonitor.setListener(listener, connectivity)
    }

    override fun closeDefaultInterfaceMonitor(listener: InterfaceUpdateListener?) {
        NetworkMonitor.setListener(null, connectivity)
    }

    override fun getInterfaces(): NetworkInterfaceIterator {
        val interfaces = mutableListOf<io.nekohasekai.libbox.NetworkInterface>()
        val javaInterfaces = mutableListOf<java.net.NetworkInterface>()
        val enumeration = java.net.NetworkInterface.getNetworkInterfaces()
        while (enumeration != null && enumeration.hasMoreElements()) {
            javaInterfaces.add(enumeration.nextElement())
        }
        for (network in connectivity.allNetworks) {
            val linkProperties = connectivity.getLinkProperties(network) ?: continue
            val capabilities = connectivity.getNetworkCapabilities(network) ?: continue
            val name = linkProperties.interfaceName ?: continue
            val javaInterface = javaInterfaces.find { it.name == name } ?: continue

            val boxInterface = io.nekohasekai.libbox.NetworkInterface()
            boxInterface.name = name
            boxInterface.dnsServer = StringArray(
                linkProperties.dnsServers.mapNotNull { it.hostAddress }.iterator(),
            )
            boxInterface.gateway = StringArray(
                linkProperties.routes
                    .filter { it.destination.prefixLength == 0 }
                    .mapNotNull { it.gateway }
                    .filterNot { it.isAnyLocalAddress }
                    .mapNotNull { it.hostAddress }
                    .iterator(),
            )
            boxInterface.type = when {
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> Libbox.InterfaceTypeWIFI
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> Libbox.InterfaceTypeCellular
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> Libbox.InterfaceTypeEthernet
                else -> Libbox.InterfaceTypeOther
            }
            boxInterface.index = javaInterface.index
            runCatching { boxInterface.mtu = javaInterface.mtu }
            boxInterface.addresses = StringArray(
                javaInterface.interfaceAddresses.map { it.toPrefix() }.iterator(),
            )
            var flags = 0
            if (capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
                flags = OsConstants.IFF_UP or OsConstants.IFF_RUNNING
            }
            if (javaInterface.isLoopback) flags = flags or OsConstants.IFF_LOOPBACK
            if (javaInterface.isPointToPoint) flags = flags or OsConstants.IFF_POINTOPOINT
            if (javaInterface.supportsMulticast()) flags = flags or OsConstants.IFF_MULTICAST
            boxInterface.flags = flags
            boxInterface.metered =
                !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
            interfaces.add(boxInterface)
        }
        return InterfaceArray(interfaces.iterator())
    }

    override fun clearDNSCache() {
        // на Android нет пользовательского DNS-кеша — no-op (как в SFA)
    }

    override fun readWIFIState(): WIFIState? = null

    override fun tailscaleHostname(): String = "${Build.MANUFACTURER} ${Build.MODEL}"

    @RequiresApi(Build.VERSION_CODES.Q)
    override fun findConnectionOwner(
        ipProtocol: Int,
        sourceAddress: String,
        sourcePort: Int,
        destinationAddress: String,
        destinationPort: Int,
    ): ConnectionOwner {
        // вызывается движком только при useProcFS=false (API 29+)
        val uid = connectivity.getConnectionOwnerUid(
            ipProtocol,
            InetSocketAddress(sourceAddress, sourcePort),
            InetSocketAddress(destinationAddress, destinationPort),
        )
        if (uid == Process.INVALID_UID) {
            error("android: connection owner not found")
        }
        val packages = service.packageManager.getPackagesForUid(uid) ?: emptyArray()
        return ConnectionOwner().apply {
            userId = uid
            userName = packages.firstOrNull() ?: ""
            setAndroidPackageNames(StringArray(packages.asList().iterator()))
        }
    }

    // -------------------------------------------------------------- уведомления

    override fun sendNotification(notification: io.nekohasekai.libbox.Notification) {
        service.postEngineNotification(notification)
    }

    override fun cancelNotification(identifier: String, typeID: Int) {
        service.cancelEngineNotification(identifier, typeID)
    }

    // ------------------------------------- оболочка/мост/tailscale: недоступны

    override fun usePlatformShell(): Boolean = false

    override fun checkPlatformShell() {
        error("android: shell-оболочка не поддерживается")
    }

    override fun openShellSession(
        user: PlatformUser?,
        command: String?,
        environ: StringIterator?,
        term: String?,
        rows: Int,
        cols: Int,
    ): ShellSession = error("android: shell-оболочка не поддерживается")

    override fun readSystemSSHHostKey(): String =
        error("android: shell-оболочка не поддерживается")

    override fun lookupSFTPServer(): String =
        error("android: shell-оболочка не поддерживается")

    override fun lookupUser(username: String?): PlatformUser =
        error("android: shell-оболочка не поддерживается")

    override fun usePlatformBridge(): Boolean = false

    override fun createBridge(options: BridgeOptions?): BridgeSession =
        error("android: платформенный мост не поддерживается (нет root)")

    override fun useProcFS(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q

    override fun localDNSTransport(): LocalDNSTransport? = null

    override fun startNeighborMonitor(listener: NeighborUpdateListener?) {
        // подписка на neighbour-таблицу не используется — no-op (документировано)
    }

    override fun closeNeighborMonitor(listener: NeighborUpdateListener?) {
        // см. startNeighborMonitor
    }

    // ------------------------------------------------------------------- хелперы

    private inline fun forEachPrefix(
        iterator: RoutePrefixIterator?,
        block: (address: String, prefix: Int) -> Unit,
    ) {
        if (iterator == null) return
        var guard = 0
        while (iterator.hasNext() && guard++ < 4096) {
            val prefix = iterator.next() ?: break
            val address = prefix.address() ?: continue
            block(address, prefix.prefix())
        }
    }

    private fun collectStrings(iterator: StringIterator?): List<String> {
        if (iterator == null) return emptyList()
        val result = mutableListOf<String>()
        var guard = 0
        while (iterator.hasNext() && guard++ < 4096) {
            result.add(iterator.next() ?: break)
        }
        return result
    }
}
