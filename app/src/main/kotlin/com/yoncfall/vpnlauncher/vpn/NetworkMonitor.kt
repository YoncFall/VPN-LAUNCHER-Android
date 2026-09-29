// Монитор дефолтной сети для PlatformInterface.start/closeDefaultInterfaceMonitor.
// Порт SFA DefaultNetworkListener + DefaultNetworkMonitor (упрощённо, без actor):
//   - callback-и идут на выдельном HandlerThread (внутри — ретраи с sleep,
//     на главном потоке так нельзя);
//   - начиная с API 28 — requestNetwork с запросом INTERNET+NOT_RESTRICTED,
//     а не registerDefaultNetworkCallback: по AOSP (с P) "дефолтной" станет
//     сама VPN-интерфейс, и движок свяжется в петлю (см. комментарий SFA);
//   - первый публиш при подписке: монитор стартует ДО подъёма TUN, поэтому
//     activeNetwork ещё не VPN;
//   - updateNetworkPath не публикуется (в SFA монитор публикует только
//     updateDefaultInterface; путь сети — статус-строка, у нас не используется).
// Нужна CHANGE_NETWORK_STATE (заявлена в манифесте, normal-пермишн).
package com.yoncfall.vpnlauncher.vpn

import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import io.nekohasekai.libbox.InterfaceUpdateListener
import java.net.NetworkInterface

object NetworkMonitor {
    private const val TAG = "NetworkMonitor"

    @Volatile
    private var listener: InterfaceUpdateListener? = null
    private var cm: ConnectivityManager? = null
    private var handlerThread: HandlerThread? = null
    private var registered = false

    @Volatile
    private var lastNetwork: Network? = null

    private val request = NetworkRequest.Builder()
        .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)
        .build()

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            lastNetwork = network
            push(network)
        }

        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
            // обновление могло изменить link properties — публикуем заново (как SFA)
            lastNetwork = network
            push(network)
        }

        override fun onLost(network: Network) {
            if (lastNetwork === network) {
                lastNetwork = null
                push(null)
            }
        }
    }

    /** Подписка/отписка движка (listener = null — отписка). */
    fun setListener(newListener: InterfaceUpdateListener?, connectivity: ConnectivityManager) {
        cm = connectivity
        listener = newListener
        if (newListener != null) {
            if (!registered) {
                register(connectivity)
                registered = true
            }
            // немедленный публиш текущей сети
            push(connectivity.activeNetwork)
        } else if (registered) {
            runCatching { connectivity.unregisterNetworkCallback(callback) }
            registered = false
            lastNetwork = null
        }
    }

    private fun register(connectivity: ConnectivityManager) {
        val handler = synchronized(this) {
            val thread = handlerThread
                ?: HandlerThread("network-callback").also { it.start(); handlerThread = it }
            Handler(thread.looper)
        }
        when {
            Build.VERSION.SDK_INT >= 31 ->
                connectivity.registerBestMatchingNetworkCallback(request, callback, handler)
            Build.VERSION.SDK_INT >= 28 ->
                connectivity.requestNetwork(request, callback, handler)
            Build.VERSION.SDK_INT >= 26 ->
                connectivity.registerDefaultNetworkCallback(callback, handler)
            else ->
                connectivity.registerDefaultNetworkCallback(callback)
        }
    }

    private fun push(network: Network?) {
        val l = listener ?: return
        if (network == null) {
            l.updateDefaultInterface("", -1, false, false)
            return
        }
        val connectivity = cm ?: return
        // link properties могут быть не готовы сразу — ретраим, как в SFA
        for (attempt in 0 until 10) {
            val name = connectivity.getLinkProperties(network)?.interfaceName
            if (name.isNullOrEmpty()) {
                Thread.sleep(100)
                continue
            }
            val index = try {
                NetworkInterface.getByName(name)?.index ?: -1
            } catch (e: Exception) {
                -1
            }
            if (index == -1) {
                Thread.sleep(100)
                continue
            }
            try {
                l.updateDefaultInterface(name, index, false, false)
            } catch (e: Exception) {
                Log.e(TAG, "updateDefaultInterface: ${e.message}")
            }
            return
        }
    }
}
