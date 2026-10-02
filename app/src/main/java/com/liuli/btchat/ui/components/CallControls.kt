package com.liuli.btchat.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.liuli.btchat.ui.glass.LiuliColors
import com.liuli.btchat.ui.glass.LiuliIcons
import com.liuli.btchat.ui.glass.pressScale

/**
 * 通话页的圆形按钮。
 *
 * 通话界面是「全屏深色/浅色画面 + 一圈按钮」，所以这里全部平涂：没有玻璃、没有阴影，
 * 只有圆底 + 图标 + 可选的一行小字。颜色由调用方传进来（调色板是 `@Composable get()`，
 * 不能在非 composable 处读）。
 *
 * @param active `true` 表示这个开关当前是「开」的状态（例如已静音），会画一层强调底。
 * @param slash `true` 时在图标上叠一道斜线（静音/关摄像头这种「否定」语义）。
 */
@Composable
fun CallRoundButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    background: Color,
    modifier: Modifier = Modifier,
    tint: Color = Color.White,
    size: Dp = 60.dp,
    enabled: Boolean = true,
    active: Boolean = false,
    activeColor: Color = Color.Transparent,
    slash: Boolean = false
) {
    val interaction = remember { MutableInteractionSource() }
    Column(
        modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            Modifier
                .size(size)
                .pressScale(interaction, 0.92f)
                .clip(CircleShape)
                .background(if (active) activeColor else background)
                .clickable(
                    interactionSource = interaction,
                    indication = null,
                    enabled = enabled,
                    onClick = onClick
                )
                .then(
                    if (slash) {
                        Modifier.drawBehind {
                            val inset = this.size.minDimension * 0.26f
                            drawLine(
                                color = tint,
                                start = Offset(inset, this.size.height - inset),
                                end = Offset(this.size.width - inset, inset),
                                strokeWidth = this.size.minDimension * 0.085f,
                                cap = StrokeCap.Round
                            )
                        }
                    } else {
                        Modifier
                    }
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                icon,
                contentDescription = label,
                tint = tint.copy(alpha = if (enabled) 1f else 0.4f),
                modifier = Modifier.size(size * 0.44f)
            )
        }
        Text(
            label,
            color = if (enabled) LiuliColors.TextPrimary else LiuliColors.TextTertiary,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(top = 7.dp)
        )
    }
}

/**
 * 通话控制条：静音 / 开关摄像头 / 翻转摄像头 / 挂断。
 *
 * 群语音和单聊语音都是这一条；视频通话时中间多两个摄像头按钮。挂断永远在最后、
 * 底最大最红。
 *
 * @param speakerOn 免提状态；`onToggleSpeaker` 为 `null` 时**不显示**这个按钮 ——
 *   media 层没有暴露音频路由，宁可不放，也不放一个按了没反应的假开关。
 */
@Composable
fun CallControls(
    video: Boolean,
    muted: Boolean,
    cameraOn: Boolean,
    onToggleMute: () -> Unit,
    onToggleCamera: () -> Unit,
    onSwitchCamera: () -> Unit,
    onHangUp: () -> Unit,
    modifier: Modifier = Modifier,
    speakerOn: Boolean = false,
    onToggleSpeaker: (() -> Unit)? = null
) {
    // 颜色先取出来：drawBehind / 小 lambda 里不能再读 @Composable get()
    val surface = LiuliColors.Surface
    val surfaceAlt = LiuliColors.SurfaceAlt
    val danger = LiuliColors.Danger
    val accent = LiuliColors.Accent
    val textSecondary = LiuliColors.TextSecondary
    val onScrim = Color.White

    Row(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        CallRoundButton(
            icon = if (muted) LiuliIcons.MicOff else LiuliIcons.Mic,
            label = if (muted) "已静音" else "静音",
            onClick = onToggleMute,
            background = surfaceAlt,
            tint = if (muted) onScrim else textSecondary,
            active = muted,
            activeColor = Color(0x33FFFFFF)
        )

        if (video) {
            CallRoundButton(
                icon = if (cameraOn) LiuliIcons.Video else LiuliIcons.VideoOff,
                label = if (cameraOn) "关摄像头" else "开摄像头",
                onClick = onToggleCamera,
                background = surfaceAlt,
                tint = if (cameraOn) onScrim else textSecondary,
                active = !cameraOn,
                activeColor = Color(0x33FFFFFF)
            )
            CallRoundButton(
                icon = LiuliIcons.CameraFlip,
                label = "翻转",
                onClick = onSwitchCamera,
                background = surfaceAlt,
                tint = onScrim
            )
        }

        if (onToggleSpeaker != null) {
            CallRoundButton(
                icon = LiuliIcons.Speed,
                label = "免提",
                onClick = onToggleSpeaker,
                background = surfaceAlt,
                tint = onScrim,
                active = speakerOn,
                activeColor = accent.copy(alpha = 0.35f)
            )
        }

        CallRoundButton(
            icon = LiuliIcons.CallEnd,
            label = "挂断",
            onClick = onHangUp,
            background = danger,
            tint = Color.White,
            size = 68.dp
        )
    }
}

/** 通话页顶部的状态条：名字 + 计时 + 一句如实的状态/错误说明。 */
@Composable
fun CallStatusLine(
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    warning: String? = null
) {
    Column(
        modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            title,
            color = LiuliColors.TextPrimary,
            fontSize = 22.sp,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(Modifier.height(6.dp))
        Text(
            subtitle,
            color = LiuliColors.TextSecondary,
            fontSize = 14.sp
        )
        if (warning != null) {
            Text(
                warning,
                color = LiuliColors.Warn,
                fontSize = 12.5.sp,
                modifier = Modifier.padding(top = 8.dp, start = 16.dp, end = 16.dp)
            )
        }
    }
}

/** 通话计时文本：`00:07` / `1:02:33`。 */
fun callTimerText(sinceMs: Long, nowMs: Long): String {
    val total = ((nowMs - sinceMs).coerceAtLeast(0L)) / 1000L
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
}

/** 通话页上的小胶囊标注（低帧率提示、静音提示…）。 */
@Composable
fun CallChip(
    text: String,
    modifier: Modifier = Modifier,
    background: Color = Color(0x99000000),
    contentColor: Color = Color.White
) {
    Text(
        text,
        color = contentColor,
        fontSize = 12.sp,
        modifier = modifier
            .clip(CircleShape)
            .background(background)
            .padding(horizontal = 10.dp, vertical = 5.dp)
    )
}
