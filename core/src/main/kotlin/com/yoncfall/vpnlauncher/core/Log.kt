// Журнал. Аналог write_log из vpn-launcher-py (там по умолчанию пишет в файл);
// здесь сток журнала подключается приложением (AppLog.sink), в тестах — no-op.
package com.yoncfall.vpnlauncher.core

/** Сток журнала: приложение назначает свой sink (файл/логcat), тесты — пустой. */
object AppLog {
    @Volatile
    var sink: ((String) -> Unit)? = null

    fun write(message: String) {
        sink?.invoke(message)
    }
}
