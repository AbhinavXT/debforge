package com.abhinavxt.debforge.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.abhinavxt.debforge.R

/**
 * Type scale: Inter (bundled, SIL OFL 1.1) for display, headline and title
 * styles only; body and label text stay on the system font so every script in
 * every language renders well. Inter has no glyphs for scripts such as
 * Devanagari or Arabic, so those fall back to the system font per character.
 */
private val Inter = FontFamily(
    Font(R.font.inter_semibold, FontWeight.SemiBold),
    Font(R.font.inter_bold, FontWeight.Bold)
)

private val base = Typography()

val AppTypography = Typography(
    displaySmall = base.displaySmall.copy(fontFamily = Inter, fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp),
    headlineLarge = base.headlineLarge.copy(fontFamily = Inter, fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp),
    headlineMedium = base.headlineMedium.copy(fontFamily = Inter, fontWeight = FontWeight.Bold, letterSpacing = (-0.25).sp),
    headlineSmall = base.headlineSmall.copy(fontFamily = Inter, fontWeight = FontWeight.SemiBold),
    titleLarge = base.titleLarge.copy(fontFamily = Inter, fontWeight = FontWeight.SemiBold),
    titleMedium = base.titleMedium.copy(fontFamily = Inter, fontWeight = FontWeight.SemiBold, letterSpacing = 0.1.sp),
    titleSmall = base.titleSmall.copy(fontFamily = Inter, fontWeight = FontWeight.SemiBold),
    bodyLarge = base.bodyLarge.copy(letterSpacing = 0.15.sp),
    bodyMedium = base.bodyMedium,
    bodySmall = base.bodySmall,
    labelLarge = base.labelLarge.copy(fontWeight = FontWeight.SemiBold),
    labelMedium = base.labelMedium.copy(fontWeight = FontWeight.SemiBold),
    labelSmall = base.labelSmall.copy(fontWeight = FontWeight.Medium, letterSpacing = 0.3.sp)
)

/** Generous rounding: extraSmall 8, small 12, medium 16, large 20 (cards), extraLarge 28 (sheets and dialogs). */
val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp)
)
