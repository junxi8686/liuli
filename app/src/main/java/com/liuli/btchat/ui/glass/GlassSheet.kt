package com.liuli.btchat.ui.glass

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Modal surfaces. They live in the overlay slot so the panel refracts the
 * screen behind it exactly like every other piece of glass.
 */

@Composable
fun GlassSheet(
    visible: Boolean,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Box(modifier.fillMaxSize()) {
        AnimatedVisibility(
            visible = visible,
            enter = fadeIn(tween(180)),
            exit = fadeOut(tween(160))
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(LiuliColors.Scrim)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onDismiss
                    )
            )
        }
        AnimatedVisibility(
            visible = visible,
            modifier = Modifier.align(Alignment.BottomCenter),
            // A plain tween, not a spring: the panel is large and carries a
            // render effect, so every frame of the animation re-blurs it. A
            // spring keeps moving after the eye has settled; 240ms of tween is
            // both shorter and predictable.
            enter = slideInVertically(
                animationSpec = tween(240),
                initialOffsetY = { it }
            ) + fadeIn(tween(160)),
            exit = slideOutVertically(
                animationSpec = tween(180),
                targetOffsetY = { it }
            ) + fadeOut(tween(140))
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp)
                    .glassSurface(
                        shape = RoundedCornerShape(Radii.sheet),
                        level = GlassLevel.Sheet,
                        specularEdge = false
                    )
                    // Inside the material, so the panel's white reaches down
                    // behind the system navigation bar instead of hovering
                    // above it.
                    .navigationBarsPadding()
                    .padding(bottom = 8.dp)
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                Box(
                    Modifier
                        .align(Alignment.CenterHorizontally)
                        .width(38.dp)
                        .height(4.dp)
                        .background(Color(0xFFD9DCE1), RoundedCornerShape(Radii.pill))
                )
                Spacer(Modifier.height(12.dp))
                content()
                Spacer(Modifier.height(6.dp))
            }
        }
    }
}

data class SheetAction(
    val icon: ImageVector,
    val label: String,
    val description: String? = null,
    val danger: Boolean = false,
    val onClick: () -> Unit
)

@Composable
fun GlassActionSheet(
    visible: Boolean,
    onDismiss: () -> Unit,
    title: String? = null,
    actions: List<SheetAction>,
    modifier: Modifier = Modifier
) {
    GlassSheet(visible = visible, onDismiss = onDismiss, modifier = modifier) {
        if (title != null) {
            Text(
                title,
                color = LiuliColors.TextSecondary,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(start = 6.dp, bottom = 8.dp)
            )
        }
        actions.forEach { action ->
            SheetRow(action)
        }
    }
}

@Composable
private fun SheetRow(action: SheetAction) {
    val interaction = remember { MutableInteractionSource() }
    Row(
        Modifier
            .fillMaxWidth()
            .pressScale(interaction, 0.975f)
            .background(
                if (action.danger) LiuliColors.Danger.copy(alpha = 0.06f) else LiuliColors.Surface,
                RoundedCornerShape(Radii.card)
            )
            .border(1.dp, LiuliColors.Separator, RoundedCornerShape(Radii.card))
            .clickable(
                interactionSource = interaction,
                indication = androidx.compose.material3.ripple(
                    bounded = true,
                    color = LiuliColors.Accent.copy(alpha = 0.14f)
                ),
                onClick = action.onClick
            )
            .padding(horizontal = 14.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(13.dp)
    ) {
        Box(
            Modifier
                .size(38.dp)
                .background(LiuliColors.SurfaceAlt, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                action.icon,
                contentDescription = null,
                tint = if (action.danger) LiuliColors.Danger else LiuliColors.Accent,
                modifier = Modifier.size(19.dp)
            )
        }
        Column(Modifier.weight(1f)) {
            Text(
                action.label,
                color = if (action.danger) LiuliColors.Danger else LiuliColors.TextPrimary,
                style = MaterialTheme.typography.titleSmall
            )
            if (action.description != null) {
                Text(
                    action.description,
                    color = LiuliColors.TextTertiary,
                    style = MaterialTheme.typography.labelMedium
                )
            }
        }
    }
    Spacer(Modifier.height(7.dp))
}

@Composable
fun GlassConfirmDialog(
    visible: Boolean,
    title: String,
    message: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
    confirmText: String = "确定",
    dismissText: String = "取消",
    destructive: Boolean = false,
    modifier: Modifier = Modifier
) {
    Box(modifier.fillMaxSize()) {
        AnimatedVisibility(
            visible = visible,
            enter = fadeIn(tween(170)),
            exit = fadeOut(tween(150))
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(LiuliColors.Scrim)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onDismiss
                    )
            )
        }
        AnimatedVisibility(
            visible = visible,
            modifier = Modifier.align(Alignment.Center),
            enter = fadeIn(tween(160)) + slideInVertically(
                animationSpec = spring(dampingRatio = 0.8f, stiffness = 500f),
                initialOffsetY = { it / 6 }
            ),
            exit = fadeOut(tween(140))
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 34.dp)
                    .glassSurface(
                        shape = RoundedCornerShape(Radii.panel),
                        level = GlassLevel.Sheet,
                        specularEdge = false
                    )
                    .padding(22.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    title,
                    color = LiuliColors.TextPrimary,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    message,
                    color = LiuliColors.TextSecondary,
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
                Spacer(Modifier.height(4.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    GlassButton(
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f),
                        level = GlassLevel.Thin,
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 12.dp)
                    ) {
                        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                            Text(dismissText, color = LiuliColors.TextSecondary, style = MaterialTheme.typography.labelLarge)
                        }
                    }
                    GlassButton(
                        onClick = onConfirm,
                        modifier = Modifier.weight(1f),
                        level = GlassLevel.Thin,
                        tint = if (destructive) LiuliColors.Danger.copy(alpha = 0.10f) else LiuliColors.Accent.copy(alpha = 0.12f),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 12.dp)
                    ) {
                        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                            Text(
                                confirmText,
                                color = if (destructive) LiuliColors.Danger else LiuliColors.AccentDeep,
                                style = MaterialTheme.typography.labelLarge
                            )
                        }
                    }
                }
            }
        }
    }
}
