package app.onebook.autoledger.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle

@Immutable
data class LedgerColors(
    val paper: Color,
    val surface: Color,
    val ink: Color,
    val muted: Color,
    val line: Color,
    val accent: Color,
    val accentSoft: Color,
    val danger: Color,
    val dangerSoft: Color,
    val warn: Color,
    val warnSoft: Color,
    val moneyIn: Color,
    val moneyOut: Color,
    val ok: Color,
    val okSoft: Color,
    val isDark: Boolean,
)

val LightLedgerColors = LedgerColors(
    paper = Color(0xFFF3F5F8),
    surface = Color(0xFFFFFFFF),
    ink = Color(0xFF0E1A2E),
    muted = Color(0xFF586579),
    line = Color(0xFFDCE2EA),
    accent = Color(0xFF2447D1),
    accentSoft = Color(0xFFE6EBFC),
    danger = Color(0xFFB42318),
    dangerSoft = Color(0xFFFDECEA),
    warn = Color(0xFF8A5300),
    warnSoft = Color(0xFFFFF3DC),
    moneyIn = Color(0xFF0F6B3F),
    moneyOut = Color(0xFFA3203D),
    ok = Color(0xFF0F6B3F),
    okSoft = Color(0xFFE3F5EA),
    isDark = false,
)

val DarkLedgerColors = LedgerColors(
    paper = Color(0xFF0B111D),
    surface = Color(0xFF141C2C),
    ink = Color(0xFFEDF1F8),
    muted = Color(0xFF97A5BA),
    line = Color(0xFF243049),
    accent = Color(0xFF8EA6FF),
    accentSoft = Color(0xFF1B2850),
    danger = Color(0xFFF97066),
    dangerSoft = Color(0xFF3A1714),
    warn = Color(0xFFF5B547),
    warnSoft = Color(0xFF33260E),
    moneyIn = Color(0xFF4ADE80),
    moneyOut = Color(0xFFFF8FA3),
    ok = Color(0xFF4ADE80),
    okSoft = Color(0xFF10301F),
    isDark = true,
)

val LocalLedgerColors = staticCompositionLocalOf { LightLedgerColors }

object LedgerTheme {
    val colors: LedgerColors
        @Composable
        @ReadOnlyComposable
        get() = LocalLedgerColors.current
}

val TextStyle.tnum: TextStyle
    get() = copy(fontFeatureSettings = "tnum")

private val LightMaterialScheme = lightColorScheme(
    primary = LightLedgerColors.accent,
    onPrimary = Color.White,
    primaryContainer = LightLedgerColors.accentSoft,
    onPrimaryContainer = LightLedgerColors.accent,
    background = LightLedgerColors.paper,
    onBackground = LightLedgerColors.ink,
    surface = LightLedgerColors.surface,
    onSurface = LightLedgerColors.ink,
    surfaceVariant = LightLedgerColors.paper,
    onSurfaceVariant = LightLedgerColors.muted,
    outline = LightLedgerColors.line,
    outlineVariant = LightLedgerColors.line,
    error = LightLedgerColors.danger,
    onError = Color.White,
    errorContainer = LightLedgerColors.dangerSoft,
    onErrorContainer = LightLedgerColors.danger,
)

private val DarkMaterialScheme = darkColorScheme(
    primary = DarkLedgerColors.accent,
    onPrimary = Color(0xFF0B111D),
    primaryContainer = DarkLedgerColors.accentSoft,
    onPrimaryContainer = DarkLedgerColors.accent,
    background = DarkLedgerColors.paper,
    onBackground = DarkLedgerColors.ink,
    surface = DarkLedgerColors.surface,
    onSurface = DarkLedgerColors.ink,
    surfaceVariant = DarkLedgerColors.paper,
    onSurfaceVariant = DarkLedgerColors.muted,
    outline = DarkLedgerColors.line,
    outlineVariant = DarkLedgerColors.line,
    error = DarkLedgerColors.danger,
    onError = Color(0xFF0B111D),
    errorContainer = DarkLedgerColors.dangerSoft,
    onErrorContainer = DarkLedgerColors.danger,
)

@Composable
fun AutoLedgerTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val ledgerColors = if (darkTheme) DarkLedgerColors else LightLedgerColors
    val materialColors = if (darkTheme) DarkMaterialScheme else LightMaterialScheme
    CompositionLocalProvider(LocalLedgerColors provides ledgerColors) {
        MaterialTheme(
            colorScheme = materialColors,
            typography = Typography(),
            content = content,
        )
    }
}
