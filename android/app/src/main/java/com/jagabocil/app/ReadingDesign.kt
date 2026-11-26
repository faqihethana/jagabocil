package com.jagabocil.app

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal val Forest = Color(0xFF08645E)
internal val Cream = Color(0xFFFBF8F4)
internal val Mint = Color(0xFFCFEBDD)
internal val Muted = Color(0xFF535C57)
internal val Soft = Color(0xFFEAF4EE)
internal val Peach = Color(0xFFFFE4D4)
internal val Rose = Color(0xFFF7DFE7)
internal val Sky = Color(0xFFDDEDF7)
internal val Butter = Color(0xFFFBEBC4)
internal val Amber = Color(0xFF795019)
internal val Ink = Color(0xFF24312C)
private val Edge = Color(0xFFE3E2DA)

internal val AppColors = lightColorScheme(
    primary = Forest, onPrimary = Color.White, primaryContainer = Mint, onPrimaryContainer = Forest,
    secondary = Forest, secondaryContainer = Mint, onSecondaryContainer = Ink,
    tertiary = Amber, tertiaryContainer = Butter, onTertiaryContainer = Amber,
    background = Cream, onBackground = Ink, surface = Cream, onSurface = Ink,
    onSurfaceVariant = Muted, surfaceContainer = Soft, surfaceContainerLowest = Color.White,
    outline = Color(0xFF717A73), outlineVariant = Edge,
)

@OptIn(ExperimentalTextApi::class)
private val Outfit = FontFamily(
    Font(R.font.outfit, FontWeight.Normal, variationSettings = FontVariation.Settings(FontVariation.weight(400))),
    Font(R.font.outfit, FontWeight.Medium, variationSettings = FontVariation.Settings(FontVariation.weight(500))),
    Font(R.font.outfit, FontWeight.SemiBold, variationSettings = FontVariation.Settings(FontVariation.weight(600))),
    Font(R.font.outfit, FontWeight.Bold, variationSettings = FontVariation.Settings(FontVariation.weight(700))),
)

private val DefaultTypography = Typography()
internal val AppTypography = Typography(
    headlineLarge = DefaultTypography.headlineLarge.copy(
        fontFamily = Outfit, fontWeight = FontWeight.Bold, fontSize = 38.sp, lineHeight = 40.sp, letterSpacing = (-1.4).sp,
    ),
    headlineMedium = DefaultTypography.headlineMedium.copy(
        fontFamily = Outfit, fontWeight = FontWeight.Bold, lineHeight = 33.sp, letterSpacing = (-0.7).sp,
        lineBreak = LineBreak.Heading,
    ),
    headlineSmall = DefaultTypography.headlineSmall.copy(fontFamily = Outfit, fontWeight = FontWeight.SemiBold),
    titleLarge = DefaultTypography.titleLarge.copy(fontFamily = Outfit, fontWeight = FontWeight.SemiBold),
    titleMedium = DefaultTypography.titleMedium.copy(fontFamily = Outfit, fontWeight = FontWeight.SemiBold),
    titleSmall = DefaultTypography.titleSmall.copy(fontFamily = Outfit),
    bodyLarge = DefaultTypography.bodyLarge.copy(fontFamily = Outfit),
    bodyMedium = DefaultTypography.bodyMedium.copy(fontFamily = Outfit),
    bodySmall = DefaultTypography.bodySmall.copy(fontFamily = Outfit),
    labelLarge = DefaultTypography.labelLarge.copy(fontFamily = Outfit, fontWeight = FontWeight.SemiBold),
    labelMedium = DefaultTypography.labelMedium.copy(fontFamily = Outfit),
    labelSmall = DefaultTypography.labelSmall.copy(fontFamily = Outfit),
)

@Composable internal fun FamilyIllustration(modifier: Modifier = Modifier) {
    Image(painterResource(R.drawable.family_reading), contentDescription = null, modifier = modifier)
}

@Composable internal fun ReadingBanner(
    label: String?,
    title: String,
    detail: String,
    accent: Color = Mint,
    icon: ImageVector = Icons.AutoMirrored.Outlined.MenuBook,
    illustrated: Boolean = false,
) {
    Surface(color = accent, shape = RoundedCornerShape(26.dp), contentColor = Ink) {
        BoxWithConstraints(Modifier.fillMaxWidth().padding(20.dp)) {
            val showArt = maxWidth >= 248.dp
            val illustrationWidth = (maxWidth * 0.34f).coerceAtMost(140.dp)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    if (label != null) Text(label, color = Forest, style = MaterialTheme.typography.labelLarge)
                    Text(title, Modifier.semantics { heading() }, style = MaterialTheme.typography.headlineMedium)
                    Text(detail, color = Muted, style = MaterialTheme.typography.bodyMedium)
                }
                if (showArt) {
                    if (illustrated) {
                        FamilyIllustration(Modifier.width(illustrationWidth).aspectRatio(1.15f))
                    } else {
                        IconTile(icon, Color.White.copy(alpha = 0.64f), Modifier.size(58.dp))
                    }
                }
            }
        }
    }
}

@Composable internal fun SectionTitle(text: String) {
    Text(text, Modifier.semantics { heading() }, color = Ink, style = MaterialTheme.typography.titleLarge)
}

@Composable internal fun IconTile(icon: ImageVector, color: Color, modifier: Modifier = Modifier.size(44.dp)) {
    Box(modifier.background(color, CircleShape), contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = Forest, modifier = Modifier.size(24.dp))
    }
}

@Composable internal fun TaskLink(
    title: String, detail: String, icon: ImageVector, accent: Color = Soft, enabled: Boolean = true, onClick: () -> Unit,
) {
    Surface(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth(), color = accent,
        contentColor = Ink, shape = RoundedCornerShape(20.dp)) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            IconTile(icon, Color.White.copy(alpha = 0.75f))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(detail, color = Muted, style = MaterialTheme.typography.bodyMedium)
            }
            Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null, tint = Forest)
        }
    }
}

@Composable internal fun DashboardLink(
    title: String,
    detail: String,
    icon: ImageVector,
    accent: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Surface(onClick = onClick, modifier = modifier, color = accent, contentColor = Ink, shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            IconTile(icon, Color.White.copy(alpha = 0.74f), Modifier.size(40.dp))
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(detail, color = Muted, style = MaterialTheme.typography.bodySmall)
        }
    }
}
