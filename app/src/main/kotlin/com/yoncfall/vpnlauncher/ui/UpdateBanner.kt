// Плашка обновления (2.1.0): компактная полоса между TitleBar и карточкой.
// Стиль - упрощённый GameCard: заливка цветом карточки, рамка акцентом.
package com.yoncfall.vpnlauncher.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

@Composable
fun UpdateBanner(
    version: String,
    progress: Int,
    onUpdate: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val radius = 10.dp
    Row(
        modifier
            .padding(start = 14.dp, end = 14.dp, top = 4.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(radius))
            .drawBehind {
                val r = radius.toPx()
                val s = 1.dp.toPx()
                drawRoundRect(
                    rgb(27, 31, 42),
                    topLeft = Offset(s, s),
                    size = Size(size.width - 2 * s, size.height - 2 * s),
                    cornerRadius = CornerRadius(r),
                )
                drawRoundRect(
                    Theme.Accent.copy(alpha = 0.55f),
                    topLeft = Offset(s, s),
                    size = Size(size.width - 2 * s, size.height - 2 * s),
                    cornerRadius = CornerRadius(r),
                    style = Stroke(s),
                )
            }
            .padding(horizontal = 10.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        BasicText(
            if (progress >= 0) "Скачивание обновления: $progress%" else "Доступна версия $version",
            style = Fonts.Sub.copy(color = Theme.Text),
            modifier = Modifier.weight(1f),
        )
        GameButton("Позже", ButtonKind.GHOST, height = 30.dp, radius = 6, onClick = onDismiss)
        GameButton(
            if (progress >= 0) "${progress}%" else "Обновить",
            ButtonKind.ACCENT,
            height = 30.dp,
            radius = 6,
            enabled = progress < 0,
            onClick = onUpdate,
        )
    }
}
