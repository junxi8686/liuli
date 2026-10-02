package com.liuli.btchat.ui.glass

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.liuli.btchat.core.LinkState
import com.liuli.btchat.core.LinkStatus

/**
 * App chrome: the top bar, the tab bar, the action bubble and the small status
 * pieces.
 *
 * This is the only place glass is still used for a bar — list rows and message
 * bubbles are flat on purpose, because every glass surface costs a graphics
 * layer and a blur pass, and a list has dozens of them.
 */

@Composable
fun GlassTopBar(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    /** WeChat centres the title; a leading title reads better for long names. */
    centered: Boolean = true,
    trailing: @Composable RowScope.() -> Unit = {}
) {
    val mode = LocalGlassMode.current
    Box(
        modifier
            .fillMaxWidth()
            .then(
                if (mode == GlassMode.None) {
                    // Flat white. A blur here costs an offscreen layer and a
                    // render effect on every screen for a bar that ends up
                    // looking white anyway.
                    Modifier.background(LiuliColors.Surface)
                } else {
                    Modifier.glassSurface(
                        shape = RoundedCornerShape(0.dp),
                        level = GlassLevel.Bar,
                        specularEdge = false
                    )
                }
            )
            // Inside the material, so it reaches up behind the status bar
            // instead of leaving a band of page colour above it.
            .statusBarsPadding()
    ) {
        Box(Modifier.fillMaxWidth().heightIn(min = 50.dp)) {

            if (centered) {
                // Absolutely centred, not "centred between two weighted slots":
                // a wide trailing action (a status pill, two icon buttons) used
                // to shove the title off-centre.
                Column(
                    Modifier
                        .align(Alignment.Center)
                        .padding(horizontal = 74.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    TitleText(title, subtitle, TextAlign.Center)
                }
            } else {
                Column(
                    Modifier
                        .align(Alignment.CenterStart)
                        .padding(start = if (onBack != null) 54.dp else 16.dp, end = 74.dp)
                ) {
                    TitleText(title, subtitle, TextAlign.Start)
                }
            }

            if (onBack != null) {
                GlassIconButton(
                    onClick = onBack,
                    size = 42.dp,
                    level = GlassLevel.Thin,
                    shape = CircleShape,
                    bordered = false,
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .padding(start = 4.dp)
                ) {
                    Icon(LiuliIcons.Back, contentDescription = "返回", tint = LiuliColors.TextPrimary)
                }
            }

            Row(
                Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp)
            ) { trailing() }

            // Hairline separator, drawn last so it sits on top of the material.
            Box(
                Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(LiuliColors.Separator)
            )
        }
    }
}

@Composable
private fun TitleText(title: String, subtitle: String?, align: TextAlign) {
    Text(
        title,
        color = LiuliColors.TextPrimary,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        textAlign = align
    )
    if (!subtitle.isNullOrBlank()) {
        Text(
            subtitle,
            color = LiuliColors.TextTertiary,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = align
        )
    }
}

data class NavItem(
    val icon: ImageVector,
    val label: String
)

@Composable
fun GlassBottomNav(
    items: List<NavItem>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    badgeFor: (Int) -> Int = { 0 }
) {
    val mode = LocalGlassMode.current
    Column(
        modifier
            .fillMaxWidth()
            .then(
                if (mode == GlassMode.None) {
                    Modifier.background(LiuliColors.Surface)
                } else {
                    Modifier.glassSurface(
                        shape = RoundedCornerShape(0.dp),
                        level = GlassLevel.Bar,
                        specularEdge = false
                    )
                }
            )
            // Same idea at the bottom: the material continues behind the system
            // navigation bar rather than stopping above it.
            .navigationBarsPadding()
    ) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(LiuliColors.Separator))
        Row(
            Modifier
                .fillMaxWidth()
                .padding(vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            items.forEachIndexed { index, item ->
                NavSlot(
                    item = item,
                    selected = index == selected,
                    badge = badgeFor(index),
                    onClick = { onSelect(index) },
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun NavSlot(
    item: NavItem,
    selected: Boolean,
    badge: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val interaction = remember { MutableInteractionSource() }
    val tint by animateColorAsState(
        targetValue = if (selected) LiuliColors.Accent else LiuliColors.TextTertiary,
        animationSpec = spring(stiffness = 900f),
        label = "navTint"
    )
    Box(
        modifier
            .clickable(
                interactionSource = interaction,
                indication = ripple(bounded = true, color = LiuliColors.Accent.copy(alpha = 0.12f)),
                onClick = onClick
            )
            .padding(vertical = 4.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Box {
                Icon(item.icon, contentDescription = item.label, tint = tint, modifier = Modifier.size(24.dp))
                if (badge > 0) {
                    GlassBadge(badge, Modifier.align(Alignment.TopEnd).padding(start = 16.dp))
                }
            }
            Text(
                item.label,
                color = tint,
                style = MaterialTheme.typography.labelSmall
            )
        }
    }
}

/** The one raised control: a solid accent circle, the way a send button reads. */
@Composable
fun GlassFab(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector = LiuliIcons.Add,
    size: Dp = 54.dp
) {
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier
            .size(size)
            .pressScale(interaction, 0.92f)
            .shadow(10.dp, CircleShape)
            .background(LiuliColors.Accent, CircleShape)
            .clickable(
                interactionSource = interaction,
                indication = ripple(bounded = false, color = Color.White.copy(alpha = 0.4f)),
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(size * 0.44f))
    }
}

/** Compact Bluetooth health readout; tapping it jumps to the discover screen. */
@Composable
fun GlassLinkPill(
    link: LinkStatus,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null
) {
    val (icon, tint, label) = when (link.state) {
        LinkState.CONNECTED -> Triple(LiuliIcons.BluetoothOn, LiuliColors.Success, "${link.connections} 台已连接")
        LinkState.READY -> Triple(LiuliIcons.Bluetooth, LiuliColors.Accent, "蓝牙就绪")
        LinkState.SCANNING -> Triple(LiuliIcons.Discover, LiuliColors.Cyan, "搜索中…")
        LinkState.CONNECTING -> Triple(LiuliIcons.Speed, LiuliColors.Warn, "连接中…")
        LinkState.UNAUTHORIZED -> Triple(LiuliIcons.Lock, LiuliColors.Danger, "缺少权限")
        LinkState.ERROR -> Triple(LiuliIcons.Error, LiuliColors.Danger, link.message.ifBlank { "蓝牙异常" })
        LinkState.OFF -> Triple(LiuliIcons.BluetoothOff, LiuliColors.TextTertiary, "蓝牙未开启")
    }
    val interaction = remember { MutableInteractionSource() }
    Row(
        modifier
            .pressScale(interaction, 0.96f)
            .background(LiuliColors.SurfaceAlt, RoundedCornerShape(Radii.pill))
            .then(
                if (onClick != null) {
                    Modifier.clickable(
                        interactionSource = interaction,
                        indication = ripple(bounded = true, color = LiuliColors.Accent.copy(alpha = 0.12f)),
                        onClick = onClick
                    )
                } else Modifier
            )
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(15.dp))
        Text(label, color = LiuliColors.TextSecondary, style = MaterialTheme.typography.labelMedium)
    }
}

/** Section heading above a grouped list. */
@Composable
fun GlassSectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier = modifier.padding(start = 28.dp, top = 16.dp, bottom = 6.dp),
        color = LiuliColors.TextTertiary,
        style = MaterialTheme.typography.labelMedium
    )
}

/** Empty-state block, kept plain: an icon, one sentence, one action. */
@Composable
fun GlassEmptyState(
    icon: ImageVector,
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null
) {
    Column(
        modifier.fillMaxWidth().padding(horizontal = 40.dp, vertical = 56.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Box(
            Modifier
                .size(76.dp)
                .background(LiuliColors.SurfaceAlt, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = null, tint = LiuliColors.TextTertiary, modifier = Modifier.size(32.dp))
        }
        Text(title, color = LiuliColors.TextPrimary, style = MaterialTheme.typography.titleMedium)
        Text(
            message,
            color = LiuliColors.TextTertiary,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center
        )
        if (action != null) {
            Box(Modifier.padding(top = 6.dp)) { action() }
        }
    }
}
