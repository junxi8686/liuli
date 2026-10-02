package com.liuli.btchat.ui.screens

import android.net.Uri
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.AudioAttributes
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.liuli.btchat.core.Svc
import com.liuli.btchat.ui.glass.LiuliIcons
import java.io.File
import kotlinx.coroutines.delay

/**
 * 视频播放页（Media3 ExoPlayer）。
 *
 * 全黑底 + 平涂控件：视频页不需要玻璃，画面本身才是主角，而且这里每一帧都要
 * 合成视频帧，再叠 RenderEffect 会直接掉帧。所以：
 *  * 播放器铺满全屏（`AndroidView` + `PlayerView`），黑底无衬底；
 *  * 顶部一条半透明黑栏（返回 + 标题），底部一排半透明黑圆按钮（暂停/播放、保存到相册）；
 *  * 不套 `GlassStage`（那会引入两层 backdrop 录制，对播放中的视频是纯开销）。
 *
 * 生命周期：
 *  * `ExoPlayer` 只建一次（`remember(path)`），`DisposableEffect` 出屏即 `release()`；
 *  * 页面进后台自动暂停，回到前台且原本在播就继续；
 *  * 返回时先 `pause()` 再 `onBack()`，避免退出动画期间还有声音。
 *
 * @param path 本地视频绝对路径。
 * @param title 顶栏标题（一般是文件名）。
 * @param onBack 返回聊天页。
 */
@OptIn(UnstableApi::class)
@Composable
fun VideoPlayerScreen(
    path: String,
    title: String,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val file = remember(path) { File(path) }
    val exists = remember(path) { file.isFile && file.length() > 0L }

    var playing by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }

    val exoPlayer = remember(path) {
        ExoPlayer.Builder(context).build().apply {
            setAudioAttributes(AudioAttributes.DEFAULT, true)
            if (exists) {
                setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
                prepare()
                playWhenReady = true
            }
        }
    }

    // 出屏即释放解码器与音频焦点。
    DisposableEffect(exoPlayer) {
        onDispose { exoPlayer.release() }
    }

    DisposableEffect(exoPlayer) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                playing = isPlaying
            }

            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                notice = "播放失败：${error.errorCodeName}"
            }
        }
        exoPlayer.addListener(listener)
        onDispose { exoPlayer.removeListener(listener) }
    }

    // 后台暂停，回前台续播。
    DisposableEffect(exoPlayer, lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> exoPlayer.pause()
                Lifecycle.Event.ON_START -> if (playing) exoPlayer.play()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(notice) {
        if (notice != null) {
            delay(2200)
            notice = null
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        if (exists) {
            AndroidView(
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        useController = true
                        controllerAutoShow = true
                        resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                        setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
                        player = exoPlayer
                    }
                },
                update = { view ->
                    if (view.player !== exoPlayer) view.player = exoPlayer
                },
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    LiuliIcons.Warning,
                    contentDescription = null,
                    tint = Color.White.copy(alpha = 0.85f),
                    modifier = Modifier.size(38.dp)
                )
                Spacer(Modifier.size(12.dp))
                Text(
                    "视频不见了",
                    color = Color.White,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    "文件可能已经被清理：${file.name}",
                    color = Color.White.copy(alpha = 0.6f),
                    fontSize = 13.sp,
                    modifier = Modifier.padding(top = 6.dp)
                )
                Text(
                    "返回",
                    color = Color.White,
                    fontSize = 15.sp,
                    modifier = Modifier
                        .padding(top = 22.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color.White.copy(alpha = 0.16f))
                        .clickable(onClick = onBack)
                        .padding(horizontal = 24.dp, vertical = 9.dp)
                )
            }
        }

        // 顶部：返回 + 标题
        Row(
            Modifier
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.45f))
                .statusBarsPadding()
                .padding(horizontal = 6.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .clickable {
                        exoPlayer.pause()
                        onBack()
                    },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    LiuliIcons.Back,
                    contentDescription = "返回",
                    tint = Color.White,
                    modifier = Modifier.size(22.dp)
                )
            }
            Text(
                title.ifBlank { file.name },
                color = Color.White,
                fontSize = 16.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 6.dp)
            )
        }

        if (notice != null) {
            AnimatedVisibility(
                visible = true,
                modifier = Modifier.align(Alignment.TopCenter),
                enter = fadeIn(tween(150)),
                exit = fadeOut(tween(180))
            ) {
                Text(
                    notice.orEmpty(),
                    color = Color.White,
                    fontSize = 13.5.sp,
                    modifier = Modifier
                        .statusBarsPadding()
                        .padding(top = 62.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color(0xE6000000))
                        .padding(horizontal = 14.dp, vertical = 8.dp)
                )
            }
        }

        // 底部：暂停/播放 + 保存到相册
        if (exists) {
            Row(
                Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(horizontal = 14.dp)
                    .padding(bottom = 18.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    Modifier
                        .size(46.dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.18f))
                        .clickable {
                            if (exoPlayer.isPlaying) exoPlayer.pause() else exoPlayer.play()
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        if (playing) LiuliIcons.Pause else LiuliIcons.Play,
                        contentDescription = if (playing) "暂停" else "播放",
                        tint = Color.White,
                        modifier = Modifier.size(24.dp)
                    )
                }
                Box(
                    Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color.White.copy(alpha = 0.18f))
                        .clickable {
                            val saved = runCatching {
                                Svc.media.exportToGallery(file, "video/mp4")
                            }.getOrDefault(false)
                            notice = if (saved) "已保存到相册" else "保存失败"
                        }
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            LiuliIcons.Download,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(7.dp))
                        Text("保存到相册", color = Color.White, fontSize = 15.sp)
                    }
                }
            }
        }
    }
}
