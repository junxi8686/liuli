package com.liuli.btchat.ui.components

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.liuli.btchat.core.Quote
import com.liuli.btchat.media.RecordedVoice
import com.liuli.btchat.media.VoicePlayer
import com.liuli.btchat.media.VoiceRecorder
import com.liuli.btchat.media.rememberVoiceRecorder
import com.liuli.btchat.ui.glass.LiuliColors
import com.liuli.btchat.ui.glass.LiuliIcons
import com.liuli.btchat.ui.glass.Radii
import com.liuli.btchat.ui.glass.pressScale
import kotlinx.coroutines.delay

/**
 * 底部输入栏（微信式），带「按住说话」。
 *
 * 它是一条**平的白色栏**：顶部一根发丝线，不是悬浮胶囊，也不做模糊 —— 输入栏每一帧
 * 都在键盘旁边重绘，玻璃在这里只有成本没有收益。整条栏：
 * ```
 *   ─────────────────────────────── 发丝线
 *   [语音/键盘] 😊 │  [ #F2F3F5 输入框 ] │  ＋ / 发送
 *   [语音/键盘]    │  [  按住 说话     ] │  ＋          ← 语音模式
 * ```
 *  * 最左边是语音/键盘切换（微信那个麦克风按钮）；
 *  * 语音模式下中间换成 [VoiceRecordBar]：按下开始录、松手发送、上滑取消，
 *    60 秒自动结束，短于 1 秒丢弃并提示「说话时间太短」；
 *  * 权限由这里申请（recorder 自己从不弹窗），拒绝后再按会重新弹一次；
 *  * 切后台 / 被别的 App 抢麦克风 → `ON_STOP` 里干净取消，不留坏文件；
 *  * 右侧按钮会变形：文本为空时是白底发丝边的圆形「＋」（开媒体面板），
 *    有文本时变成微信绿实心「发送」；
 *  * 表情按钮展开一条浅灰表情条，点一下追加到文本末尾。
 *
 * @param value 当前草稿（由屏幕持有，屏幕负责节流上报"正在输入"）。
 * @param onValueChange 文本变化。
 * @param onSend 发送非空文本。
 * @param onOpenMedia 打开 `MediaSheet`。
 * @param enabled 链路不可用时置灰（仍可打字）。
 * @param quote 正在引用的消息（显示在输入框上方）。
 * @param onCancelQuote 取消引用。
 * @param onVoice 录完一段可以发送的语音（调用方交给 `engine.sendMedia`）。
 * @param onNotice 一句话提示（太短/取消/权限/中断），由屏幕浮层展示。
 */
@Composable
fun ChatInputBar(
    value: String,
    onValueChange: (String) -> Unit,
    onSend: () -> Unit,
    onOpenMedia: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    quote: Quote? = null,
    onCancelQuote: () -> Unit = {},
    onVoice: (RecordedVoice) -> Unit = {},
    onNotice: (String) -> Unit = {}
) {
    var emojiOpen by remember { mutableStateOf(false) }
    var voiceMode by remember { mutableStateOf(false) }
    var recording by remember { mutableStateOf(false) }
    var elapsedMs by remember { mutableStateOf(0L) }
    var amplitude by remember { mutableStateOf(0) }
    val blank = value.isBlank()

    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val recorder = rememberVoiceRecorder()

    // 录音期间按 50ms 采样音量与时长（recorder 的 getter 都是线程安全且不阻塞的）。
    LaunchedEffect(recording) {
        while (recording) {
            amplitude = recorder.amplitude
            elapsedMs = recorder.elapsedMs
            delay(50)
        }
        amplitude = 0
        elapsedMs = 0L
    }

    // 60 秒到顶：recorder 自己停下来并回调，这里负责把成品发出去。
    DisposableEffect(recorder) {
        recorder.onAutoStop = { take ->
            recording = false
            when {
                take == null -> onNotice("录音失败，请重试")
                take.durationMs < VoiceRecorder.MIN_VOICE_MS -> {
                    recorder.discard(take)
                    onNotice("说话时间太短")
                }

                else -> onVoice(take)
            }
        }
        onDispose {
            recorder.onAutoStop = null
            recorder.release()
        }
    }

    // 切后台 / 锁屏 / 被别的 App 抢麦：干净收尾，不留半截文件。
    DisposableEffect(lifecycleOwner, recorder) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP && recorder.isRecording) {
                recorder.cancel()
                recording = false
                onNotice("录音已中断")
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        onNotice(if (granted) "再按住一次就能录了" else "没有麦克风权限，发不了语音")
    }

    fun beginVoice() {
        if (!enabled || recorder.isRecording) return
        // 一边放语音一边录会互相盖住，先停播放。
        VoicePlayer.stop()
        val granted = context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) {
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        recording = recorder.start()
        if (!recording) onNotice("麦克风被占用，稍后再试")
    }

    fun endVoice(cancelled: Boolean) {
        if (!recorder.isRecording) {
            recording = false
            return
        }
        if (cancelled) {
            recorder.cancel()
            recording = false
            onNotice("已取消")
            return
        }
        // 先量一下按住了多久：轻点一下时 MediaRecorder 连一帧都写不出来，
        // stop() 会返回 null —— 那种情况对用户来说就是「说话时间太短」。
        val heldMs = recorder.elapsedMs
        val take = recorder.stop()
        recording = false
        when {
            take == null && heldMs < VoiceRecorder.MIN_VOICE_MS -> onNotice("说话时间太短")
            take == null -> onNotice("录音失败，请重试")
            take.durationMs < VoiceRecorder.MIN_VOICE_MS -> {
                recorder.discard(take)
                onNotice("说话时间太短")
            }

            else -> onVoice(take)
        }
    }

    Column(
        modifier
            .fillMaxWidth()
            // 先画白底、再让出导航条：白色才会一直铺到系统导航条后面，
            // 而不是在导航条上方留一条透明缝（imePadding 由屏幕在外面加）。
            .background(LiuliColors.Surface)
            .navigationBarsPadding()
    ) {
        // 顶部发丝线
        Box(
            Modifier
                .fillMaxWidth()
                .height(0.6.dp)
                .background(LiuliColors.Separator)
        )

        // 引用条：长按「引用」后出现在输入框上方，发送时带上 quoteMsgId。
        if (quote != null && enabled) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(LiuliColors.SurfaceAlt)
                    .padding(horizontal = 12.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    Modifier
                        .width(2.dp)
                        .height(28.dp)
                        .background(LiuliColors.Accent, RoundedCornerShape(1.dp))
                )
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        "引用 ${quote.senderName.takeIf { it.isNotBlank() } ?: "对方"}",
                        color = LiuliColors.TextSecondary,
                        fontSize = 11.5.sp
                    )
                    Text(
                        quote.preview,
                        color = LiuliColors.TextPrimary,
                        fontSize = 12.5.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Box(
                    Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .clickable(onClick = onCancelQuote),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        LiuliIcons.Close,
                        contentDescription = "取消引用",
                        tint = LiuliColors.TextTertiary,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }

        AnimatedVisibility(
            visible = emojiOpen && !voiceMode,
            enter = fadeIn(tween(120)) + expandVertically(tween(160)),
            exit = fadeOut(tween(100)) + shrinkVertically(tween(140))
        ) {
            EmojiStrip(onPick = { onValueChange(value + it) })
        }

        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 7.dp),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            // 语音 / 键盘 切换（微信最左边那个按钮）
            Box(
                Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .clickable(enabled = enabled) {
                        voiceMode = !voiceMode
                        if (voiceMode) emojiOpen = false
                    },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (voiceMode) LiuliIcons.Keyboard else LiuliIcons.Mic,
                    contentDescription = if (voiceMode) "切换到键盘" else "切换到语音",
                    tint = if (voiceMode) LiuliColors.Accent else LiuliColors.TextSecondary,
                    modifier = Modifier.size(22.dp)
                )
            }

            if (voiceMode) {
                VoiceRecordBar(
                    recording = recording,
                    elapsedMs = elapsedMs,
                    amplitude = amplitude,
                    enabled = enabled,
                    onPressStart = { beginVoice() },
                    onRelease = { cancelled -> endVoice(cancelled) },
                    modifier = Modifier.weight(1f)
                )
            } else {
                // 表情
                Box(
                    Modifier
                        .size(34.dp)
                        .clip(CircleShape)
                        .clickable(enabled = enabled) { emojiOpen = !emojiOpen },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        LiuliIcons.Emoji,
                        contentDescription = "表情",
                        tint = if (emojiOpen) LiuliColors.Accent else LiuliColors.TextSecondary,
                        modifier = Modifier.size(23.dp)
                    )
                }

                // 输入框：浅灰底、无边框
                Box(
                    Modifier
                        .weight(1f)
                        .heightIn(min = 36.dp)
                        .background(LiuliColors.SurfaceSunken, RoundedCornerShape(Radii.field))
                        .padding(horizontal = 11.dp, vertical = 8.dp),
                    contentAlignment = Alignment.CenterStart
                ) {
                    if (value.isEmpty()) {
                        Text(
                            "发消息…",
                            color = LiuliColors.TextTertiary,
                            fontSize = 15.5.sp
                        )
                    }
                    BasicTextField(
                        value = value,
                        onValueChange = onValueChange,
                        enabled = enabled,
                        maxLines = 5,
                        textStyle = TextStyle(
                            fontSize = 15.5.sp,
                            lineHeight = 21.sp,
                            color = LiuliColors.TextPrimary
                        ),
                        cursorBrush = SolidColor(LiuliColors.Accent),
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 20.dp, max = 105.dp)
                    )
                }
            }

            // ＋ / 发送（语音模式下也是「＋」，跟微信一致）
            //
            // Both states live in the **same fixed slot**. The field beside this
            // is `weight(1f)`, so letting the button change width moved the
            // field with it: typing the first character swapped the 36dp 「＋」
            // for a ~61dp 「发送」 and the whole bar visibly jumped. A fixed slot
            // costs nothing and removes the jump entirely.
            Box(
                Modifier
                    .width(SendSlotWidth)
                    .height(SendSlotHeight),
                contentAlignment = Alignment.Center
            ) {
                if (voiceMode || blank) {
                    Box(
                        Modifier
                            .size(SendSlotHeight)
                            .clip(CircleShape)
                            .background(LiuliColors.Surface)
                            .border(0.8.dp, LiuliColors.Separator, CircleShape)
                            .clickable(enabled = enabled, onClick = onOpenMedia),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            LiuliIcons.Add,
                            contentDescription = "发送图片或文件",
                            tint = LiuliColors.TextPrimary,
                            modifier = Modifier.size(21.dp)
                        )
                    }
                } else {
                    val interaction = remember { MutableInteractionSource() }
                    Box(
                        Modifier
                            .pressScale(interaction, 0.94f)
                            .fillMaxSize()
                            .clip(RoundedCornerShape(6.dp))
                            .background(LiuliColors.Accent)
                            .clickable(
                                interactionSource = interaction,
                                indication = null,
                                enabled = enabled,
                                onClick = onSend
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            "发送",
                            color = LiuliColors.TextOnAccent,
                            fontSize = 15.sp
                        )
                    }
                }
            }
        }
    }
}

/** 展开的表情条：一条浅灰底的可横滑列表，平涂。 */
@Composable
private fun EmojiStrip(onPick: (String) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(LiuliColors.SurfaceAlt)
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 10.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Emojis.forEach { emoji ->
            val interaction = remember { MutableInteractionSource() }
            Text(
                emoji,
                fontSize = 22.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .pressScale(interaction, 0.86f)
                    .clip(RoundedCornerShape(6.dp))
                    .clickable(
                        interactionSource = interaction,
                        indication = null,
                        onClick = { onPick(emoji) }
                    )
                    .padding(horizontal = 7.dp, vertical = 4.dp)
            )
        }
    }
}

/**
 * The trailing ＋/发送 button sits in a slot of exactly this size in both
 * states, so swapping the two never resizes the text field beside it.
 */
private val SendSlotWidth = 58.dp
private val SendSlotHeight = 36.dp

private val Emojis = listOf(
    "😀", "😂", "🥰", "😎", "🤔", "😭", "😡", "😴",
    "👍", "👏", "🙏", "🤝", "💪", "🥳", "😅", "🤗",
    "❤️", "🔥", "✨", "🎉", "🌈", "🌙", "☕", "🍜"
)
