package com.example.aquiethours.ui.theme

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import com.example.aquiethours.ui.icons.NotificationsOff
import com.example.aquiethours.ui.icons.Vibration
import com.example.aquiethours.ui.icons.VolumeUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

// ---------------------------------------------------------------------------------------------
// Quiet Hours design system. Every screen builds from these so spacing, shape and motion match.
//   Radii: 28 (hero) / 22 (cards) / 16 (controls) / full (pills)
//   Spacing: 4-pt grid; screen gutter 20dp
// ---------------------------------------------------------------------------------------------

object Q {
    val Gutter = 20.dp
    val CardRadius = 22.dp
    val HeroRadius = 28.dp
    val ControlRadius = 16.dp
    /** Height reserved at the bottom of tab screens for the floating nav bar. */
    val BottomBarSpace = 104.dp

    val CardColor = SurfaceContainerDark
    val CardBorder = Color.White.copy(alpha = 0.06f)
    val Muted = OnSurfaceVariantDark
}

/**
 * Screen background: deep slate with two slow-drifting aurora glows. Subtle by design —
 * it gives the app depth and a sense of calm motion without competing with content.
 */
@Composable
fun AuroraBackground(
    modifier: Modifier = Modifier,
    accent: Color = PrimaryLight,
    secondary: Color = SecondaryLight,
    content: @Composable () -> Unit
) {
    val t = rememberInfiniteTransition(label = "aurora")
    val drift by t.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(14000, easing = LinearEasing), RepeatMode.Reverse),
        label = "drift"
    )
    val glowA by animateColorAsState(accent, tween(900), label = "glowA")
    val glowB by animateColorAsState(secondary, tween(900), label = "glowB")
    Box(
        modifier
            .fillMaxSize()
            .background(SurfaceDark)
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            drawCircle(
                brush = Brush.radialGradient(
                    listOf(glowA.copy(alpha = 0.16f), Color.Transparent),
                    center = Offset(w * (0.15f + 0.25f * drift), h * 0.05f),
                    radius = w * 0.9f
                ),
                radius = w * 0.9f,
                center = Offset(w * (0.15f + 0.25f * drift), h * 0.05f)
            )
            drawCircle(
                brush = Brush.radialGradient(
                    listOf(glowB.copy(alpha = 0.10f), Color.Transparent),
                    center = Offset(w * (0.95f - 0.2f * drift), h * (0.25f + 0.1f * drift)),
                    radius = w * 0.7f
                ),
                radius = w * 0.7f,
                center = Offset(w * (0.95f - 0.2f * drift), h * (0.25f + 0.1f * drift))
            )
        }
        content()
    }
}

/** Transparent header: big title, optional subtitle, back button and trailing actions. */
@Composable
fun QuietHeader(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    /** False when the parent already applied status-bar insets (e.g. a list with a sticky bar). */
    statusBarPadding: Boolean = true,
    actions: @Composable RowScope.() -> Unit = {}
) {
    Column(
        modifier
            .fillMaxWidth()
            .then(if (statusBarPadding) Modifier.statusBarsPadding() else Modifier)
            .padding(start = if (onBack != null) 8.dp else Q.Gutter, end = 12.dp, top = 8.dp, bottom = 8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (onBack != null) {
                CircleIconButton(Icons.AutoMirrored.Filled.ArrowBack, "Back", onBack)
                Spacer(Modifier.width(8.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.headlineMedium,
                    color = OnSurfaceDark,
                    fontWeight = FontWeight.Bold
                )
                if (subtitle != null) {
                    Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = Q.Muted)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, content = actions)
        }
    }
}

/** 44dp round tonal icon button with press feedback. */
@Composable
fun CircleIconButton(
    icon: ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = OnSurfaceDark,
    container: Color = Color.White.copy(alpha = 0.06f)
) {
    val source = remember { MutableInteractionSource() }
    Box(
        modifier
            .size(44.dp)
            .pressScale(source, 0.9f)
            .clip(CircleShape)
            .background(container)
            .clickable(interactionSource = source, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription, tint = tint, modifier = Modifier.size(22.dp))
    }
}

/** Standard surface card. [accent] adds a soft tinted glow border (e.g. the live class). */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun QuietCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    accent: Color? = null,
    radius: Dp = Q.CardRadius,
    contentPadding: PaddingValues = PaddingValues(18.dp),
    content: @Composable ColumnScope.() -> Unit
) {
    val source = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(radius)
    val borderColor by animateColorAsState(accent?.copy(alpha = 0.55f) ?: Q.CardBorder, tween(Motion.MEDIUM), label = "cardBorder")
    val bg = if (accent != null) Brush.verticalGradient(listOf(accent.copy(alpha = 0.12f), Q.CardColor))
             else Brush.verticalGradient(listOf(Q.CardColor, Q.CardColor))
    var m = modifier
        .then(if (onClick != null) Modifier.pressScale(source) else Modifier)
        .clip(shape)
        .background(bg)
        .border(1.dp, borderColor, shape)
    if (onClick != null) {
        m = m.combinedClickable(
            interactionSource = source,
            indication = androidx.compose.material.ripple.rememberRipple(color = accent ?: Color.White),
            onClick = onClick,
            onLongClick = onLongClick
        )
    }
    Column(m.padding(contentPadding), content = content)
}

/** Small uppercase section label. */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier, trailing: @Composable (() -> Unit)? = null) {
    Row(modifier.fillMaxWidth().padding(top = 8.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text.uppercase(),
            style = MaterialTheme.typography.labelMedium,
            color = Q.Muted,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f)
        )
        trailing?.invoke()
    }
}

/** Rounded-square tinted icon chip, used as a leading visual in rows and cards. */
@Composable
fun IconBadge(icon: ImageVector, tint: Color, modifier: Modifier = Modifier, size: Dp = 40.dp) {
    Box(
        modifier
            .size(size)
            .clip(RoundedCornerShape(size * 0.32f))
            .background(tint.copy(alpha = 0.14f)),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(size * 0.52f))
    }
}

/** Filled pill CTA with gradient, press-scale and an inline loading state. */
@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    loading: Boolean = false,
    colors: List<Color> = listOf(PrimaryLight, Color(0xFF5B8FF0))
) {
    val source = remember { MutableInteractionSource() }
    val alpha by androidx.compose.animation.core.animateFloatAsState(if (enabled) 1f else 0.38f, tween(Motion.SHORT), label = "btnAlpha")
    Box(
        modifier
            .height(56.dp)
            .pressScale(source, 0.96f)
            .graphicsLayer { this.alpha = alpha }
            .clip(RoundedCornerShape(50))
            .background(Brush.horizontalGradient(colors))
            .clickable(interactionSource = source, indication = null, enabled = enabled && !loading, onClick = onClick)
            .padding(horizontal = 24.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
            if (loading) {
                CircularProgressIndicator(Modifier.size(20.dp), color = OnPrimaryInk, strokeWidth = 2.5.dp)
                Spacer(Modifier.width(10.dp))
            } else if (icon != null) {
                Icon(icon, null, tint = OnPrimaryInk, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
            }
            Text(text, color = OnPrimaryInk, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        }
    }
}

/** Ink colour for text on the blue gradient (better contrast than white). */
val OnPrimaryInk = Color(0xFF0A1A3A)

/** Outlined/tonal pill for secondary actions. */
@Composable
fun SecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    tint: Color = OnSurfaceDark,
    enabled: Boolean = true
) {
    val source = remember { MutableInteractionSource() }
    Box(
        modifier
            .height(52.dp)
            .pressScale(source, 0.96f)
            .graphicsLayer { alpha = if (enabled) 1f else 0.4f }
            .clip(RoundedCornerShape(50))
            .background(Color.White.copy(alpha = 0.06f))
            .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(50))
            .clickable(interactionSource = source, indication = null, enabled = enabled, onClick = onClick)
            .padding(horizontal = 20.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                Icon(icon, null, tint = tint, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
            }
            Text(text, color = tint, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
        }
    }
}

/** Custom animated switch: springy thumb, colour crossfade. */
@Composable
fun PillToggle(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    activeColor: Color = PrimaryLight,
    enabled: Boolean = true
) {
    val track by animateColorAsState(if (checked) activeColor else Color.White.copy(alpha = 0.12f), tween(Motion.SHORT), label = "track")
    val thumbX by animateDpAsState(
        if (checked) 22.dp else 2.dp,
        spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMediumLow),
        label = "thumb"
    )
    val thumbColor by animateColorAsState(if (checked) OnPrimaryInk else OnSurfaceVariantDark, tween(Motion.SHORT), label = "thumbC")
    val source = remember { MutableInteractionSource() }
    Box(
        modifier
            .size(width = 50.dp, height = 30.dp)
            .graphicsLayer { alpha = if (enabled) 1f else 0.4f }
            .clip(RoundedCornerShape(50))
            .background(track)
            .clickable(interactionSource = source, indication = null, enabled = enabled) { onCheckedChange(!checked) }
    ) {
        Box(
            Modifier
                .offset(x = thumbX, y = 3.dp)
                .size(24.dp)
                .clip(CircleShape)
                .background(thumbColor)
        )
    }
}

/**
 * Segmented control with a sliding, springy highlight. Each option can carry an icon and its
 * own accent colour (e.g. Silent = coral, Vibrate = amber, Ring = blue).
 */
data class Segment(val label: String, val icon: ImageVector? = null, val color: Color = PrimaryLight)

@Composable
fun SegmentedSelector(
    segments: List<Segment>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    tall: Boolean = false
) {
    val shape = RoundedCornerShape(Q.ControlRadius)
    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .height(if (tall) 76.dp else 48.dp)
            .clip(shape)
            .background(Color.White.copy(alpha = 0.05f))
            .padding(4.dp)
    ) {
        val segW = maxWidth / segments.size
        val x by animateDpAsState(
            segW * selectedIndex.coerceAtLeast(0),
            spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMediumLow),
            label = "segX"
        )
        val sel = segments.getOrNull(selectedIndex)
        val hl by animateColorAsState(sel?.color ?: PrimaryLight, tween(Motion.MEDIUM), label = "segC")
        if (selectedIndex >= 0) {
            Box(
                Modifier
                    .offset(x = x)
                    .width(segW)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(Q.ControlRadius - 4.dp))
                    .background(hl.copy(alpha = 0.18f))
                    .border(1.dp, hl.copy(alpha = 0.5f), RoundedCornerShape(Q.ControlRadius - 4.dp))
            )
        }
        Row(Modifier.fillMaxSize()) {
            segments.forEachIndexed { i, seg ->
                val selected = i == selectedIndex
                val c by animateColorAsState(if (selected) seg.color else Q.Muted, tween(Motion.SHORT), label = "segT")
                val source = remember { MutableInteractionSource() }
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clickable(interactionSource = source, indication = null) { onSelect(i) },
                    contentAlignment = Alignment.Center
                ) {
                    if (tall) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            seg.icon?.let { Icon(it, null, tint = c, modifier = Modifier.size(22.dp)) }
                            Spacer(Modifier.height(4.dp))
                            Text(seg.label, color = c, style = MaterialTheme.typography.labelMedium, fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium)
                        }
                    } else {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            seg.icon?.let { Icon(it, null, tint = c, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)) }
                            Text(seg.label, color = c, style = MaterialTheme.typography.labelLarge, fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium)
                        }
                    }
                }
            }
        }
    }
}

/** Centered empty state: animated floating badge, title, body, optional actions. */
@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    tint: Color = PrimaryLight,
    actions: @Composable ColumnScope.() -> Unit = {}
) {
    val t = rememberInfiniteTransition(label = "float")
    val bob by t.animateFloat(0f, 1f, infiniteRepeatable(tween(2400, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "bob")
    Column(
        modifier.fillMaxWidth().padding(horizontal = 32.dp).enterAnimation(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            Modifier
                .graphicsLayer { translationY = -10f * bob }
                .size(112.dp)
                .clip(CircleShape)
                .background(Brush.radialGradient(listOf(tint.copy(alpha = 0.28f), tint.copy(alpha = 0.04f)))),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, null, tint = tint, modifier = Modifier.size(48.dp))
        }
        Spacer(Modifier.height(24.dp))
        Text(title, style = MaterialTheme.typography.titleLarge, color = OnSurfaceDark, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(body, style = MaterialTheme.typography.bodyMedium, color = Q.Muted, textAlign = TextAlign.Center)
        Spacer(Modifier.height(28.dp))
        actions()
    }
}

/** Styled confirmation dialog. [destructive] colours the confirm action coral. */
@Composable
fun QuietDialog(
    title: String,
    onDismiss: () -> Unit,
    confirmText: String,
    onConfirm: () -> Unit,
    dismissText: String? = "Cancel",
    /** Action for the dismiss *button*; defaults to [onDismiss] (outside tap / back). */
    onDismissButton: (() -> Unit)? = null,
    destructive: Boolean = false,
    confirmEnabled: Boolean = true,
    icon: ImageVector? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = SurfaceContainerHighDark,
        shape = RoundedCornerShape(Q.HeroRadius),
        icon = icon?.let { { IconBadge(it, if (destructive) ErrorDark else PrimaryLight, size = 48.dp) } },
        title = { Text(title, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center) },
        text = { Column(content = content) },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = confirmEnabled) {
                Text(confirmText, color = if (destructive) ErrorDark else PrimaryLight, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = dismissText?.let { { TextButton(onClick = onDismissButton ?: onDismiss) { Text(it, color = Q.Muted) } } }
    )
}

/** Fades/scales its content in and out; handy for conditional chips and badges. */
@Composable
fun PopIn(visible: Boolean, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = fadeIn(tween(Motion.SHORT)) + scaleIn(initialScale = 0.8f, animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy)),
        exit = fadeOut(tween(Motion.SHORT)) + scaleOut(targetScale = 0.8f)
    ) { content() }
}

/** Small rounded label chip. */
@Composable
fun Chip(text: String, color: Color, modifier: Modifier = Modifier, icon: ImageVector? = null) {
    Row(
        modifier
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.14f))
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        icon?.let { Icon(it, null, tint = color, modifier = Modifier.size(14.dp)); Spacer(Modifier.width(5.dp)) }
        Text(text, color = color, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
    }
}

// ----- Domain helpers shared by screens -----

/** Visual identity for an AudioManager ringer mode (0 silent, 1 vibrate, 2 normal). */
data class RingerVisual(val label: String, val icon: ImageVector, val color: Color)

fun ringerVisual(mode: Int): RingerVisual = when (mode) {
    // Silent is the app's calm default, so it gets the cool sky tone, not alarm-red.
    0 -> RingerVisual("Silent", Icons.Outlined.NotificationsOff, SecondaryLight)
    1 -> RingerVisual("Vibrate", Icons.Outlined.Vibration, TertiaryLight)
    else -> RingerVisual("Ring", Icons.Outlined.VolumeUp, PrimaryLight)
}

/** "1h 05m", "45 min". */
fun formatDuration(totalMinutes: Int): String {
    val h = totalMinutes / 60
    val m = totalMinutes % 60
    return when {
        h == 0 -> "$m min"
        m == 0 -> "${h}h"
        else -> "${h}h ${m.toString().padStart(2, '0')}m"
    }
}
