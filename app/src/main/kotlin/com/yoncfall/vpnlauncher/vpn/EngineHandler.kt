// CommandServerHandler: события движка -> сервис. Порт BoxService (SFA),
// упрощённый: без системного прокси и ssh-агента (см. PlatformBridge).
package com.yoncfall.vpnlauncher.vpn

import android.util.Log
import com.yoncfall.vpnlauncher.core.AppLog
import io.nekohasekai.libbox.CommandServerHandler
import io.nekohasekai.libbox.SystemProxyStatus

class EngineHandler(private val service: VpnServiceImpl) : CommandServerHandler {

    override fun connectSSHAgent(): Int = -1 // ssh-агент недоступен (как в SFA)

    override fun getSystemProxyStatus(): SystemProxyStatus =
        // Android не имеет настольного "системного прокси" (в 1.0.6 это WinINET)
        // -> available=false; движок эту ветку не активирует.
        SystemProxyStatus().apply {
            available = false
            enabled = false
        }

    override fun serviceReload() = service.engineReload()

    override fun serviceStop() = service.engineStopRequested()

    override fun setSystemProxyEnabled(isEnabled: Boolean) {
        // getSystemProxyStatus().available=false -> движок не должен звать; no-op
    }

    override fun triggerNativeCrash() {
        // отладочный триггер движка: поднимаем ошибку вместо аварии процесса
        error("android: triggerNativeCrash")
    }

    override fun writeDebugMessage(message: String?) {
        val text = message ?: return
        Log.d("sing-box", text)
        AppLog.write(text)
    }
}
