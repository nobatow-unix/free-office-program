package com.icqnew.app.presentation.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp

val IcqGreen = Color(0xFF00CB66)

/** Accent colours the user can choose in Settings -> Appearance. */
val Accents: List<Pair<String, Color>> = listOf(
    "Зелёный ICQ" to IcqGreen,
    "Синий" to Color(0xFF2F80ED),
    "Фиолетовый" to Color(0xFF8B5CF6),
    "Оранжевый" to Color(0xFFFF8A00),
    "Красный" to Color(0xFFEF4444),
    "Бирюзовый" to Color(0xFF00A9B5),
    "Розовый" to Color(0xFFEC4899),
)

private fun lightScheme(accent: Color): ColorScheme {
    val container = lerp(Color.White, accent, 0.16f)
    return lightColorScheme(
        primary = accent,
        onPrimary = Color.White,
        primaryContainer = container,
        onPrimaryContainer = lerp(accent, Color.Black, 0.55f),
        secondary = accent,
        onSecondary = Color.White,
        secondaryContainer = container,
        onSecondaryContainer = lerp(accent, Color.Black, 0.55f),
        tertiary = accent,
        onTertiary = Color.White,
        background = Color(0xFFFFFFFF),
        onBackground = Color(0xFF16181A),
        surface = Color(0xFFFFFFFF),
        onSurface = Color(0xFF16181A),
        surfaceVariant = Color(0xFFF1F3F4),
        onSurfaceVariant = Color(0xFF666B70),
        surfaceTint = Color.Transparent,
        surfaceBright = Color(0xFFFFFFFF),
        surfaceDim = Color(0xFFE6E8EA),
        surfaceContainerLowest = Color(0xFFFFFFFF),
        surfaceContainerLow = Color(0xFFF8F9FA),
        surfaceContainer = Color(0xFFF3F5F6),
        surfaceContainerHigh = Color(0xFFEDEFF1),
        surfaceContainerHighest = Color(0xFFE7E9EB),
        inverseSurface = Color(0xFF2B2E31),
        inverseOnSurface = Color(0xFFF1F3F4),
        inversePrimary = lerp(accent, Color.White, 0.4f),
        outline = Color(0xFFD5D8DC),
        outlineVariant = Color(0xFFE8EAED),
        error = Color(0xFFE53935),
        onError = Color.White,
        errorContainer = Color(0xFFFDE2E1),
        onErrorContainer = Color(0xFF5F0E0B),
        scrim = Color.Black,
    )
}

private fun darkScheme(accent: Color): ColorScheme {
    val container = lerp(Color(0xFF121314), accent, 0.30f)
    return darkColorScheme(
        primary = accent,
        onPrimary = Color.White,
        primaryContainer = container,
        onPrimaryContainer = lerp(accent, Color.White, 0.75f),
        secondary = accent,
        onSecondary = Color.White,
        secondaryContainer = container,
        onSecondaryContainer = lerp(accent, Color.White, 0.75f),
        tertiary = accent,
        onTertiary = Color.White,
        background = Color(0xFF0F1011),
        onBackground = Color(0xFFECEDEE),
        surface = Color(0xFF0F1011),
        onSurface = Color(0xFFECEDEE),
        surfaceVariant = Color(0xFF222427),
        onSurfaceVariant = Color(0xFFA2A7AC),
        surfaceTint = Color.Transparent,
        surfaceBright = Color(0xFF2C2E31),
        surfaceDim = Color(0xFF0F1011),
        surfaceContainerLowest = Color(0xFF0A0B0C),
        surfaceContainerLow = Color(0xFF16181A),
        surfaceContainer = Color(0xFF1B1D1F),
        surfaceContainerHigh = Color(0xFF222427),
        surfaceContainerHighest = Color(0xFF2A2C2F),
        inverseSurface = Color(0xFFECEDEE),
        inverseOnSurface = Color(0xFF1B1D1F),
        inversePrimary = lerp(accent, Color.Black, 0.3f),
        outline = Color(0xFF3A3D40),
        outlineVariant = Color(0xFF26282B),
        error = Color(0xFFFF6B6B),
        onError = Color.Black,
        errorContainer = Color(0xFF5F1412),
        onErrorContainer = Color(0xFFFFDAD6),
        scrim = Color.Black,
    )
}

private val IcqShapes = Shapes(
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(20.dp),
)

/** Rubik (SIL OFL): a round, friendly font with Cyrillic; one variable file for all weights. */
@OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)
private fun rubik(w: Int) = androidx.compose.ui.text.font.Font(
    com.icqnew.app.R.font.rubik,
    weight = androidx.compose.ui.text.font.FontWeight(w),
    variationSettings = androidx.compose.ui.text.font.FontVariation.Settings(androidx.compose.ui.text.font.FontVariation.weight(w)),
)

val Rubik = androidx.compose.ui.text.font.FontFamily(rubik(300), rubik(400), rubik(500), rubik(600), rubik(700), rubik(800))

/** Big titles get a soft drop shadow, so the letters look slightly raised ("3D"). */
private val Raised = androidx.compose.ui.graphics.Shadow(
    color = androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.28f),
    offset = androidx.compose.ui.geometry.Offset(0f, 2.5f),
    blurRadius = 4f,
)

val IcqTypography: androidx.compose.material3.Typography = androidx.compose.material3.Typography().let { t ->
    fun androidx.compose.ui.text.TextStyle.r() = copy(fontFamily = Rubik)
    fun androidx.compose.ui.text.TextStyle.r3d() = copy(fontFamily = Rubik, shadow = Raised)
    t.copy(
        displayLarge = t.displayLarge.r3d(), displayMedium = t.displayMedium.r3d(), displaySmall = t.displaySmall.r3d(),
        headlineLarge = t.headlineLarge.r3d(), headlineMedium = t.headlineMedium.r3d(), headlineSmall = t.headlineSmall.r3d(),
        titleLarge = t.titleLarge.r3d().copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold),
        titleMedium = t.titleMedium.r(), titleSmall = t.titleSmall.r(),
        bodyLarge = t.bodyLarge.r(), bodyMedium = t.bodyMedium.r(), bodySmall = t.bodySmall.r(),
        labelLarge = t.labelLarge.r(), labelMedium = t.labelMedium.r(), labelSmall = t.labelSmall.r(),
    )
}

@Composable
fun IcqTheme(
    dark: Boolean = isSystemInDarkTheme(),
    accent: Int = 0,
    fontScale: Float = 1f,
    content: @Composable () -> Unit,
) {
    val color = Accents.getOrNull(accent)?.second ?: IcqGreen
    val base = LocalDensity.current
    CompositionLocalProvider(LocalDensity provides Density(base.density, base.fontScale * fontScale)) {
        MaterialTheme(
            colorScheme = if (dark) darkScheme(color) else lightScheme(color),
            shapes = IcqShapes,
            typography = IcqTypography,
            content = content,
        )
    }
}
