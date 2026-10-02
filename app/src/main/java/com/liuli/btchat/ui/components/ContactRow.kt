package com.liuli.btchat.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.liuli.btchat.core.Contact
import com.liuli.btchat.core.TimeFmt
import com.liuli.btchat.ui.glass.GlassAvatar
import com.liuli.btchat.ui.glass.GlassDivider
import com.liuli.btchat.ui.glass.LiuliColors
import com.liuli.btchat.ui.glass.LiuliIcons

/**
 * One row of the 通讯录 (and of the group member picker).
 *
 * Same material rule as [ConversationRow]: a flat white row with an inset
 * hairline, never a glass card. The avatar is a slot so a person renders a
 * [GlassAvatar] and a group renders a `GroupAvatar` while everything else —
 * name, remark, presence, the selection circle — stays identical.
 *
 * @param remark     user-set name; shown as a quiet suffix when it differs.
 * @param selectable draws the round tick used by 发起群聊's member picker.
 * @param leadingCheck puts that tick *before* the avatar, the way the WeChat
 *                     member picker does.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ContactRow(
    name: String,
    seed: Int,
    modifier: Modifier = Modifier,
    remark: String? = null,
    subtitle: String? = null,
    online: Boolean = false,
    selected: Boolean = false,
    selectable: Boolean = false,
    leadingCheck: Boolean = false,
    avatar: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
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
            if (selectable && leadingCheck) {
                CheckCircle(selected, Modifier.size(22.dp))
            }

            if (avatar != null) {
                avatar()
            } else {
                GlassAvatar(
                    name = name,
                    seed = seed,
                    size = RowAvatarSize,
                    online = online,
                    glassSheen = false
                )
            }

            Column(
                Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        name,
                        color = LiuliColors.TextPrimary,
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    if (!remark.isNullOrBlank()) {
                        Text(
                            remark,
                            color = LiuliColors.TextTertiary,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(start = 6.dp)
                        )
                    }
                }
                if (!subtitle.isNullOrBlank()) {
                    Text(
                        subtitle,
                        color = if (online) LiuliColors.Success else LiuliColors.TextTertiary,
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            when {
                selectable && !leadingCheck -> CheckCircle(selected, Modifier.size(22.dp))
                trailing != null -> trailing()
            }
        }

        GlassDivider(
            modifier = Modifier.align(Alignment.BottomStart),
            inset = RowSeparatorInset + (if (selectable && leadingCheck) 34.dp else 0.dp)
        )
    }
}

/** Round tick: green with a white check when on, a hairline circle when off. */
@Composable
fun CheckCircle(selected: Boolean, modifier: Modifier = Modifier) {
    Box(
        modifier
            .background(if (selected) LiuliColors.Accent else Color.Transparent, CircleShape)
            .border(1.dp, if (selected) LiuliColors.Accent else LiuliColors.Separator, CircleShape),
        contentAlignment = Alignment.Center
    ) {
        if (selected) {
            Icon(
                LiuliIcons.Check,
                contentDescription = "已选择",
                tint = Color.White,
                modifier = Modifier.size(14.dp)
            )
        }
    }
}

/** "刚刚 / 12 分钟前 / 昨天" — the presence line of a contact. */
fun lastSeenText(lastSeen: Long, now: Long = System.currentTimeMillis()): String {
    if (lastSeen <= 0L) return "最近未见过"
    val diff = now - lastSeen
    return when {
        diff < 60_000L -> "刚刚还在"
        diff < 3_600_000L -> "${diff / 60_000L} 分钟前"
        diff < 86_400_000L -> "${diff / 3_600_000L} 小时前"
        diff < 172_800_000L -> "昨天见过"
        diff < 604_800_000L -> "${diff / 86_400_000L} 天前"
        else -> "最近可见 ${TimeFmt.listStamp(lastSeen)}"
    }
}

/** Subtitle used by the contact list: address when known, presence otherwise. */
fun contactSubtitle(contact: Contact): String = when {
    contact.address.isNotBlank() -> "已配对 · ${contact.address}"
    contact.lastSeen > 0L -> lastSeenText(contact.lastSeen)
    else -> "尚未配对，可在发现页连接"
}
