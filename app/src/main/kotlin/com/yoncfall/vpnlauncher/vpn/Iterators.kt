// Итераторы gomobile-биндингов libbox и хелперы вывода сетевых интерфейсов.
// Порт SFA PlatformInterfaceWrapper (классы StringArray/InterfaceArray/toPrefix).
package com.yoncfall.vpnlauncher.vpn

import io.nekohasekai.libbox.NetworkInterfaceIterator
import io.nekohasekai.libbox.StringIterator
import java.net.Inet6Address
import java.net.InterfaceAddress

/** Строковый итератор поверх Iterator<String> (len() core не использует). */
class StringArray(private val iterator: Iterator<String>) : StringIterator {
    override fun hasNext(): Boolean = iterator.hasNext()
    override fun next(): String = iterator.next()
    override fun len(): Int = 0
}

/** Итератор сетевых интерфейсов для PlatformInterface.getInterfaces. */
class InterfaceArray(
    private val iterator: Iterator<io.nekohasekai.libbox.NetworkInterface>,
) : NetworkInterfaceIterator {
    override fun hasNext(): Boolean = iterator.hasNext()
    override fun next(): io.nekohasekai.libbox.NetworkInterface = iterator.next()
}

/** "ip/prefix" адреса интерфейса; v6 — через Inet6Address, чтобы убрать зону (%wlan0). */
fun InterfaceAddress.toPrefix(): String = if (address is Inet6Address) {
    "${Inet6Address.getByAddress(address.address).hostAddress}/$networkPrefixLength"
} else {
    "${address.hostAddress}/$networkPrefixLength"
}
