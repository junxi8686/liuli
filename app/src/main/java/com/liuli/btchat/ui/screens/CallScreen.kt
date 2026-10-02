package com.liuli.btchat.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.liuli.btchat.bt.call.CallState
import com.liuli.btchat.core.Svc
import com.liuli.btchat.media.call.CallStats
import com.liuli.btchat.media.call.diagnostics
import com.liuli.btchat.ui.components.CallChip
import com.liuli.btchat.ui.components.CallControls
import com.liuli.btchat.ui.components.CallRoundButton
import com.liuli.btchat.ui.components.CallStatusLine
import com.liuli.btchat.ui.components.IncomingCallSheet
import com.liuli.btchat.ui.components.callTimerText
import com.liuli.btchat.ui.glass.GlassAvatar
import com.liuli.btchat.ui.glass.LiuliColors
import com.liuli.btchat.ui.glass.LiuliIcons
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * 全屏通话页（呼出 / 来电 / 通话中）。
 *
 * 这是一个**纯参数 composable**：状态从 `CallState` 进来，动作通过回调出去，
 * 由 `LiuliRoot` 挂在最上层（`state.busy` 时显示）。它自己不查 `Engine` 的状态机，
 * 只额外读两样诊断数据（远端画面与实测帧率），因为那是媒体层的私有面：
 *
 * ```
 *   val diag  = Engine.callMedia.diagnostics()          // null = 本机没装配媒体层
 *   val stats = diag?.stats                             // CallStats：真实计数
 *   val frame = diag?.remoteVideo                       // 对端最新解码帧
 * ```
 *
 * 三种形态：
 *  * **OUTGOING**：大头像 + 「正在呼叫…」+ 秒表 + 红色挂断；
 *  * **RINGING**：交给 [IncomingCallSheet]（响铃 + 震动 + 接听/拒绝）；
 *  * **ACTIVE**：语音=大头像 + 计时 +「已静音/对方已静音」；视频=远端画面铺满 +
 *    本地小窗（媒体层没有本地预览流，所以小窗只画一个诚实的占位，不假装是画面）；
 *    群聊=头像网格（不做「谁在说话」的假高亮，只显示真实静音状态）。
 *
 * 页面上没有任何 `glassSurface`：视频帧每秒都在换，任何 RenderEffect 都是白烧电。
 *
 * @param state 通话状态机快照（`phase == IDLE` 时不该被挂载）。
 * @param onAccept 接听。
 * @param onReject 拒绝。
 * @param onHangUp 挂断 / 取消呼叫。
 * @param onToggleMute 麦克风静音开关（传目标值）。
 * @param onToggleCamera 摄像头开关（传目标值）。
 * @param onSwitchCamera 前后摄像头翻转。
 * @param onDismissEnded 通话已结束、页面可以关掉时回调（`LiuliRoot` 用它 reset）。
 */
@Composable
fun CallScreen(
    state: CallState,
    onAccept: () -> Unit,
    onReject: () -> Unit,
    onHangUp: () -> Unit,
    onToggleMute: (Boolean) -> Unit,
    onToggleCamera: (Boolean) -> Unit,
    onSwitchCamera: () -> Unit,
    onDismissEnded: () -> Unit = {}
) {
    if (state.phase == CallState.Phase.RINGING) {
        IncomingCallSheet(state = state, onAccept = onAccept, onReject = onReject)
    } else {
        ActiveCallBody(
            state = state,
            onAccept = onAccept,
            onReject = onReject,
            onHangUp = onHangUp,
            onToggleMute = onToggleMute,
            onToggleCamera = onToggleCamera,
            onSwitchCamera = onSwitchCamera,
            onDismissEnded = onDismissEnded
        )
    }
}

@Composable
private fun ActiveCallBody(
    state: CallState,
    onAccept: () -> Unit,
    onReject: () -> Unit,
    onHangUp: () -> Unit,
    onToggleMute: (Boolean) -> Unit,
    onToggleCamera: (Boolean) -> Unit,
    onSwitchCamera: () -> Unit,
    onDismissEnded: () -> Unit
) {
    val context = LocalContext.current

    // 实测诊断：媒体层没装配时为 null，下面一律走「如实提示」分支。
    val diag = remember(state.callId) { com.liuli.btchat.bt.Engine.callMedia.diagnostics() }
    val emptyStats = remember { MutableStateFlow(CallStats()) }
    val stats by (diag?.stats ?: emptyStats).collectAsState()
    val remoteFrame by (diag?.remoteVideo ?: MutableStateFlow<android.graphics.Bitmap?>(null))
        .collectAsState()
    val frameImage = remember(remoteFrame) { remoteFrame?.asImageBitmap() }

    // 计时：0.5 秒一跳，不要每帧刷新文本。
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(state.phase, state.since) {
        while (true) {
            nowMs = System.currentTimeMillis()
            delay(500)
        }
    }

    // 权限：麦克风（通话必需）+ 摄像头（视频通话）。
    // CAMERA 必须在**视频通话一开始**就申请（不是等用户点开关摄像头）——
    // 用户反馈「他不会申请那个摄像头权限」，根因是 manifest 里没声明 CAMERA
    // （未声明的权限 Android 直接回 denied 且不弹窗，lead 已补上）。
    var micDenied by remember { mutableStateOf(false) }
    var cameraDenied by remember { mutableStateOf(false) }
    val micLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> micDenied = !granted }
    val cameraLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> cameraDenied = !granted }
    LaunchedEffect(state.phase, state.video) {
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            micLauncher.launch(Manifest.permission.RECORD_AUDIO)
        } else {
            micDenied = false
        }
        if (state.video) {
            if (context.checkSelfPermission(Manifest.permission.CAMERA) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                cameraLauncher.launch(Manifest.permission.CAMERA)
            } else {
                cameraDenied = false
            }
        }
    }

    val incoming = !state.outgoing
    val established = state.established
    val group = state.participants.size > 1
    val isVideo = state.video

    val subtitle = when {
        state.phase == CallState.Phase.ENDED -> state.error ?: "通话已结束"
        established -> callTimerText(state.since, nowMs)
        incoming -> "来电"
        else -> "正在呼叫…"
    }

    val warning = when {
        !state.mediaReady -> "仅信令可用，本机没有声音"
        micDenied -> "没有麦克风权限：对方听不到你"
        cameraDenied && isVideo -> "没有相机权限：对方看不到你"
        state.mediaError != null -> state.mediaError
        else -> null
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(if (isVideo && established) Color.Black else LiuliColors.Bg)
    ) {
        // 视频画面上，上下两端各压一层很淡的渐变，保证白色文字始终读得清。
        // 注意：这两层必须在所有内容之前，否则会把控制条也一起压暗。
        if (isVideo && established) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(140.dp)
                    .align(Alignment.TopCenter)
                    .background(
                        Brush.verticalGradient(
                            listOf(Color.Black.copy(alpha = 0.50f), Color.Transparent)
                        )
                    )
            )
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(200.dp)
                    .align(Alignment.BottomCenter)
                    .background(
                        Brush.verticalGradient(
                            listOf(Color.Transparent, Color.Black.copy(alpha = 0.55f))
                        )
                    )
            )
        }

        // ------------------------------------------------------------ 画面层
        when {
            // 视频通话已接通：远端满屏
            isVideo && established && frameImage != null -> Image(
                bitmap = frameImage,
                contentDescription = "对方画面",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )

            // 视频通话已接通但还没收到画面：如实说「等待对方画面」
            isVideo && established -> Box(
                Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    GlassAvatar(
                        name = state.peerName,
                        seed = state.peerName.hashCode(),
                        size = 96.dp,
                        glassSheen = false
                    )
                    Text(
                        "等待对方画面…",
                        color = Color.White.copy(alpha = 0.75f),
                        fontSize = 14.sp,
                        modifier = Modifier.padding(top = 12.dp)
                    )
                }
            }

            // 语音通话 / 呼叫中：大头像（群聊换成网格）
            group -> GroupGrid(state)
            else -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                GlassAvatar(
                    name = state.peerName,
                    seed = state.peerName.hashCode(),
                    size = 132.dp,
                    glassSheen = false
                )
            }
        }

        // ------------------------------------------------------------ 顶部信息
        Column(
            Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(top = 18.dp)
        ) {
            CallStatusLine(
                title = state.peerName.ifBlank { "通话" },
                subtitle = subtitle,
                warning = warning
            )

            // 权限被拒后的**可重试**入口：点一下重新弹系统权限框；被「不再询问」挡住时
            // 走系统设置页。不做「一次拒绝就永久静默」。
            if (micDenied || (cameraDenied && isVideo)) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp),
                    horizontalArrangement = Arrangement.Center
                ) {
                    if (micDenied) {
                        PermissionChip(
                            text = "麦克风权限被拒 · 点这里重试",
                            onRetry = { micLauncher.launch(Manifest.permission.RECORD_AUDIO) }
                        )
                    }
                    if (cameraDenied && isVideo) {
                        if (micDenied) Spacer(Modifier.size(6.dp))
                        PermissionChip(
                            text = "摄像头权限被拒 · 点这里重试",
                            onRetry = { cameraLauncher.launch(Manifest.permission.CAMERA) }
                        )
                    }
                }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp),
                    horizontalArrangement = Arrangement.Center
                ) {
                    PermissionChip(
                        text = "去系统设置里授权",
                        onRetry = { openAppSettings(context) }
                    )
                }
            }
            // 如实标注实测质量与静音状态（不写死帧率文案，用媒体层算出来的）
            val note = stats.videoQualityNote
            if (note != null || state.peerMuted || state.muted) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp),
                    horizontalArrangement = Arrangement.Center
                ) {
                    if (note != null) {
                        CallChip(note)
                        Spacer(Modifier.size(6.dp))
                    }
                    if (state.muted) {
                        CallChip("已静音")
                        Spacer(Modifier.size(6.dp))
                    }
                    if (state.peerMuted) CallChip("对方已静音")
                }
            }
        }

        // ------------------------------------------------------------ 本地小窗 + 底部控制
        Column(
            Modifier
                .fillMaxSize()
                .navigationBarsPadding()
                .padding(bottom = 26.dp),
            verticalArrangement = Arrangement.Bottom,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp),
                horizontalArrangement = Arrangement.End
            ) {
                if (isVideo && established) {
                    LocalPreviewPlaceholder(
                        cameraOn = state.cameraOn,
                        modifier = Modifier.padding(bottom = 14.dp)
                    )
                }
            }

            Spacer(Modifier.weight(1f))

            if (state.phase == CallState.Phase.ENDED) {
                CallRoundButton(
                    icon = LiuliIcons.Back,
                    label = "返回",
                    onClick = onDismissEnded,
                    background = LiuliColors.SurfaceAlt,
                    tint = LiuliColors.TextPrimary
                )
            } else {
                CallControls(
                    video = isVideo,
                    muted = state.muted,
                    cameraOn = state.cameraOn,
                    onToggleMute = { onToggleMute(!state.muted) },
                    onToggleCamera = { onToggleCamera(!state.cameraOn) },
                    onSwitchCamera = onSwitchCamera,
                    onHangUp = onHangUp
                )
            }
        }
    }
}

/**
 * 权限被拒后的可点小胶囊：点一下重新申请，或跳系统设置。
 *
 * 「一次拒绝就永久静默」是用户明确不接受的行为：这里永远给一条可点的退路，
 * 而且每次通话开始（`LaunchedEffect(phase, video)`）都会重新尝试申请。
 */
@Composable
private fun PermissionChip(text: String, onRetry: () -> Unit) {
    Text(
        text,
        color = Color.White,
        fontSize = 12.sp,
        modifier = Modifier
            .clip(androidx.compose.foundation.shape.CircleShape)
            .background(Color(0xCCB3261E))
            .clickable(onClick = onRetry)
            .padding(horizontal = 11.dp, vertical = 5.dp)
    )
}

/** 打开本应用的系统设置页，让用户手动开权限。 */
private fun openAppSettings(context: android.content.Context) {
    runCatching {
        val intent = android.content.Intent(
            android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS
        ).apply {
            data = android.net.Uri.fromParts("package", context.packageName, null)
            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }
}

/**
 * 通话入口 → Engine 的薄桥。
 *
 * `ChatEngine` 契约上没有通话方法（通话是 `bt.Engine` 这个实现类上的公开方法），
 * 所以这里直接落到实现上 —— 和 `ChatScreen` 里接收/拒绝附件的 `EngineBridge`
 * 是同一个处理方式，避免去改冻结的 core 契约。
 */
internal object CallBridge {

    /**
     * 发起通话。
     *
     * @return `null` 表示已经发起（页面会自动出现，因为 `CallState.phase` 变成 OUTGOING）；
     *   非空字符串是失败原因，调用方应当把它提示给用户 —— 绝不静默失败。
     */
    fun startCall(convId: String, video: Boolean): String? {
        val ok = runCatching {
            com.liuli.btchat.bt.Engine.startCall(convId, video)
        }.getOrDefault(false)
        if (ok) return null
        return runCatching { com.liuli.btchat.bt.Engine.call.value.error }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: "无法发起通话：对方不在线或链路不可用"
    }

    /** 通话已结束、页面要收起来时清状态。 */
    fun resetCall() {
        runCatching { com.liuli.btchat.bt.Engine.resetCall() }
    }
}

/**
 * 本地画面小窗。
 *
 * 媒体层的诊断面只有 `remoteVideo`，**没有本地预览流**，所以这里不画假的自己：
 * 摄像头关着就写「摄像头已关闭」，开着就写「本地画面（本机不显示预览）」。
 */
@Composable
private fun LocalPreviewPlaceholder(cameraOn: Boolean, modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(width = 92.dp, height = 132.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0x66000000)),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            androidx.compose.material3.Icon(
                if (cameraOn) LiuliIcons.Camera else LiuliIcons.VideoOff,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.85f),
                modifier = Modifier.size(22.dp)
            )
            Text(
                if (cameraOn) "本地画面" else "摄像头已关闭",
                color = Color.White.copy(alpha = 0.8f),
                fontSize = 10.5.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(top = 6.dp, start = 6.dp, end = 6.dp)
            )
        }
    }
}

/**
 * 多人语音网格：2–4 人排成 2 列，每格头像 + 昵称 + 静音标记。
 *
 * 「谁在说话」**不做假高亮**：群里只有「对端是否静音」这一条真实信息
 * （`CallState.mutedPeers`），拿不到每个人的音量就不要假装。
 */
@Composable
private fun GroupGrid(state: CallState) {
    val me = Svc.settings.ensureIdentity()
    val others = state.participants
        .filter { it != me.myDeviceId }
        .map { id ->
            val contact = runCatching { Svc.store.contact(id) }.getOrNull()
            id to (contact?.display?.takeIf { it.isNotBlank() } ?: id.take(6))
        }
    val cells = listOf(me.myDeviceId to me.myName.ifBlank { "我" }) + others

    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(top = 86.dp, bottom = 150.dp)
            .padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.Center
    ) {
        cells.chunked(2).forEach { rowCells ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                rowCells.forEach { (id, name) ->
                    val mutedPeer = state.mutedPeers.contains(id)
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.weight(1f)
                    ) {
                        GlassAvatar(
                            name = name,
                            seed = name.hashCode(),
                            size = 78.dp,
                            glassSheen = false
                        )
                        Text(
                            name,
                            color = LiuliColors.TextPrimary,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                        if (mutedPeer) {
                            Text(
                                "已静音",
                                color = LiuliColors.TextTertiary,
                                fontSize = 11.5.sp,
                                modifier = Modifier.padding(top = 2.dp)
                            )
                        }
                    }
                }
                // 单数行补一个占位的 weight，保持两列对齐
                if (rowCells.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}
