// Игровые виджеты (порт vpn_launcher/ui/widgets/*, стили из theme.ps1):
//   GameCard  - большая карточка (card.py): ореол, градиент-заливка, рамка Line
//               и акцентные градиенты по краям (правка «по просьбе»);
//   GameButton- кнопка (button.py): градиент + рамка + блик сверху, скины
//               ghost/accent/danger, выключенная = ghost с тусклым текстом;
//   GameField - поле ввода (field.py): заливка Field, рамка Border -> BorderFocus;
//   GameRadio - сегмент режима (radio.py): точка 6px у выбранного (удалён
//               после правки v2.0.0 - секции РЕЖИМ на Android нет);
//   DividerView- линия с акцентным градиентом слева->справа (divider.py);
//   GameFrame - рамка списка (frame.py): блик по верхней грани;
//   LedView   - лампочка (led.py): свечение + ядро + блик.
//
// Отклонения (тач-окружение): hover-состояния нет, вместо них нажатие (down);
// высоты контролов чуть выше десктопных (30-34px -> 36-44dp) под палец.
package com.yoncfall.vpnlauncher.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

// ---------------------------------------------------------------------------
// Карточка (card.py)
// ---------------------------------------------------------------------------

/**
 * Большая карточка интерфейса. Порт New-GameCard (theme.ps1:1090-1134):
 * ореол, заливка (27,31,42)->(22,25,34), рамка Line радиус 14 и акцентные
 * градиенты по краям: ярче правый верхний/левый нижний углы, линии гаснут
 * вдоль рёбер (верх влево, право вниз, низ вправо, лево вверх).
 */
@Composable
fun GameCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val radius = 14.dp
    Box(
        modifier
            .clip(RoundedCornerShape(radius))
            .drawBehind {
                val w = size.width
                val h = size.height
                val r = radius.toPx()
                val s = 1.dp.toPx()
                // ореол (в PS - PathGradientBrush по внешнему контуру)
                drawRoundRect(
                    rgb(24, 34, 46, 26),
                    topLeft = Offset(2f, 2f),
                    size = Size(w - 4f, h - 4f),
                    cornerRadius = CornerRadius(r + 3.dp.toPx()),
                )
                // заливка градиентом
                drawRoundRect(
                    Brush.verticalGradient(listOf(rgb(27, 31, 42), rgb(22, 25, 34))),
                    topLeft = Offset(s, s),
                    size = Size(w - 2 * s, h - 2 * s),
                    cornerRadius = CornerRadius(r),
                )
                // рамка Line
                drawRoundRect(
                    Theme.Line,
                    topLeft = Offset(s, s),
                    size = Size(w - 2 * s, h - 2 * s),
                    cornerRadius = CornerRadius(r),
                    style = Stroke(s),
                )
                // акцентные градиенты по краям (альфа 215 в светлых углах)
                val a = 215f / 255f
                val inset = s
                val top = Path().apply {
                    moveTo(inset, inset + r)
                    arcTo(Rect(Offset(inset, inset), Size(2 * r, 2 * r)), 180f, 90f, false)
                    lineTo(w - inset - r, inset)
                    arcTo(
                        Rect(Offset(w - inset - 2 * r, inset), Size(2 * r, 2 * r)),
                        270f, 90f, false,
                    )
                }
                drawPath(
                    top,
                    Brush.horizontalGradient(
                        listOf(Theme.Accent.copy(alpha = 0f), Theme.Accent.copy(alpha = a)),
                        startX = inset, endX = w - inset,
                    ),
                    style = Stroke(s),
                )
                val right = Path().apply {
                    moveTo(w - inset, inset + r)
                    lineTo(w - inset, h - inset - r)
                }
                drawPath(
                    right,
                    Brush.verticalGradient(
                        listOf(Theme.Accent.copy(alpha = a), Theme.Accent.copy(alpha = 0f)),
                        startY = inset, endY = h - inset,
                    ),
                    style = Stroke(s),
                )
                val bottom = Path().apply {
                    moveTo(inset, h - inset - r)
                    arcTo(Rect(Offset(inset, h - inset - 2 * r), Size(2 * r, 2 * r)), 180f, -90f, false)
                    lineTo(w - inset - r, h - inset)
                }
                drawPath(
                    bottom,
                    Brush.horizontalGradient(
                        listOf(Theme.Accent.copy(alpha = 0f), Theme.Accent.copy(alpha = a)),
                        startX = w - inset, endX = inset,
                    ),
                    style = Stroke(s),
                )
                val left = Path().apply {
                    moveTo(inset, h - inset - r)
                    lineTo(inset, inset + r)
                }
                drawPath(
                    left,
                    Brush.verticalGradient(
                        listOf(Theme.Accent.copy(alpha = a), Theme.Accent.copy(alpha = 0f)),
                        startY = h - inset, endY = inset,
                    ),
                    style = Stroke(s),
                )
            },
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .padding(start = 18.dp, top = 12.dp, end = 18.dp, bottom = 14.dp),
            content = content,
        )
    }
}

// ---------------------------------------------------------------------------
// Кнопка (button.py)
// ---------------------------------------------------------------------------

/** Виды кнопок (button.py kinds). */
enum class ButtonKind { GHOST, ACCENT, DANGER }

private data class Skin(
    val top: Color, val bottom: Color, val border: Color, val text: Color,
    val downTop: Color, val downBottom: Color, val downBorder: Color, val downText: Color,
)

// kind -> normal/down (hover-состояния на тач-устройствах не используются)
private val SKINS = mapOf(
    ButtonKind.GHOST to Skin(
        top = rgb(27, 31, 42), bottom = rgb(21, 24, 32),
        border = rgb(46, 52, 68), text = rgb(176, 184, 200),
        downTop = rgb(17, 20, 28), downBottom = rgb(15, 17, 24),
        downBorder = rgb(46, 52, 68), downText = rgb(132, 142, 162),
    ),
    ButtonKind.ACCENT to Skin(
        top = rgb(46, 226, 255), bottom = rgb(0, 160, 205),
        border = rgb(0, 205, 245), text = rgb(3, 26, 34),
        downTop = rgb(0, 140, 178), downBottom = rgb(0, 110, 148),
        downBorder = rgb(0, 190, 230), downText = rgb(3, 26, 34),
    ),
    ButtonKind.DANGER to Skin(
        top = rgb(255, 104, 132), bottom = rgb(196, 48, 78),
        border = rgb(255, 92, 122), text = rgb(42, 4, 12),
        downTop = rgb(196, 48, 78), downBottom = rgb(150, 34, 58),
        downBorder = rgb(220, 70, 100), downText = rgb(42, 4, 12),
    ),
)

/**
 * Игровая кнопка. Порт New-GameButton (theme.ps1:724-782): градиент сверху-вниз,
 * рамка, тонкий блик по верхней грани (theme.ps1:755-756), текст по центру (FBtn).
 * Выключенная: ghost normal + тусклый текст (как в 1.0.6).
 */
@Composable
fun GameButton(
    text: String,
    kind: ButtonKind,
    modifier: Modifier = Modifier,
    height: Dp = 40.dp,
    radius: Int = 8,
    enabled: Boolean = true,
    onClick: () -> Unit = {},
) {
    val skin = SKINS.getValue(kind)
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val top: Color
    val bottom: Color
    val border: Color
    val textColor: Color
    when {
        !enabled -> {
            top = rgb(27, 31, 42); bottom = rgb(21, 24, 32)
            border = rgb(46, 52, 68); textColor = Theme.DisabledText
        }
        pressed -> {
            top = skin.downTop; bottom = skin.downBottom
            border = skin.downBorder; textColor = skin.downText
        }
        else -> {
            top = skin.top; bottom = skin.bottom
            border = skin.border; textColor = skin.text
        }
    }
    Box(
        modifier = modifier
            .height(height)
            .clip(RoundedCornerShape(radius.dp))
            .drawBehind {
                val r = radius.dp.toPx()
                val s = 1.dp.toPx()
                drawRect(Brush.verticalGradient(listOf(top, bottom)))
                drawRoundRect(
                    border,
                    topLeft = Offset(s / 2, s / 2),
                    size = Size(size.width - s, size.height - s),
                    cornerRadius = CornerRadius(r),
                    style = Stroke(s),
                )
                if (!pressed) {
                    // тонкий блик сверху (theme.ps1:755-756)
                    drawLine(
                        Color.White.copy(alpha = 60f / 255f),
                        Offset(4.dp.toPx(), 2.dp.toPx()),
                        Offset(size.width - 5.dp.toPx(), 2.dp.toPx()),
                        strokeWidth = s,
                    )
                }
            }
            .then(
                if (enabled) {
                    Modifier.clickable(interaction, indication = null, onClick = onClick)
                } else {
                    Modifier
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        // E2E 720p: длинные подписи («Загрузить подписку», «Проверить конфиг»)
        // обрезались по центру без многоточия - ужимаем шрифт до влезания
        var autoSize by remember(text) { mutableStateOf(Fonts.Btn.fontSize.value) }
        BasicText(
            text,
            style = Fonts.Btn.copy(
                color = textColor,
                textAlign = TextAlign.Center,
                fontSize = autoSize.sp,
            ),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            onTextLayout = { r ->
                if ((r.hasVisualOverflow || r.didOverflowWidth) && autoSize > 7f) autoSize -= 0.5f
            },
            modifier = Modifier.padding(horizontal = 6.dp),
        )
    }
}

// ---------------------------------------------------------------------------
// Поле ввода (field.py)
// ---------------------------------------------------------------------------

/**
 * Тёмное поле ввода. Порт GameField (theme.ps1:54-130): заливка Field,
 * радиус 7, рамка Border -> BorderFocus по фокусу (hover нет на тач),
 * плейсхолдер Placeholder, текст FBody.
 *
 * Безопасность: параметр `mask` (URL подписки с токеном) - вне фокуса поле
 * показывает маску, секрет раскрывается только на время редактирования
 * (тап по маске -> раскрытие + фокус + клавиатура; потеря фокуса -> снова
 * маска), поэтому случайный скриншот/запись экрана не покажет токен.
 * Ввод идёт только по реальному значению: маска - отдельный BasicText,
 * поле ввода монтируется лишь когда раскрыто - правки не искажаются.
 */
@Composable
fun GameField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    height: Dp = 44.dp,
    onDone: (() -> Unit)? = null,
    mask: ((String) -> String)? = null,
) {
    var focused by remember { mutableStateOf(false) }
    var revealed by remember { mutableStateOf(false) }
    val masked = mask != null && value.isNotEmpty() && !focused && !revealed
    // потеря фокуса -> снова маска (на первом кадре revealed=false - no-op)
    LaunchedEffect(focused) { if (!focused) revealed = false }
    // клавиатура убрана (back / тап мимо поля): фокус на поле часто остаётся,
    // поэтому маску возвращаем и по закрытию клавиатуры - токен не висит на
    // экране без нужды. Задержка нужна, чтобы клавиатура успела открыться
    // после тапа по маске (иначе маска вернулась бы сразу).
    val imeVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    LaunchedEffect(imeVisible, revealed) {
        if (revealed && !imeVisible) {
            delay(600)
            if (revealed && !imeVisible) {
                revealed = false
                focused = false
            }
        }
    }
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val shape = RoundedCornerShape(7.dp)
    Box(
        modifier
            .height(height)
            .clip(shape)
            .background(Theme.Field)
            .onFocusChanged { focused = it.isFocused }
            .border(width = 1.dp, color = if (focused) Theme.BorderFocus else Theme.Border, shape = shape)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        if (masked) {
            // маска вместо текста: тап раскрывает поле для редактирования
            BasicText(
                mask!!(value),
                style = Fonts.Body.copy(color = Theme.Text),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { revealed = true },
            )
        } else {
            if (value.isEmpty()) {
                BasicText(placeholder, style = Fonts.Body.copy(color = Theme.Placeholder))
            }
            androidx.compose.foundation.text.BasicTextField(
                value = value,
                onValueChange = onValueChange,
                textStyle = Fonts.Body.copy(color = Theme.Text),
                cursorBrush = SolidColor(Theme.Accent),
                singleLine = true,
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    imeAction = if (onDone != null) androidx.compose.ui.text.input.ImeAction.Done
                    else androidx.compose.ui.text.input.ImeAction.Default,
                ),
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                    onDone = if (onDone != null) { { onDone() } } else null,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester),
            )
            if (revealed && !focused) {
                // раскрытие по тапу: сразу фокус и клавиатура
                LaunchedEffect(Unit) {
                    focusRequester.requestFocus()
                    keyboard?.show()
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Разделитель (divider.py)
// ---------------------------------------------------------------------------

/**
 * Разделитель. Порт New-GameDivider (theme.ps1:1137-1157) с правкой «по просьбе»:
 * поверх базовой линии (Line a=90) - акцентный градиент слева прозрачный ->
 * справа голубой.
 */
@Composable
fun DividerView(modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .height(2.dp)
            .drawBehind {
                val y = 1.dp.toPx()
                val s = 1.dp.toPx()
                drawLine(
                    Theme.Line.copy(alpha = 90f / 255f),
                    Offset(0f, y), Offset(size.width, y),
                    strokeWidth = s,
                )
                drawLine(
                    Brush.horizontalGradient(
                        listOf(Theme.Accent.copy(alpha = 0f), Theme.Accent.copy(alpha = 160f / 255f)),
                    ),
                    Offset(0f, y), Offset(size.width, y),
                    strokeWidth = s,
                )
            },
    )
}

// ---------------------------------------------------------------------------
// Рамка списка (frame.py)
// ---------------------------------------------------------------------------

/**
 * Тёмная рамка-контейнер списка. Порт GameFrame (theme.ps1:130-154):
 * заливка (18,21,29), рамка Line, радиус 10, белый блик (a=70) по верхней грани.
 */
@Composable
fun GameFrame(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit = {},
) {
    Box(
        modifier
            .clip(RoundedCornerShape(10.dp))
            .background(Theme.Bg2)
            .drawBehind {
                val r = 10.dp.toPx()
                val s = 1.dp.toPx()
                drawRoundRect(
                    Theme.Line,
                    topLeft = Offset(s, s),
                    size = Size(size.width - 2 * s, size.height - 2 * s),
                    cornerRadius = CornerRadius(r),
                    style = Stroke(s),
                )
                drawLine(
                    Color.White.copy(alpha = 70f / 255f),
                    Offset(r + 1.dp.toPx(), 2.dp.toPx()),
                    Offset(size.width - r - 1.dp.toPx(), 2.dp.toPx()),
                    strokeWidth = s,
                )
            },
        content = content,
    )
}

// ---------------------------------------------------------------------------
// Лампочка (led.py)
// ---------------------------------------------------------------------------

/** Состояния LED (led.py _STATES). */
enum class LedState { OFF, BUSY, OK, ERR }

/** Индикатор. Порт New-GameLed (theme.ps1:1049-1087): свечение + ядро + блик. */
@Composable
fun LedView(state: LedState, modifier: Modifier = Modifier, diameter: Dp = 10.dp) {
    val color = when (state) {
        LedState.OFF -> Theme.Muted
        LedState.BUSY -> Theme.Accent
        LedState.OK -> Theme.Accent2
        LedState.ERR -> Theme.Danger
    }
    Box(
        modifier
            .size(diameter)
            .drawBehind {
                val d = size.width
                val glow = (d + 4.dp.toPx()) / 2
                drawCircle(
                    Brush.radialGradient(
                        listOf(color.copy(alpha = 150f / 255f), color.copy(alpha = 0f)),
                        radius = glow,
                    ),
                    radius = glow,
                    center = Offset(d / 2, d / 2),
                )
                drawCircle(
                    color,
                    radius = (d - 2.dp.toPx()) / 2,
                    center = Offset(d / 2, d / 2),
                )
                val hi = maxOf(2.dp.toPx(), (d - 2.dp.toPx()) * 0.4f)
                drawCircle(
                    Color.White.copy(alpha = 160f / 255f),
                    radius = hi / 2,
                    center = Offset(2.dp.toPx() + hi / 2, 2.dp.toPx() + hi / 2),
                )
            },
    )
}

// ---------------------------------------------------------------------------
// Подзаголовки секций
// ---------------------------------------------------------------------------

/** Капс-заголовок секции (window.py _label, FCaps, TEXT_DIM). */
@Composable
fun CapsLabel(text: String, modifier: Modifier = Modifier, textAlign: TextAlign? = null) {
    // E2E 720p: заголовки таблицы («ПРОТОКОЛ») и секций впритык - автоподбор
    var autoSize by remember(text) { mutableStateOf(Fonts.Caps.fontSize.value) }
    BasicText(
        text,
        style = Fonts.Caps.copy(
            color = Theme.TextDim,
            fontSize = autoSize.sp,
        ).let { if (textAlign != null) it.copy(textAlign = textAlign) else it },
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        onTextLayout = { r ->
            if ((r.hasVisualOverflow || r.didOverflowWidth) && autoSize > 6f) autoSize -= 0.5f
        },
        modifier = modifier,
    )
}
