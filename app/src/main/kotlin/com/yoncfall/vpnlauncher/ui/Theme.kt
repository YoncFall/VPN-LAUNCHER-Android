// Палитра и шрифты. Порт vpn_launcher/ui/theme.py ($script:T из theme.ps1:612-626,
// набор шрифтов theme.ps1:638-645).
//
// Отклонения: Bahnschrift (Windows) -> системный sans-serif Android (Roboto),
// размеры pt -> sp (примерно x1.45; sp масштабируется пользователем).
package com.yoncfall.vpnlauncher.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** Собрать Color из каналов (аргументы в диапазоне 0..255, alpha по умолчанию непрозрачный). */
fun rgb(r: Int, g: Int, b: Int, a: Int = 255): Color =
    Color((a shl 24) or (r shl 16) or (g shl 8) or b)

/** $script:T (theme.ps1:612-626) + рамки контролов (theme.ps1:54-57, 131-132). */
object Theme {
    val Bg = rgb(12, 14, 19)
    val Bg2 = rgb(18, 21, 29)
    val Card = rgb(24, 27, 37)
    val CardHi = rgb(33, 38, 51)
    val Line = rgb(46, 52, 68)
    val LineHi = rgb(72, 82, 108)
    val Field = rgb(19, 22, 30)
    val Text = rgb(230, 234, 242)
    val TextDim = rgb(132, 142, 162)
    val Accent = rgb(0, 216, 255)
    val AccentD = rgb(0, 150, 190)
    val Accent2 = rgb(0, 240, 168)
    val Danger = rgb(255, 77, 109)
    val Warn = rgb(255, 196, 84)
    val Muted = rgb(58, 64, 80)
    val Border = rgb(58, 66, 86)
    val BorderHover = rgb(96, 108, 138)
    val BorderFocus = rgb(0, 200, 240)
    val Placeholder = rgb(110, 118, 136)
    val DisabledText = rgb(74, 81, 98) // button.py _DISABLED_TEXT
    val RadioTextOff = rgb(150, 158, 176) // radio.py не выбрана
}

/** Набор шрифтов theme.py f_* (размеры pt -> sp). */
object Fonts {
    val Logo = TextStyle(fontSize = 25.sp, fontWeight = FontWeight.Bold) // FLogo 17
    val Caps = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Bold) // FCaps 8
    val Sub = TextStyle(fontSize = 12.sp) // FSub 8
    val Body = TextStyle(fontSize = 13.sp) // FBody 9
    val Head = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Bold) // FHead 9.5
    val Btn = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Bold) // FBtn 8.5
    val Mono = TextStyle(fontSize = 13.sp, fontFamily = FontFamily.Monospace) // FMono 8.5
}
