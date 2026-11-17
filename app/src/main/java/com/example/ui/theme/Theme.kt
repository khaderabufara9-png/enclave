package com.example.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

private val EnclaveDarkColorScheme = darkColorScheme(
    primary = EnclaveCyan,
    onPrimary = ObsidianBg,
    primaryContainer = EnclaveCyanContainerDark,
    onPrimaryContainer = Color(0xFF9AFDF0),
    secondary = EnclaveIndigo,
    onSecondary = Color.White,
    secondaryContainer = EnclaveIndigoContainerDark,
    onSecondaryContainer = Color(0xFFD6D8FF),
    tertiary = EnclaveAmber,
    onTertiary = ObsidianBg,
    tertiaryContainer = EnclaveAmberContainerDark,
    onTertiaryContainer = Color(0xFFFDE68A),
    error = EnclaveRed,
    onError = Color.White,
    errorContainer = EnclaveRedContainerDark,
    onErrorContainer = Color(0xFFFECACA),
    background = ObsidianBg,
    onBackground = ObsidianTextPrimary,
    surface = ObsidianSurface,
    onSurface = ObsidianTextPrimary,
    surfaceVariant = ObsidianSurfaceVariant,
    onSurfaceVariant = ObsidianTextSecondary,
    surfaceContainerLow = ObsidianSurface,
    surfaceContainer = ObsidianSurfaceVariant,
    surfaceContainerHigh = ObsidianSurfaceElevated,
    outline = ObsidianOutline,
    outlineVariant = Color(0xFF1F2A3E)
)

private val EnclaveLightColorScheme = lightColorScheme(
    primary = EnclaveCyanDark,
    onPrimary = Color.White,
    primaryContainer = EnclaveCyanContainerLight,
    onPrimaryContainer = Color(0xFF003831),
    secondary = EnclaveIndigo,
    onSecondary = Color.White,
    secondaryContainer = EnclaveIndigoContainerLight,
    onSecondaryContainer = Color(0xFF1E1F54),
    tertiary = Color(0xFFD97706),
    onTertiary = Color.White,
    tertiaryContainer = EnclaveAmberContainerLight,
    onTertiaryContainer = Color(0xFF451A03),
    error = Color(0xFFDC2626),
    onError = Color.White,
    errorContainer = EnclaveRedContainerLight,
    onErrorContainer = Color(0xFF7F1D1D),
    background = AlabasterBg,
    onBackground = AlabasterTextPrimary,
    surface = AlabasterSurface,
    onSurface = AlabasterTextPrimary,
    surfaceVariant = AlabasterSurfaceVariant,
    onSurfaceVariant = AlabasterTextSecondary,
    surfaceContainerLow = AlabasterSurface,
    surfaceContainer = AlabasterSurfaceVariant,
    surfaceContainerHigh = Color(0xFFDCE5EF),
    outline = AlabasterOutline,
    outlineVariant = Color(0xFFE2E8F0)
)

val EnclaveShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

enum class ThemePreference { SYSTEM, DARK, LIGHT }

@Composable
fun EnclaveTheme(
    themePreference: ThemePreference = ThemePreference.SYSTEM,
    dynamicColor: Boolean = false,
    accentOverride: Color? = null,
    content: @Composable () -> Unit,
) {
    val systemDark = isSystemInDarkTheme()
    val useDark = when (themePreference) {
        ThemePreference.SYSTEM -> systemDark
        ThemePreference.DARK -> true
        ThemePreference.LIGHT -> false
    }

    val baseScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (useDark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        useDark -> EnclaveDarkColorScheme
        else -> EnclaveLightColorScheme
    }

    val finalScheme = if (accentOverride != null) {
        baseScheme.copy(primary = accentOverride)
    } else {
        baseScheme
    }

    MaterialTheme(
        colorScheme = finalScheme,
        typography = EnclaveTypography,
        shapes = EnclaveShapes,
        content = content
    )
}
