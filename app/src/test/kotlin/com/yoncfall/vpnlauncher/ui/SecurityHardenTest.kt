package com.yoncfall.vpnlauncher.ui

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Укрепление безопасности (по запросу владельца проекта):
 *
 * 1) Манифест ссылается на network_security_config с запретом cleartext -
 *    minSdk 24: на Android 7/7.1 HTTP разрешён по умолчанию, http://-ссылка
 *    на подписку уходила бы открытым текстом (токен = доступ к серверам).
 * 2) allowBackup=false - токен/ключи не вытащить через ADB-backup.
 * 3) kill switch - флаг VpnRuntime.killSwitch существует: авария движка при
 *    живом tun не гасит сервис (иначе трафик уходит напрямую без VPN).
 *
 * Файлы читаются относительно каталога модуля app (cwd юнит-теста).
 */
class SecurityHardenTest {

    private val manifest: String =
        File("src/main/AndroidManifest.xml").readText(Charsets.UTF_8)

    private val networkConfig: String =
        File("src/main/res/xml/network_security_config.xml").readText(Charsets.UTF_8)

    @Test
    fun manifestDisablesBackup() {
        assertTrue(
            "allowBackup=false обязан остаться (иначе backup вытащит токен)",
            manifest.contains("android:allowBackup=\"false\""),
        )
    }

    @Test
    fun manifestWiresNetworkSecurityConfig() {
        assertTrue(
            "application должен ссылаться на networkSecurityConfig",
            manifest.contains("android:networkSecurityConfig=\"@xml/network_security_config\""),
        )
    }

    @Test
    fun cleartextForbiddenOnAllApiLevels() {
        assertTrue(
            "cleartextTrafficPermitted=false запрещает http:// на всех версиях Android",
            networkConfig.contains("cleartextTrafficPermitted=\"false\""),
        )
        assertTrue(
            "конфиг должен быть network-security-config",
            networkConfig.contains("<network-security-config>"),
        )
    }

    @Test
    fun killSwitchFlagExists() {
        val cls = Class.forName("com.yoncfall.vpnlauncher.vpn.VpnRuntime")
        assertTrue(
            "VpnRuntime.killSwitch обязателен (kill switch: tun остаётся при аварии движка)",
            cls.declaredFields.any { it.name == "killSwitch" },
        )
    }
}
