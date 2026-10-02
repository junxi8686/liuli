package com.liuli.btchat.ui.components

import android.content.Context
import android.media.AudioAttributes
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.liuli.btchat.bt.call.CallState
import com.liuli.btchat.ui.glass.GlassAvatar
import com.liuli.btchat.ui.glass.LiuliColors
import com.liuli.btchat.ui.glass.LiuliIcons
import kotlinx.coroutines.delay
import androidx.compose.runtime.LaunchedEffect

/**
 * 来电界面。
 *
 * 全屏深色蒙层 + 大头像 + 「邀请你语音/视频通话」+ 绿色接听 / 红色拒绝。
 *
 * **响铃与震动是真的**：`RingtoneManager` 取系统来电铃声（`USAGE_NOTIFICATION_RINGTONE`），
 * `Vibrator` 用「响 1s 停 1s」的循环波形；这个 composable 一进组合就开始，一被移除就停，
 * 所以接听/拒绝/超时任何一种离开方式都会立刻安静下来，不会留一个还在响的铃声。
 *
 * 免提没有做：media 层没有暴露音频路由，这里不放一个按了没反应的假开关。
 *
 * @param state 当前通话状态（用 `peerName` / `video` / `participants`）。
 * @param onAccept 绿色接听。
 * @param onReject 红色拒绝。
 */
@Composable
fun IncomingCallSheet(
    state: CallState,
    onAccept: () -> Unit,
    onReject: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var ringtone by remember { mutableStateOf<Ringtone?>(null) }
    var startedAt by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }

    LaunchedEffect(Unit) { startedAt = System.currentTimeMillis() }
    LaunchedEffect(Unit) {
        while (true) {
            nowMs = System.currentTimeMillis()
            delay(500)
        }
    }

    // 进组合就响铃 + 震动；离开立刻停。
    DisposableEffect(Unit) {
        ringtone = startRinging(context)
        val vibrator = startVibrating(context)
        onDispose {
            runCatching { ringtone?.stop() }
            ringtone = null
            runCatching { vibrator?.cancel() }
        }
    }

    val isGroup = state.participants.size > 1
    val kindLabel = if (state.video) "视频通话" else "语音通话"

    Box(
        modifier
            .fillMaxSize()
            .background(Color(0xFF101216)),
        contentAlignment = Alignment.Center
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(56.dp))

            Box(
                Modifier.size(112.dp),
                contentAlignment = Alignment.Center
            ) {
                GlassAvatar(
                    name = state.peerName,
                    seed = state.peerName.hashCode(),
                    size = 112.dp,
                    glassSheen = false
                )
            }

            Spacer(Modifier.height(18.dp))
            Text2(state.peerName.ifBlank { "对方" }, 24.sp, FontWeight.SemiBold, Color.White)
            Spacer(Modifier.height(8.dp))
            Text2(
                if (isGroup) "邀请你加入$kindLabel（${state.participants.size + 1} 人）"
                else "邀请你$kindLabel",
                15.sp,
                FontWeight.Normal,
                Color(0xFFB9BFC9)
            )
            Spacer(Modifier.height(10.dp))
            Text2(
                callTimerText(startedAt, nowMs) + " 已等待",
                12.5.sp,
                FontWeight.Normal,
                Color(0xFF8A9099)
            )

            if (!state.mediaReady) {
                Spacer(Modifier.height(14.dp))
                Text2(
                    "仅信令可用，本机没有声音",
                    12.5.sp,
                    FontWeight.Normal,
                    LiuliColors.Warn
                )
            }
            state.error?.let {
                Spacer(Modifier.height(8.dp))
                Text2(it, 12.5.sp, FontWeight.Normal, LiuliColors.Danger)
            }

            Spacer(Modifier.weight(1f))

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                CallRoundButton(
                    icon = LiuliIcons.CallEnd,
                    label = "拒绝",
                    onClick = onReject,
                    background = LiuliColors.Danger,
                    tint = Color.White,
                    size = 68.dp
                )
                CallRoundButton(
                    icon = if (state.video) LiuliIcons.Video else LiuliIcons.Call,
                    label = "接听",
                    onClick = onAccept,
                    background = LiuliColors.Accent,
                    tint = Color.White,
                    size = 68.dp
                )
            }
            Spacer(Modifier.height(44.dp))
        }
    }
}

/** 小工具：带颜色的文本（避免每个调用点都写一遍三个属性）。 */
@Composable
private fun Text2(text: String, size: androidx.compose.ui.unit.TextUnit, weight: FontWeight, color: Color) {
    androidx.compose.material3.Text(
        text,
        color = color,
        fontSize = size,
        fontWeight = weight,
        textAlign = TextAlign.Center
    )
}

/** 播系统来电铃声；返回 `null` 表示这台机器取不到铃声（不静默，UI 仍会亮、会震）。 */
private fun startRinging(context: Context): Ringtone? = runCatching {
    val uri = RingtoneManager.getActualDefaultRingtoneUri(context, RingtoneManager.TYPE_RINGTONE)
        ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
    val tone = RingtoneManager.getRingtone(context, uri) ?: return null
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) tone.isLooping = true
    runCatching {
        tone.audioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
    }
    tone.play()
    tone
}.getOrNull()

/** 循环震动：响 1 秒、停 1 秒（`repeat = 0` 表示无限循环）。 */
private fun startVibrating(context: Context): Vibrator? = runCatching {
    val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    } ?: return null
    val pattern = longArrayOf(0L, 1000L, 1000L)
    vibrator.vibrate(VibrationEffect.createWaveform(pattern, 0))
    vibrator
}.getOrNull()
