package com.liuli.btchat.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.liuli.btchat.core.Message
import com.liuli.btchat.core.MsgKind
import com.liuli.btchat.ui.glass.GlassActionSheet
import com.liuli.btchat.ui.glass.LiuliIcons
import com.liuli.btchat.ui.glass.SheetAction

/** 长按菜单当前停在哪一层。 */
private enum class MenuStage { Main, Delete }

/**
 * 长按消息后的操作菜单（电报式）。
 *
 * 两层结构：
 *  * **主菜单**：复制（仅文本）/ 引用 / 转发 / 收藏（已收藏显示「取消收藏」）/ 多选 /
 *    撤回（自己发的且在 2 分钟内）/ 删除；
 *  * **删除层**：`删除（仅我可见）` → [com.liuli.btchat.core.ChatStore.deleteMessage]；
 *    `删除（双方）` → [com.liuli.btchat.core.ChatEngine.deleteForEveryone]，只有自己发的
 *    才给这个选项；另有「返回」回到主菜单。
 *
 * 这是一个**菜单**不是页面，所以用 `GlassActionSheet`（弹层是整页仅剩的玻璃之一）。
 *
 * @param message 正在操作的消息，`null` 表示菜单关闭。
 */
@Composable
fun MessageActionSheet(
    message: Message?,
    onDismiss: () -> Unit,
    onCopy: (Message) -> Unit,
    onQuote: (Message) -> Unit,
    onForward: (Message) -> Unit,
    onToggleStar: (Message) -> Unit,
    onMultiSelect: (Message) -> Unit,
    onRecall: (Message) -> Unit,
    onDeleteLocal: (Message) -> Unit,
    onDeleteBoth: (Message) -> Unit,
    modifier: Modifier = Modifier
) {
    var stage by remember(message?.id) { mutableStateOf(MenuStage.Main) }

    val actions: List<SheetAction> = buildList {
        val m = message
        if (m != null) {
            when (stage) {
                MenuStage.Main -> {
                    if (m.kind == MsgKind.TEXT && m.text.isNotBlank()) {
                        add(
                            SheetAction(
                                icon = LiuliIcons.Copy,
                                label = "复制",
                                onClick = { onDismiss(); onCopy(m) }
                            )
                        )
                    }
                    add(
                        SheetAction(
                            icon = LiuliIcons.ChatAlt,
                            label = "引用",
                            description = "回复这条消息",
                            onClick = { onDismiss(); onQuote(m) }
                        )
                    )
                    add(
                        SheetAction(
                            icon = LiuliIcons.Share,
                            label = "转发",
                            description = "选择会话发出去",
                            onClick = { onDismiss(); onForward(m) }
                        )
                    )
                    add(
                        SheetAction(
                            icon = LiuliIcons.Star,
                            label = if (m.starred) "取消收藏" else "收藏",
                            description = if (m.starred) "从我的收藏里移除" else "存进我的收藏",
                            onClick = { onDismiss(); onToggleStar(m) }
                        )
                    )
                    add(
                        SheetAction(
                            icon = LiuliIcons.Checked,
                            label = "多选",
                            description = "批量转发 / 收藏 / 删除",
                            onClick = { onDismiss(); onMultiSelect(m) }
                        )
                    )
                    if (m.canRecall) {
                        add(
                            SheetAction(
                                icon = LiuliIcons.Refresh,
                                label = "撤回",
                                description = "两分钟内可以撤回",
                                onClick = { onDismiss(); onRecall(m) }
                            )
                        )
                    }
                    add(
                        SheetAction(
                            icon = LiuliIcons.Delete,
                            label = "删除",
                            description = "选择只删本机还是双方都删",
                            danger = true,
                            onClick = { stage = MenuStage.Delete }
                        )
                    )
                }

                MenuStage.Delete -> {
                    add(
                        SheetAction(
                            icon = LiuliIcons.Delete,
                            label = "删除（仅我可见）",
                            description = "对方那边还留着",
                            danger = true,
                            onClick = { onDismiss(); onDeleteLocal(m) }
                        )
                    )
                    if (m.outgoing) {
                        add(
                            SheetAction(
                                icon = LiuliIcons.Cancel,
                                label = "删除（双方）",
                                description = "会通知对方一起删掉",
                                danger = true,
                                onClick = { onDismiss(); onDeleteBoth(m) }
                            )
                        )
                    }
                    add(
                        SheetAction(
                            icon = LiuliIcons.Back,
                            label = "返回",
                            onClick = { stage = MenuStage.Main }
                        )
                    )
                }
            }
        }
    }

    GlassActionSheet(
        visible = message != null,
        onDismiss = onDismiss,
        title = when (stage) {
            MenuStage.Main -> messageTitle(message)
            MenuStage.Delete -> "删除这条消息"
        },
        actions = actions,
        modifier = modifier
    )
}

private fun messageTitle(m: Message?): String? = when {
    m == null -> null
    m.recalled -> "已撤回的消息"
    m.kind == MsgKind.IMAGE -> "图片"
    m.kind == MsgKind.VIDEO -> "视频"
    m.kind == MsgKind.FILE -> m.attachment?.fileName ?: "文件"
    else -> null
}
