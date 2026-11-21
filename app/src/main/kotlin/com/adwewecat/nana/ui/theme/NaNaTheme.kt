package com.adwewecat.nana.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** Semantic accent tokens for Na Na's dark-first OriginOS 6 companion aesthetic. */
object NaNaPalette {
    val NightVoid = Color(0xFF0E0B16)
    val DeepPlum = Color(0xFF171224)
    val CardSurface = Color(0xFF211A33)
    val BubbleSlate = Color(0xFF2B2242)
    val SakuraPink = Color(0xFFFF8FB8)
    val SakuraContainer = Color(0xFF591B38)
    val LavenderViolet = Color(0xFFC6A4FF)
    val LavenderContainer = Color(0xFF3E266B)
    val MintHealthy = Color(0xFF7EE7C1)
    val MintContainer = Color(0xFF0D4E3B)
    val AmberCooldown = Color(0xFFFFCA7A)
    val AmberContainer = Color(0xFF523910)
    val CoralError = Color(0xFFFF8B94)
    val CoralContainer = Color(0xFF5E1725)
    val TextPrimary = Color(0xFFF7F1FC)
    val TextSecondary = Color(0xFFC7BCD9)
    val SubtleOutline = Color(0xFF473D63)
}

private val NaNaDarkColorScheme = darkColorScheme(
    primary = NaNaPalette.SakuraPink,
    onPrimary = Color(0xFF3D0620),
    primaryContainer = NaNaPalette.SakuraContainer,
    onPrimaryContainer = Color(0xFFFFD9E6),
    secondary = NaNaPalette.LavenderViolet,
    onSecondary = Color(0xFF2B1258),
    secondaryContainer = NaNaPalette.LavenderContainer,
    onSecondaryContainer = Color(0xFFECDCFF),
    tertiary = NaNaPalette.MintHealthy,
    onTertiary = Color(0xFF003828),
    tertiaryContainer = NaNaPalette.MintContainer,
    onTertiaryContainer = Color(0xFFA9F7D9),
    background = NaNaPalette.NightVoid,
    onBackground = NaNaPalette.TextPrimary,
    surface = NaNaPalette.DeepPlum,
    onSurface = NaNaPalette.TextPrimary,
    surfaceVariant = NaNaPalette.CardSurface,
    onSurfaceVariant = NaNaPalette.TextSecondary,
    surfaceContainer = NaNaPalette.CardSurface,
    surfaceContainerHigh = NaNaPalette.BubbleSlate,
    outline = NaNaPalette.SubtleOutline,
    error = NaNaPalette.CoralError,
    onError = Color(0xFF4A0712),
    errorContainer = NaNaPalette.CoralContainer,
    onErrorContainer = Color(0xFFFFDAD9),
)

private val NaNaTypography = Typography(
    headlineSmall = TextStyle(
        fontSize = 22.sp,
        lineHeight = 28.sp,
        fontWeight = FontWeight.Bold,
    ),
    titleMedium = TextStyle(
        fontSize = 16.sp,
        lineHeight = 22.sp,
        fontWeight = FontWeight.SemiBold,
    ),
    bodyMedium = TextStyle(
        fontSize = 14.sp,
        lineHeight = 20.sp,
        fontWeight = FontWeight.Normal,
    ),
    labelMedium = TextStyle(
        fontSize = 12.sp,
        lineHeight = 16.sp,
        fontWeight = FontWeight.Medium,
    ),
)

/** Dark-first Material 3 theme tailored for Na Na on OriginOS 6. */
@Composable
fun NaNaTheme(
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = NaNaDarkColorScheme,
        typography = NaNaTypography,
        content = content,
    )
}
