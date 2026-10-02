package com.liuli.btchat.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.liuli.btchat.core.Peer
import com.liuli.btchat.core.PeerSource
import com.liuli.btchat.ui.glass.GlassAvatar
import com.liuli.btchat.ui.glass.GlassDivider
import com.liuli.btchat.ui.glass.LiuliColors
import com.liuli.btchat.ui.glass.LiuliIcons

/**
 * One Bluetooth device in the 发现 screen.
 *
 * A flat white row with an inset hairline, exactly like a contact: the phone
 * list is long and every glass row would be another blur pass. The row shows
 * what the user needs to decide — advertised name, MAC, where the device came
 * from and how strong the signal is — and leaves the actual
 * [com.liuli.btchat.core.ChatEngine] call to the screen.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PeerRow(
    peer: Peer,
    modifier: Modifier = Modifier,
    connecting: Boolean = false,
    onClick: () -> Unit = {},
    onLongClick: (() -> Unit)? = null
) {
    Box(modifier.fillMaxWidth().background(LiuliColors.Surface)) {
        Row(
            Modifier
                .fillMaxWidth()
                .combinedClickable(onClick = onClick, onLongClick = onLongClick)
                .heightIn(min = 64.dp)
                .padding(horizontal = RowPadding, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (peer.name.isBlank()) {
                Box(
                    Modifier
                        .size(RowAvatarSize)
                        .background(LiuliColors.SurfaceSunken, RoundedCornerShape(10.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        LiuliIcons.Bluetooth,
                        contentDescription = null,
                        tint = LiuliColors.TextTertiary,
                        modifier = Modifier.size(22.dp)
                    )
                }
            } else {
                GlassAvatar(
                    name = peer.name,
                    seed = peer.avatarSeed,
                    size = RowAvatarSize,
                    online = peer.connected,
                    glassSheen = false
                )
            }

            Column(
                Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                Text(
                    peer.name.ifBlank { "未知设备" },
                    color = LiuliColors.TextPrimary,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    SourceTag(peer.source)
                    Text(
                        peer.address.ifBlank { peer.deviceId.take(12) }.ifBlank { "无地址" },
                        color = LiuliColors.TextTertiary,
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    if (peer.rssi != 0) SignalBars(peer.rssi)
                }
            }

            when {
                connecting -> RotatingRefresh()
                peer.connected -> Text(
                    "进入",
                    color = LiuliColors.Accent,
                    style = MaterialTheme.typography.labelMedium
                )
                else -> Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Text(
                        "连接",
                        color = LiuliColors.TextTertiary,
                        style = MaterialTheme.typography.labelMedium
                    )
                    Icon(
                        LiuliIcons.ChevronRight,
                        contentDescription = null,
                        tint = LiuliColors.TextTertiary,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }

        GlassDivider(
            modifier = Modifier.align(Alignment.BottomStart),
            inset = RowSeparatorInset
        )
    }
}

/** Where a device came from: a small chip, green only when it is connected. */
@Composable
private fun SourceTag(source: PeerSource) {
    val (label, tint) = when (source) {
        PeerSource.CONNECTED -> "已连接" to LiuliColors.Accent
        PeerSource.BONDED -> "已配对" to LiuliColors.TextSecondary
        PeerSource.DISCOVERED -> "附近" to LiuliColors.TextSecondary
        PeerSource.SAVED -> "曾配对" to LiuliColors.TextTertiary
    }
    Box(
        Modifier
            .background(
                if (source == PeerSource.CONNECTED) {
                    LiuliColors.Accent.copy(alpha = 0.12f)
                } else {
                    LiuliColors.SurfaceSunken
                },
                RoundedCornerShape(4.dp)
            )
            .padding(horizontal = 5.dp, vertical = 1.dp)
    ) {
        Text(label, color = tint, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun SignalBars(rssi: Int) {
    val level = when {
        rssi >= -58 -> 4
        rssi >= -70 -> 3
        rssi >= -84 -> 2
        else -> 1
    }
    Row(
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        repeat(4) { index ->
            Box(
                Modifier
                    .width(3.dp)
                    .height((5 + index * 3).dp)
                    .background(
                        if (index < level) LiuliColors.Success else LiuliColors.Separator,
                        RoundedCornerShape(2.dp)
                    )
            )
        }
    }
}

@Composable
private fun RotatingRefresh() {
    val transition = rememberInfiniteTransition(label = "peerSpin")
    val angle by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 900, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "peerAngle"
    )
    Icon(
        LiuliIcons.Refresh,
        contentDescription = "连接中",
        tint = LiuliColors.Warn,
        modifier = Modifier
            .size(20.dp)
            .rotate(angle)
    )
}

/** A quiet grey dot used by the peer list's empty rows. */
@Composable
fun IdleDot(color: Color = LiuliColors.TextTertiary) {
    Box(Modifier.size(8.dp).background(color, CircleShape))
}
