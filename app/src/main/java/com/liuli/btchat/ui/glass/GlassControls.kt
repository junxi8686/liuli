package com.liuli.btchat.ui.glass

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Interactive controls, in the light language: a solid accent for the one
 * primary action, a white surface with a hairline for everything else.
 *
 * The signatures are unchanged from the glassy version so screens did not have
 * to move; only the material differs.
 */

@Composable
fun GlassButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    level: GlassLevel = GlassLevel.Regular,
    shape: Shape = RoundedCornerShape(Radii.card),
    enabled: Boolean = true,
    tint: Color = Color.Transparent,
    pressedScale: Float = 0.97f,
    contentPadding: PaddingValues = PaddingValues(horizontal = 18.dp, vertical = 12.dp),
    content: @Composable RowScope.() -> Unit
) {
    val interaction = remember { MutableInteractionSource() }
    val bg = if (tint.alpha > 0f) tint else LiuliColors.Surface
    Row(
        modifier
            .alpha(if (enabled) 1f else 0.45f)
            .pressScale(interaction, pressedScale)
            .background(bg, shape)
            .border(1.dp, LiuliColors.Separator, shape)
            .clickable(
                interactionSource = interaction,
                indication = ripple(bounded = true, color = LiuliColors.Accent.copy(alpha = 0.14f)),
                enabled = enabled,
                onClick = onClick
            )
            .defaultMinSize(minHeight = 42.dp)
            .padding(contentPadding),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        content = content
    )
}

/** The one loud button: solid accent, white label. */
@Composable
fun GlassPrimaryButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = RoundedCornerShape(Radii.card),
    contentPadding: PaddingValues = PaddingValues(horizontal = 22.dp, vertical = 13.dp),
    content: @Composable RowScope.() -> Unit
) {
    val interaction = remember { MutableInteractionSource() }
    Row(
        modifier
            .alpha(if (enabled) 1f else 0.45f)
            .pressScale(interaction, 0.96f)
            .background(LiuliColors.Accent, shape)
            .clickable(
                interactionSource = interaction,
                indication = ripple(bounded = true, color = Color.White.copy(alpha = 0.3f)),
                enabled = enabled,
                onClick = onClick
            )
            .defaultMinSize(minHeight = 46.dp)
            .padding(contentPadding),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        content = content
    )
}

@Composable
fun GlassIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
    level: GlassLevel = GlassLevel.Regular,
    shape: Shape = CircleShape,
    enabled: Boolean = true,
    tint: Color = Color.Transparent,
    /** Off for icon buttons that sit on a white bar and need no outline. */
    bordered: Boolean = true,
    content: @Composable () -> Unit
) {
    val interaction = remember { MutableInteractionSource() }
    var m = modifier
        .size(size)
        .alpha(if (enabled) 1f else 0.45f)
        .pressScale(interaction, 0.90f)
    m = if (level == GlassLevel.Thin && tint.alpha == 0f && !bordered) {
        // A plain tap target on a bar: no material at all, just the ripple.
        m.clickable(
            interactionSource = interaction,
            indication = ripple(bounded = false, color = LiuliColors.TextPrimary.copy(alpha = 0.10f)),
            enabled = enabled,
            onClick = onClick
        )
    } else {
        val bg = if (tint.alpha > 0f) tint else LiuliColors.SurfaceAlt
        m.background(bg, shape)
            .then(if (bordered) Modifier.border(1.dp, LiuliColors.Separator, shape) else Modifier)
            .clickable(
                interactionSource = interaction,
                indication = ripple(bounded = true, color = LiuliColors.Accent.copy(alpha = 0.14f)),
                enabled = enabled,
                onClick = onClick
            )
    }
    Box(m, contentAlignment = Alignment.Center) { content() }
}

@Composable
fun GlassPill(
    text: String,
    selected: Boolean = false,
    onClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    level: GlassLevel = GlassLevel.Thin,
    leading: (@Composable () -> Unit)? = null
) {
    val interaction = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(Radii.pill)
    val bg by animateColorAsState(
        targetValue = if (selected) LiuliColors.Accent else LiuliColors.SurfaceAlt,
        animationSpec = spring(stiffness = 800f),
        label = "pillBg"
    )
    Row(
        modifier
            .pressScale(interaction, 0.96f)
            .background(bg, shape)
            .then(
                if (!selected) Modifier.border(1.dp, LiuliColors.Separator, shape) else Modifier
            )
            .then(
                if (onClick != null) {
                    Modifier.clickable(
                        interactionSource = interaction,
                        indication = ripple(bounded = true, color = LiuliColors.Accent.copy(alpha = 0.14f)),
                        onClick = onClick
                    )
                } else {
                    Modifier
                }
            )
            .padding(horizontal = 13.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        leading?.invoke()
        Text(
            text,
            color = if (selected) Color.White else LiuliColors.TextSecondary,
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
fun GlassBadge(
    count: Int,
    modifier: Modifier = Modifier
) {
    if (count <= 0) return
    Box(
        modifier
            .defaultMinSize(minWidth = 18.dp, minHeight = 18.dp)
            .background(LiuliColors.Danger, RoundedCornerShape(Radii.pill))
            .padding(horizontal = 5.dp, vertical = 1.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            if (count > 99) "99+" else "$count",
            color = Color.White,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Medium
        )
    }
}

/** A plain search box: sunken grey fill, no material, no outline. */
@Composable
fun GlassSearchField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "搜索",
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null
) {
    val shape = RoundedCornerShape(Radii.field)
    Row(
        modifier
            .fillMaxWidth()
            .height(38.dp)
            .background(LiuliColors.SurfaceAlt, shape),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            leading?.invoke()
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                if (value.isEmpty()) {
                    Text(
                        placeholder,
                        color = LiuliColors.TextTertiary,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                CompositionLocalProvider(
                    LocalTextStyle provides MaterialTheme.typography.bodyMedium
                ) {
                    BasicTextField(
                        value = value,
                        onValueChange = onValueChange,
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyMedium.copy(
                            color = LiuliColors.TextPrimary
                        ),
                        cursorBrush = SolidColor(LiuliColors.Accent),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
            trailing?.invoke()
        }
    }
}

@Composable
fun GlassDivider(modifier: Modifier = Modifier, inset: Dp = 0.dp) {
    Box(
        modifier
            .fillMaxWidth()
            .padding(start = inset)
            .height(1.dp)
            .background(LiuliColors.Separator)
    )
}

/**
 * An iOS-shaped switch with the app's accent.
 *
 * The knob travel is derived from the track, not guessed: `50 - 26 - 2 = 22dp`.
 * An earlier version moved it to 24dp, which put the knob flush against the
 * capsule's edge — it read as clipped, and only when the switch was on.
 */
@Composable
fun GlassSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val trackWidth = 50.dp
    val trackHeight = 30.dp
    val knob = 26.dp
    val inset = 2.dp

    val shape = RoundedCornerShape(Radii.pill)
    val track by animateColorAsState(
        if (checked) LiuliColors.Accent else Color(0xFFD9DCE1),
        label = "switchTrack"
    )
    val knobStart by animateDpAsState(
        targetValue = if (checked) trackWidth - knob - inset else inset,
        animationSpec = spring(dampingRatio = 0.85f, stiffness = 900f),
        label = "switchKnob"
    )
    Box(
        modifier
            .size(trackWidth, trackHeight)
            .background(track, shape)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) { onCheckedChange(!checked) },
        contentAlignment = Alignment.CenterStart
    ) {
        Box(
            Modifier
                .padding(start = knobStart)
                .size(knob)
                .shadow(2.dp, CircleShape)
                .background(Color.White, CircleShape)
        )
    }
}

/** Animated fraction used by progress rings and transfer bars. */
@Composable
fun animatedFraction(target: Float, label: String = "frac"): Float {
    val v by animateFloatAsState(
        targetValue = target.coerceIn(0f, 1f),
        animationSpec = spring(dampingRatio = 0.8f, stiffness = 400f),
        label = label
    )
    return v
}
