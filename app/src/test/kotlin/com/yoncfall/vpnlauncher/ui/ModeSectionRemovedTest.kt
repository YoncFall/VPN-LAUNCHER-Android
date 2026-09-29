package com.yoncfall.vpnlauncher.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Правки UI после v2.0.0 (по отзывам с устройства).
 *
 * 1) Секция РЕЖИМ удалена: радио «Весь трафик - TUN» было единственным -
 *    выбора не было, вся строка режима бесполезна. Тест держит удаление:
 *    UiState больше не хранит mode, selectMode() нет, GameRadio удалён
 *    (в core поле mode остаётся - state.json/config.json, это ядро).
 * 2) Кнопка обновления подписки: refreshSubscription() должна существовать
 *    (перечитывает URL -> обновляет серверы -> автопинг).
 *
 * Рефлексия только Java-классов (без kotlin-reflect): поля data-класса
 * и объявленные методы.
 */
class ModeSectionRemovedTest {

    private fun fieldNames(cls: Class<*>): List<String> =
        cls.declaredFields.map { it.name }

    private fun methodNames(cls: Class<*>): List<String> =
        cls.declaredMethods.map { it.name }

    @Test
    fun uiStateHasNoModeField() {
        assertFalse(
            "UiState.mode должен быть удалён",
            "mode" in fieldNames(UiState::class.java),
        )
    }

    @Test
    fun selectModeRemoved() {
        assertFalse(
            "selectMode() должна быть удалена",
            "selectMode" in methodNames(AppViewModel::class.java),
        )
    }

    @Test
    fun gameRadioRemoved() {
        // GameRadio - top-level composable в Widgets.kt (генерит WidgetsKt)
        val cls = Class.forName("com.yoncfall.vpnlauncher.ui.WidgetsKt")
        assertFalse(
            "GameRadio должна быть удалена",
            cls.declaredMethods.any { it.name == "GameRadio" },
        )
    }

    @Test
    fun refreshSubscriptionExists() {
        assertTrue(
            "refreshSubscription() должна существовать (⟳ Обновить)",
            "refreshSubscription" in methodNames(AppViewModel::class.java),
        )
    }
}
