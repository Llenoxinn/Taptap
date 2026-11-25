package io.github.llenoxinn.taptap.android.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.llenoxinn.taptap.android.R
import io.github.llenoxinn.taptap.mesh.Priority

data class TaptapColors(
    val bg: Color,
    val surface: Color,
    val text: Color,
    val textMuted: Color,
    val glow: Color,
    val critical: Color,
    val urgent: Color,
    val info: Color,
    val ok: Color,
    val isDark: Boolean,
)

val DarkTaptapColors = TaptapColors(
    bg = Color(0xFF0E1116),
    surface = Color(0xFF1A1F27),
    text = Color(0xFFF2F4F7),
    textMuted = Color(0xFFA0A8B5),
    glow = Color(0xFFE8D44D),
    critical = Color(0xFFE5484D),
    urgent = Color(0xFFF5A524),
    info = Color(0xFF3E9BFF),
    ok = Color(0xFF30A46C),
    isDark = true,
)

val LightTaptapColors = TaptapColors(
    bg = Color(0xFFF7F9FC),
    surface = Color(0xFFFFFFFF),
    text = Color(0xFF0E1116),
    textMuted = Color(0xFF475569),
    glow = Color(0xFF8A6D00),
    critical = Color(0xFFC62828),
    urgent = Color(0xFFB45309),
    info = Color(0xFF0B61C2),
    ok = Color(0xFF16794B),
    isDark = false,
)

val LocalTaptapColors = staticCompositionLocalOf { DarkTaptapColors }

private val TaptapTypography = Typography(
    headlineMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 26.sp,
        lineHeight = 32.sp,
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 22.sp,
        lineHeight = 28.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 18.sp,
        lineHeight = 25.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 18.sp,
        lineHeight = 24.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 18.sp,
        lineHeight = 22.sp,
    ),
)

@Composable
fun TaptapTheme(darkTheme: Boolean, content: @Composable () -> Unit) {
    val palette = if (darkTheme) DarkTaptapColors else LightTaptapColors
    val scheme = if (darkTheme) {
        darkColorScheme(
            background = palette.bg,
            surface = palette.surface,
            onBackground = palette.text,
            onSurface = palette.text,
            primary = palette.glow,
            onPrimary = Color(0xFF0E1116),
            error = palette.critical,
        )
    } else {
        lightColorScheme(
            background = palette.bg,
            surface = palette.surface,
            onBackground = palette.text,
            onSurface = palette.text,
            primary = palette.info,
            onPrimary = Color.White,
            error = palette.critical,
        )
    }
    CompositionLocalProvider(LocalTaptapColors provides palette) {
        MaterialTheme(
            colorScheme = scheme,
            typography = TaptapTypography,
            content = content,
        )
    }
}

fun priorityColor(priority: Priority, colors: TaptapColors): Color = when (priority) {
    Priority.CRITICAL -> colors.critical
    Priority.URGENT -> colors.urgent
    Priority.INFO -> colors.info
}

/** Shapes match DESIGN.md §5: critical = circle, urgent = triangle, info = square. Never color alone. */
@Composable
fun PriorityShapeIcon(priority: Priority, tint: Color, modifier: Modifier = Modifier.size(18.dp)) {
    Canvas(modifier = modifier) {
        when (priority) {
            Priority.CRITICAL -> drawCircle(color = tint, style = Fill)
            Priority.URGENT -> {
                val path = Path().apply {
                    moveTo(size.width / 2f, 0f)
                    lineTo(size.width, size.height)
                    lineTo(0f, size.height)
                    close()
                }
                drawPath(path, color = tint, style = Fill)
            }
            Priority.INFO -> drawRect(color = tint, size = Size(size.width, size.height))
        }
    }
}

@Composable
fun TestWarningBanner(textResId: Int, modifier: Modifier = Modifier) {
    val colors = LocalTaptapColors.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(colors.surface, RoundedCornerShape(12.dp))
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        androidx.compose.material3.Text(
            text = "⚠",
            color = colors.glow,
            fontSize = 20.sp,
        )
        androidx.compose.material3.Text(
            text = stringResource(textResId),
            color = colors.text,
            fontSize = 16.sp,
            lineHeight = 22.sp,
        )
    }
}

/** Mascot used in onboarding only — never on Home or SOS screens (DESIGN.md §2). */
@Composable
fun FireflyMascot(modifier: Modifier = Modifier.size(96.dp)) {
    val colors = LocalTaptapColors.current
    val desc = stringResource(R.string.cd_firefly_mascot)
    Box(
        modifier = modifier.semantics { contentDescription = desc },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.matchParentSize()) {
            val w = size.width
            val h = size.height
            drawCircle(
                color = colors.glow.copy(alpha = 0.22f),
                radius = w * 0.34f,
                center = Offset(w * 0.5f, h * 0.64f),
            )
            // Wings
            drawOval(
                color = colors.textMuted.copy(alpha = 0.65f),
                topLeft = Offset(w * 0.18f, h * 0.34f),
                size = Size(w * 0.28f, h * 0.22f),
            )
            drawOval(
                color = colors.textMuted.copy(alpha = 0.65f),
                topLeft = Offset(w * 0.54f, h * 0.34f),
                size = Size(w * 0.28f, h * 0.22f),
            )
            // Head & glowing abdomen
            drawCircle(
                color = colors.text,
                radius = w * 0.11f,
                center = Offset(w * 0.5f, h * 0.25f),
            )
            drawOval(
                color = colors.glow,
                topLeft = Offset(w * 0.38f, h * 0.44f),
                size = Size(w * 0.24f, h * 0.34f),
            )
        }
    }
}
