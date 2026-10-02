package com.liuli.btchat.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.liuli.btchat.core.ConvKind
import com.liuli.btchat.core.Conversation
import com.liuli.btchat.core.TimeFmt
import com.liuli.btchat.ui.glass.GlassAvatar
import com.liuli.btchat.ui.glass.GlassBadge
import com.liuli.btchat.ui.glass.GlassDivider
import com.liuli.btchat.ui.glass.GlassMode
import com.liuli.btchat.ui.glass.GlassStage
import com.liuli.btchat.ui.glass.GroupAvatar
import com.liuli.btchat.ui.glass.LiuliColors
import com.liuli.btchat.ui.glass.LiuliIcons
import com.liuli.btchat.ui.glass.LocalGlassMode
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** WeChat's list metrics, shared by every row in the app. */
val RowAvatarSize = 48.dp
val RowHeight = 72.dp
val RowPadding = 16.dp

/** Separator starts where the avatar ends, i.e. inset from the left edge. */
val RowSeparatorInset = RowPadding + RowAvatarSize + 12.dp

/**
 * The page scaffold every screen in this package draws into.
 *
 * `GlassStage` samples a recorded layer, and a `LayerBackdrop` records the
 * subtree it wraps **and** draws it again — so a scrolling list inside the stage
 * is submitted to the renderer twice per frame, and the frosted bars then blur
 * that layer on top of it. On a flat near-white page that buys almost nothing:
 * the bars are 80–94% white, so a frosted bar and a plain one are
 * indistinguishable, while the cost is two extra passes over every row.
 *
 * It is therefore driven by the user's effect setting: the recorded stage is
 * used exactly when an effect is switched on (**液态玻璃** or **普通高斯模糊**), and
 * a plain box when effects are off. Recording the content is what makes the
 * blur *visible* — with nothing behind the bar there is nothing to blur.
 */
private const val ForceRecordedGlassStage = false

@Composable
fun LiuliScaffold(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
    overlay: @Composable BoxScope.() -> Unit = {}
) {
    val effectsOn = LocalGlassMode.current != GlassMode.None
    if (ForceRecordedGlassStage || effectsOn) {
        GlassStage(modifier = modifier, content = content, overlay = overlay)
    } else {
        // A flat page: one Box, no recorded layers, no per-frame blur. Glass
        // surfaces still render (they fall back to their own tint + hairline),
        // which is exactly what a WeChat-style white bar looks like.
        Box(modifier.fillMaxSize().background(LiuliColors.Bg)) {
            content()
            overlay()
        }
    }
}

/**
 * One conversation in the 会话 list.
 *
 * Deliberately a **flat white row**: no glass, no card, no shadow. A glass
 * surface costs a graphics layer plus a blur pass, and a screen of ten of them
 * is what made scrolling stutter on real hardware. Rows are painted
 * [LiuliColors.Surface] and separated by a hairline inset to the avatar's right
 * edge — the structure people already read as "a chat list".
 *
 * The row still owns its two gestures: a left swipe that uncovers 免打扰/删除,
 * and a long press that opens the same action sheet.
 *
 * @param title   resolved by the caller so a renamed contact shows up without
 *                rewriting the frozen [Conversation.title].
 * @param preview last line, already resolved by the caller off the main thread.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ConversationRow(
    conv: Conversation,
    title: String,
    preview: String,
    modifier: Modifier = Modifier,
    sender: String? = null,
    groupSeeds: List<Int> = emptyList(),
    online: Boolean = false,
    typing: Boolean = false,
    onClick: () -> Unit = {},
    onLongClick: () -> Unit = {},
    onToggleMute: () -> Unit = {},
    onDelete: () -> Unit = {}
) {
    val reach = 72.dp
    val reachPx = with(LocalDensity.current) { (reach * 2).toPx() }
    val slide = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()

    Box(modifier.fillMaxWidth().background(LiuliColors.Surface)) {
        // The actions sit behind the row; a left swipe uncovers them.
        Row(
            Modifier.matchParentSize(),
            horizontalArrangement = Arrangement.End
        ) {
            RevealAction(
                label = if (conv.muted) "取消免打扰" else "免打扰",
                background = LiuliColors.SurfaceSunken,
                foreground = LiuliColors.TextSecondary,
                width = reach
            ) {
                scope.launch { slide.animateTo(0f, spring(stiffness = 700f)) }
                onToggleMute()
            }
            RevealAction(
                label = "删除",
                background = LiuliColors.Danger,
                foreground = Color.White,
                width = reach
            ) {
                scope.launch { slide.animateTo(0f, spring(stiffness = 700f)) }
                onDelete()
            }
        }

        Row(
            Modifier
                .offset { IntOffset(slide.value.roundToInt(), 0) }
                .draggable(
                    orientation = Orientation.Horizontal,
                    state = rememberDraggableState { delta ->
                        scope.launch { slide.snapTo((slide.value + delta).coerceIn(-reachPx, 0f)) }
                    },
                    onDragStopped = {
                        scope.launch {
                            val target = if (slide.value < -reachPx * 0.4f) -reachPx else 0f
                            slide.animateTo(target, spring(dampingRatio = 0.82f, stiffness = 620f))
                        }
                    }
                )
                .fillMaxWidth()
                .background(LiuliColors.Surface)
                .combinedClickable(onClick = onClick, onLongClick = onLongClick)
                .heightIn(min = RowHeight)
                .padding(horizontal = RowPadding, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (conv.kind == ConvKind.GROUP) {
                GroupAvatar(seeds = groupSeeds, size = RowAvatarSize)
            } else {
                GlassAvatar(
                    name = title,
                    seed = conv.avatarSeed,
                    size = RowAvatarSize,
                    online = online,
                    glassSheen = false
                )
            }

            Column(
                Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        title,
                        color = LiuliColors.TextPrimary,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    if (conv.pinned) {
                        Icon(
                            LiuliIcons.Pin,
                            contentDescription = "已置顶",
                            tint = LiuliColors.TextTertiary,
                            modifier = Modifier.padding(start = 4.dp).size(13.dp)
                        )
                    }
                    if (conv.muted) {
                        Icon(
                            LiuliIcons.Mute,
                            contentDescription = "已免打扰",
                            tint = LiuliColors.TextTertiary,
                            modifier = Modifier.padding(start = 4.dp).size(13.dp)
                        )
                    }
                    Text(
                        TimeFmt.listStamp(conv.lastMessageAt),
                        color = LiuliColors.TextTertiary,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(start = 6.dp)
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        previewLine(conv, preview, sender, typing),
                        color = if (typing) LiuliColors.Accent else LiuliColors.TextSecondary,
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    if (conv.unread > 0) {
                        if (conv.muted) {
                            Box(
                                Modifier
                                    .padding(start = 8.dp)
                                    .size(8.dp)
                                    .background(LiuliColors.TextTertiary, CircleShape)
                            )
                        } else {
                            GlassBadge(conv.unread, Modifier.padding(start = 8.dp))
                        }
                    }
                }
            }
        }

        GlassDivider(
            modifier = Modifier.align(Alignment.BottomStart),
            inset = RowSeparatorInset
        )
    }
}

@Composable
private fun RevealAction(
    label: String,
    background: Color,
    foreground: Color,
    width: Dp,
    onClick: () -> Unit
) {
    Box(
        Modifier
            .fillMaxHeight()
            .width(width)
            .background(background)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(label, color = foreground, style = MaterialTheme.typography.labelLarge)
    }
}

/** Drafts and typing states replace the message text exactly like the list expects. */
private fun previewLine(conv: Conversation, preview: String, sender: String?, typing: Boolean): String = when {
    typing && conv.kind == ConvKind.DIRECT -> "对方正在输入…"
    typing -> "有人正在输入…"
    conv.draft.isNotBlank() -> "[草稿] ${conv.draft}"
    preview.isBlank() -> "还没有消息，打个招呼吧"
    sender != null -> "$sender：$preview"
    else -> preview
}
