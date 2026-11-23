package com.jagabocil.app

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal val Forest = Color(0xFF164B40)
internal val Cream = Color(0xFFFAF9F4)
internal val Lime = Color(0xFFD9ECA6)
internal val Muted = Color(0xFF51665D)
internal val Soft = Color(0xFFEBF1E8)
internal val Peach = Color(0xFFFADDC2)
internal val Amber = Color(0xFF80501D)
private val Edge = Color(0xFFD9E1D7)

internal val AppColors = lightColorScheme(
    primary = Forest, onPrimary = Color.White, primaryContainer = Soft, onPrimaryContainer = Forest,
    secondary = Muted, secondaryContainer = Lime, onSecondaryContainer = Forest,
    tertiaryContainer = Peach, onTertiaryContainer = Amber, background = Cream, onBackground = Forest,
    surface = Cream, onSurface = Forest, onSurfaceVariant = Muted, surfaceContainer = Soft,
    surfaceContainerLowest = Color.White, outline = Muted, outlineVariant = Edge,
)

// A small open book under a sunlit arch, drawn in the same palette at any screen density.
@Composable private fun BookIllustration(modifier: Modifier) {
    Canvas(modifier) {
        scale(size.width / 100f, size.height / 100f, pivot = Offset.Zero) {
            val arch = Path().apply {
                moveTo(15f, 85f); lineTo(15f, 43f)
                cubicTo(15f, -3f, 85f, -3f, 85f, 43f)
                lineTo(85f, 85f); close()
            }
            drawPath(arch, Color(0xFF407362))
            drawCircle(Peach, 12f, Offset(62f, 34f))
            val left = Path().apply {
                moveTo(6f, 53f); quadraticTo(26f, 47f, 50f, 67f)
                lineTo(50f, 89f); quadraticTo(29f, 74f, 6f, 77f); close()
            }
            val right = Path().apply {
                moveTo(50f, 67f); quadraticTo(74f, 47f, 94f, 53f)
                lineTo(94f, 77f); quadraticTo(71f, 74f, 50f, 89f); close()
            }
            drawPath(left, Lime); drawPath(right, Cream)
            for (y in listOf(60f, 66f)) {
                val lines = Path().apply {
                    moveTo(12f, y); quadraticTo(30f, y - 1f, 44f, y + 10f)
                    moveTo(56f, y + 10f); quadraticTo(71f, y - 1f, 88f, y)
                }
                drawPath(lines, Forest.copy(alpha = 0.45f), style = Stroke(1.2f))
            }
        }
    }
}

@Composable internal fun ReadingBanner(label: String?, title: String, detail: String) {
    Surface(color = Forest, shape = RoundedCornerShape(24.dp)) {
        BoxWithConstraints(Modifier.fillMaxWidth().padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (label != null) Text(label, color = Lime, style = MaterialTheme.typography.labelLarge)
                    Text(title, Modifier.semantics { heading() }, color = Color.White, fontFamily = FontFamily.Serif,
                        fontSize = 30.sp, lineHeight = 34.sp, fontWeight = FontWeight.Medium)
                    Text(detail, color = Color.White, style = MaterialTheme.typography.bodyMedium)
                }
                if (this@BoxWithConstraints.maxWidth >= 260.dp) BookIllustration(Modifier.size(76.dp))
            }
        }
    }
}

@Composable internal fun SectionTitle(text: String) {
    Text(text, Modifier.semantics { heading() }, color = Forest, style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.SemiBold)
}

@Composable internal fun TaskLink(
    title: String, detail: String, icon: ImageVector, accent: Color = Soft, enabled: Boolean = true, onClick: () -> Unit,
) {
    Surface(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth(), color = Color.White,
        shape = RoundedCornerShape(18.dp), border = BorderStroke(1.dp, Edge)) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Box(Modifier.size(44.dp).background(accent, RoundedCornerShape(14.dp)), contentAlignment = Alignment.Center) {
                Icon(icon, null, tint = Forest)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(title, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                Text(detail, color = Muted, style = MaterialTheme.typography.bodyMedium)
            }
            Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null, tint = Muted)
        }
    }
}
