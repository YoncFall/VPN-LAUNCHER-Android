// Главный экран. Раскладка - порт ui/window.py (карточка 14,54,592x664 по
// координатам VPN.ps1:84-222): секции ПОДПИСКА/СЕРВЕРЫ/ИСКЛЮЧЕНИЯ,
// кнопки подключения, LED+статус. Тексты и цвета дословно из window.py.
// Логика кнопок (загрузка подписки, пинг, туннель) - AppViewModel (этап 7).
//
// Отклонения: фиксированное окно 620x726 -> поток в колонке (телефоны уже),
// поэтому внутри карточки вертикальный скролл; высоты контролов под палец;
// строки списка 38dp (на десктопе ~16px); MessageBox -> встроенный диалог,
// блокнот с журналом -> встроенный экран журнала (AppViewModel: header).
// Правки после v2.0.0 (по отзывам с устройства): секция РЕЖИМ удалена -
// режим на Android всегда TUN, выбирать нечего; обновление подписки -
// кнопка-стрелка «⟳ Обновить» в строке ПОДПИСКА (перечитывает URL,
// обновляет серверы и сразу перепинговывает), «Загрузить подписку» и
// «⟳ Обновить» - цветом ACCENT как «ПОДКЛЮЧИТЬСЯ» (видны пользователю);
// «Открыть лог» перенесено под статус внизу; «Добавить» в исключениях
// убрано - пакет вводится в поле и подтверждается клавишей «Готово»
// (onDone = addExclusion).
package com.yoncfall.vpnlauncher.ui

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

@Composable
fun AppScreen() {
    val vm: AppViewModel = viewModel()
    val ui by vm.state.collectAsState()
    val logLines by AppViewModel.logLines.collectAsState()
    val scroll = rememberScrollState()

    // системное согласие VpnService.prepare (эквивалент UAC в 1.0.6)
    val consentLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        vm.onConsentResult(result.resultCode == Activity.RESULT_OK)
    }
    LaunchedEffect(vm) {
        vm.consent.collect { intent ->
            if (intent != null) {
                vm.consentShown()
                consentLauncher.launch(intent)
            }
        }
    }

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
                        ui.subUrl, vm::onSubUrl,
                        "вставь ссылку на подписку (https://...)",
                        Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        GameButton(
                            // акцентный цвет (kind=ACCENT, как у «ПОДКЛЮЧИТЬСЯ»):
                            // главная кнопка секции, должна бросаться в глаза
                            if (ui.loadRunning) "Загрузка..." else "Загрузить подписку",
                            ButtonKind.ACCENT,
                            Modifier.weight(208f),
                            enabled = !ui.loadRunning,
                            onClick = vm::loadSubscription,
                        )
                        GameButton(
                            if (ui.pingRunning) "Пинг..." else "Проверить пинг",
                            ButtonKind.GHOST,
                            Modifier.weight(150f),
                            enabled = !ui.pingRunning,
                            onClick = vm::pingAll,
                        )
                        // обновление подписки «как в браузере»: перечитать
                        // URL -> новые серверы -> автопинг (refreshSubscription);
                        // акцентный цвет - вторая «главная» кнопка секции
                        GameButton(
                            "⟳ Обновить", ButtonKind.ACCENT, Modifier.weight(130f),
                            enabled = !ui.loadRunning,
                            onClick = vm::refreshSubscription,
                        )
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
                        // CapsLabel: автоподбор шрифта (57dp жёстко обрезал «ПРОТОКОЛ»)
                        CapsLabel("ПРОТОКОЛ", Modifier.width(57.dp), TextAlign.End)
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
                    GameFrame(Modifier.fillMaxWidth().height(178.dp)) {
                        LazyColumn(
                            Modifier.fillMaxSize().background(Theme.Bg2),
                            contentPadding = PaddingValues(vertical = 4.dp),
                        ) {
                            itemsIndexed(ui.nodes) { index, node ->
                                ServerRow(
                                    node = node,
                                    ping = node["tag"]?.jsonPrimitive?.content?.let { ui.pings[it] },
                                    selected = ui.selectedIndex == index,
                                    odd = index % 2 == 1,
                                    onClick = { vm.selectNode(index) },
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    DividerView()
                    Spacer(Modifier.height(10.dp))

                    // --- РЕЖИМ удалён (правка после v2.0.0): на Android
                    // режим всегда TUN, единственное радио без выбора ---

                    // --- ИСКЛЮЧЕНИЯ (window.py:147-170) ---
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        CapsLabel("ИСКЛЮЧЕНИЯ", Modifier.weight(1f))
                        BasicText(
                            "приложения из списка идут мимо VPN",
                            style = Fonts.Sub.copy(color = Theme.TextDim),
                            maxLines = 1,
                        )
                    }
                    Spacer(Modifier.height(4.dp))

                    // отображаем label приложения, храним пакет
                    val pm = LocalContext.current.packageManager
                    val exclLabels = remember(ui.exclusions) {
                        ui.exclusions.associateWith { pkg ->
                            runCatching {
                                pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
                            }.getOrDefault(pkg)
                        }
                    }
                    Row(modifier = Modifier.fillMaxWidth()) {
                        GameFrame(Modifier.weight(260f).height(124.dp)) {
                            LazyColumn(
                                Modifier.fillMaxSize().background(Theme.Bg2),
                                contentPadding = PaddingValues(vertical = 4.dp),
                            ) {
                                itemsIndexed(ui.exclusions) { index, name ->
                                    ExclusionRow(
                                        name = exclLabels[name] ?: name,
                                        selected = ui.selectedExcl == index,
                                        odd = index % 2 == 1,
                                        onClick = { vm.selectExcl(index) },
                                    )
                                }
                            }
                        }
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(286f)) {
                            GameField(
                                ui.procInput, vm::onProcInput,
                                "выбери кнопкой Приложения или впиши пакет",
                                Modifier.fillMaxWidth(),
                                onDone = vm::addExclusion,
                            )
                            Spacer(Modifier.height(4.dp))
                            // «Добавить» убрана (правка после v2.0.0):
                            // ввод пакета подтверждается клавишей «Готово»
                            // (onDone = addExclusion), picker добавляет сам
                            GameButton(
                                "Приложения", ButtonKind.GHOST,
                                Modifier.fillMaxWidth(), height = 36.dp,
                                onClick = vm::showAppPicker,
                            )
                            Spacer(Modifier.height(4.dp))
                            Row {
                                GameButton(
                                    "Удалить", ButtonKind.GHOST,
                                    Modifier.weight(138f), height = 36.dp,
                                    onClick = vm::deleteExclusion,
                                )
                                GameButton(
                                    "Очистить", ButtonKind.GHOST,
                                    Modifier.weight(148f), height = 36.dp,
                                    onClick = vm::clearExclusions,
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    BasicText(
                        "Приложения из списка не пойдут через VPN. Пакет можно вписать и вручную (com.example.app).",
                        style = Fonts.Sub.copy(color = Theme.TextDim),
                    )
                    Spacer(Modifier.height(8.dp))
                    DividerView()
                    Spacer(Modifier.height(10.dp))

                    // --- подключение (window.py:175-193) ---
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        GameButton(
                            "ПОДКЛЮЧИТЬСЯ", ButtonKind.ACCENT,
                            Modifier.weight(288f), height = 46.dp, radius = 9,
                            enabled = !ui.connectRunning && !ui.disconnectEnabled,
                            onClick = vm::connect,
                        )
                        GameButton(
                            "ОТКЛЮЧИТЬ", ButtonKind.DANGER,
                            Modifier.weight(118f), height = 46.dp, radius = 9,
                            enabled = ui.disconnectEnabled,
                            onClick = vm::disconnect,
                        )
                        GameButton(
                            "Проверить конфиг", ButtonKind.GHOST,
                            Modifier.weight(130f), height = 46.dp, radius = 9,
                            onClick = vm::testConfig,
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        LedView(ui.led)
                        Spacer(Modifier.width(8.dp))
                        BasicText(
                            ui.status,
                            style = Fonts.Body.copy(
                                color = statusColor(ui.statusLevel),
                                textAlign = TextAlign.Start,
                            ),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        BasicText(
                            ui.egress,
                            style = Fonts.Mono.copy(
                                color = if (ui.egressAccent) Theme.Accent else Theme.TextDim,
                                textAlign = TextAlign.End,
                            ),
                            maxLines = 1,
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    // «Открыть лог» перенесено вниз (правка после v2.0.0):
                    // сверху освободили место под кнопку обновления подписки
                    GameButton(
                        "Открыть лог", ButtonKind.GHOST,
                        Modifier.fillMaxWidth(), height = 36.dp,
                        onClick = vm::showLog,
                    )
                }
            }
        }
    }

    ui.dialog?.let { dialog ->
        GameDialogView(dialog, onDismiss = vm::dismissDialog, onPick = vm::pickApp)
    }
    if (ui.logVisible) {
        LogScreen(lines = logLines, onClose = vm::hideLog)
    }
}

// ------------------------------------------------------------------ строки

/** Плашка выделения/чёточный фон строки (theme.ps1:835-851,583-891). */
@Composable
private fun rowChrome(selected: Boolean, odd: Boolean, onClick: () -> Unit): Modifier =
    Modifier
        .fillMaxWidth()
        .height(38.dp) // строка под палец (на десктопе ~16px)
        .then(
            if (selected) {
                Modifier.drawBehind {
                    // скруглённая плашка (3, rowY+2, W-6, rh-4) радиус 5
                    val left = 3.dp.toPx()
                    val top = 2.dp.toPx()
                    drawRoundRect(
                        brush = Brush.verticalGradient(listOf(rgb(44, 62, 82), rgb(28, 44, 62))),
                        topLeft = Offset(left, top),
                        size = Size(size.width - left * 2, size.height - top * 2),
                        cornerRadius = CornerRadius(5.dp.toPx()),
                    )
                    // неон-полоска (3, rowY+4, 3, rh-8) Accent -> AccentD
                    drawRect(
                        brush = Brush.verticalGradient(listOf(Theme.Accent, Theme.AccentD)),
                        topLeft = Offset(left, 4.dp.toPx()),
                        size = Size(3.dp.toPx(), size.height - 8.dp.toPx()),
                    )
                }
            } else if (odd) {
                Modifier.background(rgb(22, 25, 34))
            } else {
                Modifier
            },
        )
        .clickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = null,
            onClick = onClick,
        )
        .padding(horizontal = 11.dp)

@Composable
private fun ServerRow(
    node: JsonObject,
    ping: Int?,
    selected: Boolean,
    odd: Boolean,
    onClick: () -> Unit,
) {
    // proto: switch в theme.ps1:863-865, default '???'
    val proto = node["proto"]?.jsonPrimitive?.content
    val protoText = when (proto) {
        "vless" -> "VLESS"
        "vmess" -> "VMESS"
        "trojan" -> "TROJAN"
        "shadowsocks" -> "SS"
        "hysteria2" -> "HY2"
        "tuic" -> "TUIC"
        else -> "???"
    }
    val name = node["display"]?.jsonPrimitive?.content ?: ""
    // пинг: theme.ps1:876-886 ('---'/'...'/'offline'/пороги 120/260)
    val (pingText, pingColor) = when {
        ping == null -> "---" to Theme.TextDim
        ping == -2 -> "..." to Theme.TextDim
        ping < 0 -> "offline" to Theme.Danger
        ping < 120 -> "$ping ms" to Theme.Accent2
        ping < 260 -> "$ping ms" to Theme.Warn
        else -> "$ping ms" to rgb(255, 140, 90)
    }
    val nameColor = if (selected) Theme.Text else rgb(206, 212, 226)
    Row(
        rowChrome(selected, odd, onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(46.dp), contentAlignment = Alignment.CenterEnd) {
            BasicText(protoText, style = Fonts.Caps.copy(color = Theme.TextDim), maxLines = 1)
        }
        Spacer(Modifier.width(6.dp))
        BasicText(
            name,
            style = Fonts.Body.copy(color = nameColor),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(6.dp))
        Box(Modifier.width(70.dp), contentAlignment = Alignment.CenterEnd) {
            BasicText(pingText, style = Fonts.Body.copy(color = pingColor), maxLines = 1)
        }
    }
}

@Composable
private fun ExclusionRow(name: String, selected: Boolean, odd: Boolean, onClick: () -> Unit) {
    val nameColor = if (selected) Theme.Text else rgb(206, 212, 226)
    Row(
        rowChrome(selected, odd, onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicText(
            name,
            style = Fonts.Body.copy(color = nameColor),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

// ------------------------------------------------------------------ диалог

/** MessageBox (текст/заголовок дословно из VPN.ps1; иконки не портируются).
 *  С items = picker установленных приложений (исключения): тап по строке
 *  передаёт пакет в onPick. */
@Composable
private fun GameDialogView(
    dialog: GameDialog,
    onDismiss: () -> Unit,
    onPick: (String) -> Unit,
) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0x99000000))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onDismiss,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 36.dp)
                .background(Theme.Bg2, RoundedCornerShape(12.dp))
                .border(1.dp, Theme.Line, RoundedCornerShape(12.dp))
                .padding(16.dp),
        ) {
            if (dialog.title.isNotBlank()) {
                BasicText(dialog.title, style = Fonts.Caps.copy(color = Theme.Accent), maxLines = 1)
                Spacer(Modifier.height(8.dp))
            }
            if (dialog.items.isNotEmpty()) {
                if (dialog.text.isNotBlank()) {
                    BasicText(dialog.text, style = Fonts.Body.copy(color = Theme.Text))
                    Spacer(Modifier.height(8.dp))
                }
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 340.dp)) {
                    itemsIndexed(dialog.items) { _, item ->
                        val (label, pkg) = item
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .clickable { onPick(pkg) }
                                .padding(vertical = 9.dp, horizontal = 4.dp),
                        ) {
                            BasicText(
                                label,
                                style = Fonts.Body.copy(color = Theme.Text),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            BasicText(
                                pkg,
                                style = Fonts.Sub.copy(color = Theme.TextDim),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        DividerView()
                    }
                }
                Spacer(Modifier.height(16.dp))
                GameButton(
                    "Отмена", ButtonKind.GHOST,
                    Modifier.fillMaxWidth(), height = 40.dp, radius = 8,
                    onClick = onDismiss,
                )
            } else {
                BasicText(dialog.text, style = Fonts.Body.copy(color = Theme.Text))
                Spacer(Modifier.height(16.dp))
                GameButton(
                    "ОК", ButtonKind.ACCENT,
                    Modifier.fillMaxWidth(), height = 40.dp, radius = 8,
                    onClick = onDismiss,
                )
            }
        }
    }
}

// ------------------------------------------------------------------- журнал

/**
 * Экран журнала. 1.0.6 открывал блокнот с файлом лога; здесь AppLog живёт
 * в памяти (см. AppViewModel), формат строки - как write_log.
 */
@Composable
private fun LogScreen(lines: List<String>, onClose: () -> Unit) {
    val logScroll = rememberScrollState()
    LaunchedEffect(lines.size) {
        logScroll.scrollTo(logScroll.maxValue)
    }
    Column(
        Modifier
            .fillMaxSize()
            .background(Theme.Bg)
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(14.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            CapsLabel("ЖУРНАЛ", Modifier.weight(1f))
            GameButton(
                "Закрыть", ButtonKind.GHOST,
                Modifier.width(120.dp), height = 36.dp,
                onClick = onClose,
            )
        }
        Spacer(Modifier.height(8.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .background(Theme.Bg2, RoundedCornerShape(7.dp)),
        ) {
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(logScroll)
                    .padding(10.dp),
            ) {
                if (lines.isEmpty()) {
                    BasicText("журнал пуст", style = Fonts.Mono.copy(color = Theme.TextDim))
                }
                for (line in lines) {
                    BasicText(line, style = Fonts.Mono.copy(color = Theme.Text), maxLines = 1)
                }
            }
        }
    }
}

// ------------------------------------------------------------- цвет статуса

private fun statusColor(level: StatusLevel): Color = when (level) {
    StatusLevel.TEXT -> Theme.Text
    StatusLevel.DANGER -> Theme.Danger
    StatusLevel.WARN -> Theme.Warn
    StatusLevel.OK -> Theme.Accent2
    StatusLevel.ACCENT -> Theme.Accent
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
