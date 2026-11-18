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
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.onebook.autoledger.R

val PlusJakartaFontFamily = FontFamily(
    Font(R.font.plus_jakarta_sans, FontWeight.Normal),
    Font(R.font.plus_jakarta_sans, FontWeight.Medium),
    Font(R.font.plus_jakarta_sans, FontWeight.SemiBold),
    Font(R.font.plus_jakarta_sans, FontWeight.Bold),
)

val JetBrainsMonoFontFamily = FontFamily(
    Font(R.font.jetbrains_mono, FontWeight.Normal),
    Font(R.font.jetbrains_mono, FontWeight.Medium),
    Font(R.font.jetbrains_mono, FontWeight.SemiBold),
    Font(R.font.jetbrains_mono, FontWeight.Bold),
)

@Immutable
data class LedgerColors(
    val paper: Color,
    val surface: Color,
    val surfaceSubtle: Color,
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
    val moneyInSoft: Color,
    val moneyOut: Color,
    val moneyOutSoft: Color,
    val ok: Color,
    val okSoft: Color,
    val heroBg: Color,
    val heroInk: Color,
    val heroMuted: Color,
    val heroAccent: Color,
    val heroGlow: Color,
    val ageingBuckets: List<Color>,
    val isDark: Boolean,
)

val LightLedgerColors = LedgerColors(
    paper = Color(0xFFF3F6FA),
    surface = Color(0xFFFFFFFF),
    surfaceSubtle = Color(0xFFF8FAFC),
    ink = Color(0xFF0F172A),
    muted = Color(0xFF526075),
    line = Color(0xFFDCE3EE),
    accent = Color(0xFF2D46B9),
    accentSoft = Color(0xFFE8EDFC),
    danger = Color(0xFFB42318),
    dangerSoft = Color(0xFFFDECEA),
    warn = Color(0xFF9A5B00),
    warnSoft = Color(0xFFFEF3DC),
    moneyIn = Color(0xFF0D7A56),
    moneyInSoft = Color(0xFFE2F6EE),
    moneyOut = Color(0xFFC22E4A),
    moneyOutSoft = Color(0xFFFDECEF),
    ok = Color(0xFF0D7A56),
    okSoft = Color(0xFFE2F6EE),
    heroBg = Color(0xFF152042),
    heroInk = Color(0xFFF8FAFC),
    heroMuted = Color(0xFFA5B4D4),
    heroAccent = Color(0xFF819BFF),
    heroGlow = Color(0xFF3955D8),
    ageingBuckets = listOf(
        Color(0xFF2D46B9),
        Color(0xFF0E8A7D),
        Color(0xFFD97706),
        Color(0xFFEA580C),
        Color(0xFFC22E4A),
    ),
    isDark = false,
)

val DarkLedgerColors = LedgerColors(
    paper = Color(0xFF090F1C),
    surface = Color(0xFF111A2E),
    surfaceSubtle = Color(0xFF17223B),
    ink = Color(0xFFF1F5F9),
    muted = Color(0xFF94A3B8),
    line = Color(0xFF23324F),
    accent = Color(0xFF7E97FF),
    accentSoft = Color(0xFF1C2A52),
    danger = Color(0xFFF87171),
    dangerSoft = Color(0xFF3B1518),
    warn = Color(0xFFFBBF24),
    warnSoft = Color(0xFF33250B),
    moneyIn = Color(0xFF34D399),
    moneyInSoft = Color(0xFF0F2E25),
    moneyOut = Color(0xFFFB7185),
    moneyOutSoft = Color(0xFF371622),
    ok = Color(0xFF34D399),
    okSoft = Color(0xFF0F2E25),
    heroBg = Color(0xFF162244),
    heroInk = Color(0xFFF8FAFC),
    heroMuted = Color(0xFF9FB0D3),
    heroAccent = Color(0xFF93AAFF),
    heroGlow = Color(0xFF4763E4),
    ageingBuckets = listOf(
        Color(0xFF7E97FF),
        Color(0xFF2DD4BF),
        Color(0xFFFBBF24),
        Color(0xFFFB923C),
        Color(0xFFFB7185),
    ),
    isDark = true,
)

val LocalLedgerColors = staticCompositionLocalOf { LightLedgerColors }
val LocalBottomBarClearance = staticCompositionLocalOf { 0.dp }

object LedgerTheme {
    val colors: LedgerColors
        @Composable
        @ReadOnlyComposable
        get() = LocalLedgerColors.current
}

val TextStyle.tnum: TextStyle
    get() = copy(fontFeatureSettings = "tnum")

val TextStyle.monoTnum: TextStyle
    get() = copy(fontFamily = JetBrainsMonoFontFamily, fontFeatureSettings = "tnum")

private val AppTypography = Typography(
    displayLarge = TextStyle(
        fontFamily = PlusJakartaFontFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 36.sp,
        lineHeight = 42.sp,
        letterSpacing = (-0.5).sp,
    ),
    headlineLarge = TextStyle(
        fontFamily = PlusJakartaFontFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 30.sp,
        lineHeight = 36.sp,
        letterSpacing = (-0.4).sp,
    ),
    headlineMedium = TextStyle(
        fontFamily = PlusJakartaFontFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 24.sp,
        lineHeight = 30.sp,
        letterSpacing = (-0.25).sp,
    ),
    titleLarge = TextStyle(
        fontFamily = PlusJakartaFontFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 20.sp,
        lineHeight = 26.sp,
        letterSpacing = (-0.15).sp,
    ),
    titleMedium = TextStyle(
        fontFamily = PlusJakartaFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 22.sp,
    ),
    titleSmall = TextStyle(
        fontFamily = PlusJakartaFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = PlusJakartaFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 15.5.sp,
        lineHeight = 22.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = PlusJakartaFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
    bodySmall = TextStyle(
        fontFamily = PlusJakartaFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 12.5.sp,
        lineHeight = 17.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = PlusJakartaFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = PlusJakartaFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = PlusJakartaFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 14.sp,
    ),
)

private val LightMaterialScheme = lightColorScheme(
    primary = LightLedgerColors.accent,
    onPrimary = Color.White,
    primaryContainer = LightLedgerColors.accentSoft,
    onPrimaryContainer = LightLedgerColors.accent,
    background = LightLedgerColors.paper,
    onBackground = LightLedgerColors.ink,
    surface = LightLedgerColors.surface,
    onSurface = LightLedgerColors.ink,
    surfaceVariant = LightLedgerColors.surfaceSubtle,
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
    onPrimary = Color(0xFF090F1C),
    primaryContainer = DarkLedgerColors.accentSoft,
    onPrimaryContainer = DarkLedgerColors.accent,
    background = DarkLedgerColors.paper,
    onBackground = DarkLedgerColors.ink,
    surface = DarkLedgerColors.surface,
    onSurface = DarkLedgerColors.ink,
    surfaceVariant = DarkLedgerColors.surfaceSubtle,
    onSurfaceVariant = DarkLedgerColors.muted,
    outline = DarkLedgerColors.line,
    outlineVariant = DarkLedgerColors.line,
    error = DarkLedgerColors.danger,
    onError = Color(0xFF090F1C),
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
            typography = AppTypography,
            content = content,
        )
    }
}
