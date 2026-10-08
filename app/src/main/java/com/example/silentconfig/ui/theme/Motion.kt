package com.example.aquiethours.ui.theme

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Shared motion tokens so every screen moves the same way. */
object Motion {
    const val SHORT = 180
    const val MEDIUM = 320
    const val STAGGER = 40
    val Emphasized = FastOutSlowInEasing
}

/**
 * Fade + rise in the first time the element is composed. [index] staggers items in a list;
 * the stagger is capped so long lists don't take ages to appear.
 */
fun Modifier.enterAnimation(index: Int = 0): Modifier = composed {
    // Saveable so a lazy-list item scrolled off and back doesn't replay the entrance.
    // Previews/screenshot tests capture a single frame: show the settled state there.
    val inspection = androidx.compose.ui.platform.LocalInspectionMode.current
    var played by rememberSaveable { mutableStateOf(inspection) }
    val progress = remember { Animatable(if (played) 1f else 0f) }
    val risePx = with(LocalDensity.current) { 16.dp.toPx() }
    LaunchedEffect(Unit) {
        if (played) return@LaunchedEffect
        delay((index.coerceAtMost(8) * Motion.STAGGER).toLong())
        launch { progress.animateTo(1f, tween(Motion.MEDIUM, easing = Motion.Emphasized)) }
        played = true
    }
    graphicsLayer {
        alpha = progress.value
        translationY = (1f - progress.value) * risePx
    }
}

/** Springy shrink while pressed. Pass the same interactionSource to the clickable. */
fun Modifier.pressScale(interactionSource: MutableInteractionSource, pressed: Float = 0.97f): Modifier = composed {
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) pressed else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "pressScale"
    )
    graphicsLayer {
        scaleX = scale
        scaleY = scale
    }
}
