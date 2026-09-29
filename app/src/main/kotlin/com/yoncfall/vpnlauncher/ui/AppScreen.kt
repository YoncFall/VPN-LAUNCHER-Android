// Главный экран. Раскладка - порт ui/window.py (карточка 14,54,592x664 по
// координатам VPN.ps1:84-222): секции ПОДПИСКА/СЕРВЕРЫ/РЕЖИМ/ИСКЛЮЧЕНИЯ,
// кнопки подключения, LED+статус. Тексты и цвета дословно из window.py.
// Логика кнопок (загрузка подписки, пинг, туннель) - этапы 6-7.
//
// Отклонения: фиксированное окно 620x726 -> поток в колонке (телефоны уже),
// поэтому внутри карточки вертикальный скролл; высоты контролов под палец.
package com.yoncfall.vpnlauncher.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

@Composable
fun AppScreen() {
    var subUrl by rememberSaveable { mutableStateOf("") }
    var procName by rememberSaveable { mutableStateOf("") }
    var mode by rememberSaveable { mutableStateOf("tun") } // 'tun' | 'proxy'
    val scroll = rememberScrollState()

    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Theme.Bg2, Theme.Bg))),
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding(),
        ) {
            TitleBar()
            GameCard(
                Modifier
                    .padding(horizontal = 14.dp)
                    .weight(1f)
                    .padding(bottom = 14.dp),
            ) {
                Column(Modifier.fillMaxSize().verticalScroll(scroll)) {
                    // --- ПОДПИСКА (window.py:100-116) ---
                    CapsLabel("ПОДПИСКА")
                    Spacer(Modifier.height(2.dp))
                    GameField(
                        subUrl, { subUrl = it },
                        "вставь ссылку на подписку (https://...)",
                        Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp)) {
                        GameButton("Загрузить подписку", ButtonKind.GHOST, Modifier.weight(208f))
                        GameButton("Проверить пинг", ButtonKind.GHOST, Modifier.weight(150f))
                        GameButton("Открыть лог", ButtonKind.GHOST, Modifier.weight(186f))
                    }
                    Spacer(Modifier.height(6.dp))
                    DividerView()
                    Spacer(Modifier.height(10.dp))

                    // --- СЕРВЕРЫ (window.py:118-133) ---
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        CapsLabel("СЕРВЕРЫ", Modifier.weight(1f))
                        BasicText(
                            "выбери сервер · пусто - авто-тест всех",
                            style = Fonts.Sub.copy(color = Theme.TextDim),
                            maxLines = 1,
                        )
                    }
                    Spacer(Modifier.height(2.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        BasicText(
                            "ПРОТОКОЛ",
                            style = Fonts.Caps.copy(color = Theme.TextDim, textAlign = TextAlign.End),
                            maxLines = 1,
                            modifier = Modifier.width(57.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        BasicText(
                            "СЕРВЕР",
                            style = Fonts.Caps.copy(color = Theme.TextDim),
                            maxLines = 1,
                            modifier = Modifier.weight(1f),
                        )
                        BasicText(
                            "ПИНГ",
                            style = Fonts.Caps.copy(color = Theme.TextDim, textAlign = TextAlign.End),
                            maxLines = 1,
                            modifier = Modifier.width(81.dp),
                        )
                        Spacer(Modifier.width(13.dp))
                    }
                    Spacer(Modifier.height(2.dp))
                    // пустая рамка как на десктопе; список нод - этап 7
                    GameFrame(Modifier.fillMaxWidth().height(178.dp))
                    Spacer(Modifier.height(10.dp))
                    DividerView()
                    Spacer(Modifier.height(10.dp))

                    // --- РЕЖИМ (window.py:135-142) ---
                    CapsLabel("РЕЖИМ")
                    Spacer(Modifier.height(3.dp))
                    Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp)) {
                        GameRadio(
                            "Весь трафик - TUN (нужен админ)",
                            checked = mode == "tun",
                            onClick = { mode = "tun" },
                            modifier = Modifier.weight(270f),
                        )
                        GameRadio(
                            "Системный прокси",
                            checked = mode == "proxy",
                            onClick = { mode = "proxy" },
                            modifier = Modifier.weight(280f),
                        )
                    }
                    Spacer(Modifier.height(5.dp))
                    DividerView()
                    Spacer(Modifier.height(10.dp))

                    // --- ИСКЛЮЧЕНИЯ (window.py:147-170) ---
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        CapsLabel("ИСКЛЮЧЕНИЯ", Modifier.weight(1f))
                        BasicText(
                            "игры, Steam и античиты исключены автоматически",
                            style = Fonts.Sub.copy(color = Theme.TextDim),
                            maxLines = 1,
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    Row(modifier = Modifier.fillMaxWidth()) {
                        // пустая рамка; список исключений - этап 7
                        GameFrame(Modifier.weight(260f).height(124.dp))
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(286f)) {
                            GameField(
                                procName, { procName = it },
                                "выбери процесс или впиши имя .exe",
                                Modifier.fillMaxWidth(),
                            )
                            Spacer(Modifier.height(4.dp))
                            GameButton(
                                "Добавить", ButtonKind.GHOST,
                                Modifier.fillMaxWidth(), height = 36.dp,
                            )
                            Spacer(Modifier.height(4.dp))
                            Row {
                                GameButton(
                                    "Удалить", ButtonKind.GHOST,
                                    Modifier.weight(138f), height = 36.dp,
                                )
                                GameButton(
                                    "Очистить", ButtonKind.GHOST,
                                    Modifier.weight(148f), height = 36.dp,
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    BasicText(
                        "Список процессов обновляется при запуске. В поле можно вписать имя .exe вручную.",
                        style = Fonts.Sub.copy(color = Theme.TextDim),
                    )
                    Spacer(Modifier.height(8.dp))
                    DividerView()
                    Spacer(Modifier.height(10.dp))

                    // --- подключение (window.py:175-193) ---
                    Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp)) {
                        GameButton(
                            "ПОДКЛЮЧИТЬСЯ", ButtonKind.ACCENT,
                            Modifier.weight(288f), height = 46.dp, radius = 9,
                        )
                        GameButton(
                            "ОТКЛЮЧИТЬ", ButtonKind.DANGER,
                            Modifier.weight(118f), height = 46.dp, radius = 9,
                            enabled = false,
                        )
                        GameButton(
                            "Проверить конфиг", ButtonKind.GHOST,
                            Modifier.weight(130f), height = 46.dp, radius = 9,
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        LedView(LedState.OFF)
                        Spacer(Modifier.width(8.dp))
                        BasicText(
                            // на десктопе стадии 2-5; логика андроид-версии - этапы 6-7
                            "Готов - оболочка, логика на этапах 6-7",
                            style = Fonts.Body.copy(color = Theme.Text),
                            maxLines = 1,
                            modifier = Modifier.weight(1f),
                        )
                        // egress-адрес: заполняется после подключения (этап 7)
                        BasicText(
                            "",
                            style = Fonts.Mono.copy(color = Theme.TextDim, textAlign = TextAlign.End),
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }
}

/**
 * Шапка (title_bar.py Install-GameTitleBar): фон-градиент (20,24,33)->(12,14,19),
 * нижняя граница Line, левый кант 3px Accent->AccentD, логотип со слоем свечения
 * (0,180,215,a=110) со смещением (+1,+1) и byline. Кнопки окна (capmin/capclose)
 * и LED шапки не переносятся - окно Android нативное.
 */
@Composable
private fun TitleBar() {
    Box(
        Modifier
            .fillMaxWidth()
            .height(50.dp)
            .drawBehind {
                val h = size.height
                val s = 1.dp.toPx()
                drawRect(Brush.verticalGradient(listOf(rgb(20, 24, 33), rgb(12, 14, 19))))
                // нижняя граница Line (в desktop - y=45 высоты 46)
                drawLine(
                    Theme.Line,
                    Offset(0f, h - s / 2),
                    Offset(size.width, h - s / 2),
                    strokeWidth = s,
                )
                // левый кант 3px: градиент Accent -> AccentD
                drawRect(
                    Brush.verticalGradient(listOf(Theme.Accent, Theme.AccentD)),
                    size = Size(3.dp.toPx(), h),
                )
            },
    ) {
        Row(
            Modifier
                .fillMaxSize()
                .padding(start = 16.dp, end = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box {
                BasicText(
                    "VPN ЛАУНЧЕР",
                    style = Fonts.Logo.copy(color = rgb(0, 180, 215, 110)),
                    modifier = Modifier.offset(1.dp, 1.dp),
                )
                BasicText(
                    "VPN ЛАУНЧЕР",
                    style = Fonts.Logo.copy(color = Theme.Accent),
                )
            }
            Spacer(Modifier.width(10.dp))
            BasicText(
                "by @YoncFALL",
                style = Fonts.Sub.copy(color = Theme.TextDim),
            )
        }
    }
}
