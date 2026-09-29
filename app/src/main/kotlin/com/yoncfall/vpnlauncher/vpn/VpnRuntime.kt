// Статус туннеля для UI. Сервис и UI живут в одном процессе, поэтому
// состояние - обычный синглтон: VpnRuntime.state выставляет VpnServiceImpl
// (STARTING/CONNECTED/FAILED/IDLE), AppViewModel подписывается на него.
package com.yoncfall.vpnlauncher.vpn

import kotlinx.coroutines.flow.MutableStateFlow

object VpnRuntime {
    enum class State { IDLE, STARTING, CONNECTED, FAILED }

    val state = MutableStateFlow(State.IDLE)

    /** Текст статуса при FAILED (null -> 'Не удалось подключиться'). */
    @Volatile
    var statusText: String? = null

    /** Заголовок MessageBox при ошибке подключения (null -> без диалога). */
    @Volatile
    var dialogTitle: String? = null

    /** Текст MessageBox при ошибке подключения (null -> без диалога). */
    @Volatile
    var dialogText: String? = null
}
