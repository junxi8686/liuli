package com.liuli.btchat.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.liuli.btchat.ui.glass.LiuliColors
import com.liuli.btchat.ui.glass.LiuliIcons
import com.liuli.btchat.ui.glass.Radii

/**
 * 「按住说话」条 —— 微信式的录音手势全在这里。
 *
 * 手势用 [pointerInput] + `awaitPointerEventScope` 手写，因为要同时满足三件事：
 *  1. **按下即开始**（不需要长按等待，微信按下就有反馈）；
 *  2. **上滑取消**：手指从按下点向上滑过 [CancelThreshold]，文案和边框变红，
 *     松手时回调 `cancelled = true`；
 *  3. **滑出组件范围也要继续跟手**：指针一旦被这个节点捕获，移动事件会照常送到这里，
 *     所以不用管坐标是否还在条内 —— 只在收到 up/cancel 时结束。
 *
 * 录音本身由 `media/VoiceRecorder` 负责（它不会自己申请权限），这个组件只负责
 * 手势与状态回传：`onPressStart()` / `onRelease(cancelled)`。
 *
 * @param recording 是否正在录（由上层持有 recorder 状态）。
 * @param elapsedMs 已录毫秒数（上层按 50ms 轮询 recorder）。
 * @param amplitude 0..100 的实时音量（上层轮询 recorder.amplitude）。
 * @param enabled 链路/权限不可用时禁用整条。
 * @param onPressStart 手指按下。
 * @param onRelease 手指抬起或手势被系统取消；`cancelled = true` 表示要丢弃这次录音。
 */
@Composable
fun VoiceRecordBar(
    recording: Boolean,
    elapsedMs: Long,
    amplitude: Int,
    enabled: Boolean,
    onPressStart: () -> Unit,
    onRelease: (cancelled: Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    var armed by remember { mutableStateOf(false) }
    val history = remember { mutableStateListOf<Int>() }

    // 颜色全部先取出来：@Composable get() 的调色板不能在手势 lambda 里读。
    val surface = LiuliColors.Surface
    val surfaceAlt = LiuliColors.SurfaceAlt
    val separator = LiuliColors.Separator
    val accent = LiuliColors.Accent
    val danger = LiuliColors.Danger
    val textPrimary = LiuliColors.TextPrimary
    val textSecondary = LiuliColors.TextSecondary

    val density = LocalDensity.current
    val cancelThresholdPx = with(density) { CancelThreshold.toPx() }

    // 波形条要滚起来才像活的：把最近 18 个采样推进队列。
    LaunchedEffect(amplitude, recording) {
        if (recording) {
            history.add(amplitude)
            while (history.size > Bars) history.removeAt(0)
        } else {
            history.clear()
        }
    }

    val shape = RoundedCornerShape(Radii.field)
    Box(
        modifier
            .fillMaxWidth()
            .height(40.dp)
            .clip(shape)
            .background(if (recording) surfaceAlt else surface)
            .border(
                width = if (recording && armed) 1.4.dp else 0.8.dp,
                color = when {
                    recording && armed -> danger
                    recording -> accent.copy(alpha = 0.55f)
                    else -> separator
                },
                shape = shape
            )
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                awaitPointerEventScope {
                    while (true) {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val startY = down.position.y
                        down.consume()
                        armed = false
                        onPressStart()

                        var cancelled = false
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id }
                            if (change == null) {
                                // 指针被系统/父级收走：当取消处理，别把半截文件发出去。
                                cancelled = true
                                break
                            }
                            if (!change.pressed) {
                                change.consume()
                                break
                            }
                            // 上滑距离决定是否进入「取消」区
                            armed = (startY - change.position.y) > cancelThresholdPx
                            change.consume()
                        }
                        cancelled = cancelled || armed
                        armed = false
                        onRelease(cancelled)
                    }
                }
            },
        contentAlignment = Alignment.Center
    ) {
        if (!recording) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    LiuliIcons.Mic,
                    contentDescription = null,
                    tint = if (enabled) textPrimary else textSecondary.copy(alpha = 0.5f),
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    "按住 说话",
                    color = if (enabled) textPrimary else textSecondary.copy(alpha = 0.5f),
                    fontSize = 15.5.sp
                )
            }
        } else {
            Row(
                Modifier.padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    "${(elapsedMs / 1000).coerceAtLeast(0)}\"",
                    color = if (armed) danger else accent,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium
                )
                LiveWaveform(
                    history = history,
                    tint = if (armed) danger else accent,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    if (armed) "松开手指，取消发送" else "上滑取消",
                    color = if (armed) danger else textSecondary,
                    fontSize = 12.5.sp
                )
            }
        }
    }
}

/** 录音中的实时波形：18 根竖条，高度跟着音量走。 */
@Composable
private fun LiveWaveform(
    history: List<Int>,
    tint: Color,
    modifier: Modifier = Modifier
) {
    val values = remember(history.size, history.lastOrNull()) {
        // 补位保证总是 18 根，新的在右边
        val pad = List((Bars - history.size).coerceAtLeast(0)) { 0 }
        (pad + history).takeLast(Bars)
    }
    Row(
        modifier.height(24.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        values.forEach { raw ->
            val h = 3f + (raw.coerceIn(0, 100) / 100f) * 17f
            Box(
                Modifier
                    .width(2.5.dp)
                    .height(h.dp)
                    .background(tint.copy(alpha = 0.35f + 0.65f * (raw / 100f)), RoundedCornerShape(2.dp))
            )
        }
    }
}

/** 上滑多少算「取消」（微信大概是一个拇指的高度）。 */
private val CancelThreshold = 60.dp

/** 实时波形条数。 */
private const val Bars = 18
