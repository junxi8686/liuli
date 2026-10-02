package com.liuli.btchat.ui.components

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.liuli.btchat.core.ImportedMedia
import com.liuli.btchat.core.Svc
import com.liuli.btchat.data.ChatPrefs
import com.liuli.btchat.media.MediaLimits
import com.liuli.btchat.media.VideoPipeline
import com.liuli.btchat.media.discardImportedFiles
import com.liuli.btchat.media.launchImagePicker
import com.liuli.btchat.media.launchVideoPicker
import com.liuli.btchat.media.rememberImagePicker
import com.liuli.btchat.media.rememberVideoPicker
import com.liuli.btchat.ui.glass.GlassActionSheet
import com.liuli.btchat.ui.glass.GlassConfirmDialog
import com.liuli.btchat.ui.glass.GlassLevel
import com.liuli.btchat.ui.glass.LiuliColors
import com.liuli.btchat.ui.glass.LiuliIcons
import com.liuli.btchat.ui.glass.Radii
import com.liuli.btchat.ui.glass.SheetAction
import com.liuli.btchat.ui.glass.glassSurface
import com.liuli.btchat.ui.screens.CallBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 媒体选择面板（聊天页「+」弹出的那个）。
 *
 * 负责四件事，全部真接在媒体管线上：
 *  1. 相册图片 / 相册视频 → [rememberImagePicker] / [rememberVideoPicker]；
 *  2. 拍摄照片 → 系统相机写进 `cache/camera/`，再用 FileProvider 交给
 *     [com.liuli.btchat.core.MediaVault.importImage] 压缩入库；
 *  3. 任意文件 → `OpenDocument`，走 [com.liuli.btchat.core.MediaVault.importAny]；
 *  4. 导入过程显示「正在压缩…」；**视频先探针**，超过 50 MB 立刻说明原因且一个字节都不落盘，
 *     15–50 MB 让用户确认传输时长（取消时把已导入的文件删掉，不留孤儿），其余直接交给上层发出去。
 *
 * @param visible 面板是否显示。
 * @param onDismiss 关闭。
 * @param onReady 已经压好、可以发送的媒体（上层调用 `Svc.engine.sendMedia`）。
 * @param onNotice 一句话提示（成功/失败都可以，由屏幕用玻璃胶囊浮层展示）。
 */
@Composable
fun MediaSheet(
    visible: Boolean,
    onDismiss: () -> Unit,
    onReady: (ImportedMedia) -> Unit,
    onNotice: (String) -> Unit,
    modifier: Modifier = Modifier,
    convId: String = "",
    isGroup: Boolean = false
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // 对讲机开关持久化在 ChatPrefs，写一次 revision 就变，这里跟着刷新
    val prefsRevision by ChatPrefs.revision.collectAsState()
    val walkieOn = remember(convId, prefsRevision) {
        convId.isNotBlank() && Walkie.enabled(convId)
    }

    var busyLabel by remember { mutableStateOf<String?>(null) }
    var dialog by remember { mutableStateOf<DialogBody?>(null) }

    /**
     * 导入一律在协程里做：ImagePipeline/VideoPipeline 自己切 IO，缩略图与压缩都不会卡 UI。
     *
     * 视频**先探针再导入**：体积超限时一个字节都不落盘，直接把原因说清楚；个别 provider 不报
     * size 时，媒体层会拷完后拒绝并删除半成品，这里用 [VideoPipeline.lastRejection] 取回真实
     * 原因 —— 对超限视频说「这个文件读不出来」是误导用户。
     *
     * @param cleanupFile 拍照写进 `cache/camera` 的全分辨率原图：导入结束（成功或失败）后即无用。
     */
    fun intake(uri: Uri, videoHint: Boolean, cleanupFile: File? = null) {
        if (busyLabel != null) {
            cleanupFile?.let { runCatching { it.delete() } }
            return
        }
        busyLabel = if (videoHint) "正在处理视频…" else "正在压缩…"
        scope.launch {
            var imported: ImportedMedia? = null
            var refused: String? = null
            try {
                if (videoHint) {
                    // probe() 打的是 ContentResolver + MediaMetadataRetriever，必须离开主线程；
                    // 导入本身（importVideo/importImage）在媒体层内部已经切 IO。
                    val info = withContext(Dispatchers.IO) { VideoPipeline.probe(context, uri) }
                    if (info != null && MediaLimits.exceedsLimit(info.sizeBytes)) {
                        refused = MediaLimits.check(info.sizeBytes) ?: TOO_LARGE_FALLBACK
                    } else {
                        imported = Svc.media.importVideo(uri)
                    }
                } else {
                    imported = Svc.media.importImage(uri)
                }
            } catch (e: Exception) {
                imported = null
                refused = null
            } finally {
                // 要用的字节已经复制进 files/media（或者本来就失败），缓存里那份不必再留。
                cleanupFile?.let { runCatching { it.delete() } }
            }
            busyLabel = null

            when {
                refused != null -> dialog = DialogBody("文件太大", refused, null, true)

                imported == null -> {
                    val rejection = VideoPipeline.lastRejection
                    dialog = if (rejection != null) {
                        DialogBody("文件太大", rejection.message, null, true)
                    } else {
                        DialogBody("导入失败", "这个文件读不出来，换一个试试。", null, false)
                    }
                }

                MediaLimits.exceedsLimit(imported.size) -> dialog = DialogBody(
                    "文件太大",
                    MediaLimits.check(imported.size) ?: TOO_LARGE_FALLBACK,
                    null,
                    true
                )

                MediaLimits.isWarning(imported.size) -> dialog = DialogBody(
                    "文件较大",
                    MediaLimits.check(imported.size) ?: "蓝牙传输会比较慢，确定继续吗？",
                    imported,
                    false
                )

                else -> {
                    onReady(imported)
                    onDismiss()
                }
            }
        }
    }

    fun intakeAny(uri: Uri) {
        if (busyLabel != null) return
        busyLabel = "正在导入…"
        scope.launch {
            val result = runCatching { Svc.media.importAny(uri) }.getOrNull()
            busyLabel = null
            if (result == null) {
                // 媒体层拒绝时给的是原话（超限的任意文件也会落到视频管线上）。
                val rejection = VideoPipeline.lastRejection
                dialog = if (rejection != null) {
                    DialogBody("文件太大", rejection.message, null, true)
                } else {
                    DialogBody("导入失败", "这个文件读不出来，换一个试试。", null, false)
                }
            } else if (MediaLimits.exceedsLimit(result.size)) {
                dialog = DialogBody("文件太大", MediaLimits.check(result.size) ?: "超过 50 MB 上限。", null, true)
            } else if (MediaLimits.isWarning(result.size)) {
                dialog = DialogBody("文件较大", MediaLimits.check(result.size) ?: "确定继续吗？", result, false)
            } else {
                onReady(result)
                onDismiss()
            }
        }
    }

    // ------------------------------------------------------------ 选择器
    val imagePicker = rememberImagePicker { uri -> intake(uri, videoHint = false) }
    val videoPicker = rememberVideoPicker { uri -> intake(uri, videoHint = true) }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) intakeAny(uri)
    }

    var cameraUri by remember { mutableStateOf<Uri?>(null) }
    var cameraFile by remember { mutableStateOf<File?>(null) }
    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val uri = cameraUri
        val shot = cameraFile
        cameraUri = null
        cameraFile = null
        releaseCameraGrant(context, uri)
        when {
            // 拍成了：把这次的原图交给导入，导入结束后它会删掉缓存里那份。
            ok && uri != null -> intake(uri, videoHint = false, cleanupFile = shot)
            // 取消或失败：没人要这张全分辨率原图，立刻删掉，别留在 cache 里。
            else -> shot?.let { runCatching { it.delete() } }
        }
    }

    fun shoot() {
        val dir = File(context.cacheDir, "camera")
        // 上一次拍照（包括用户取消、导入失败留下的）已经没有意义：导入会把要发的字节复制进
        // files/media。这里先清干净，再写这一张，cache/camera 永远不会堆积全分辨率原图。
        dir.listFiles()?.forEach { runCatching { it.delete() } }
        dir.mkdirs()
        val target = File(dir, "liuli_${System.currentTimeMillis()}.jpg")
        val uri = runCatching { Svc.media.shareUri(target) }.getOrNull()
        if (uri == null) {
            onNotice("无法创建拍照文件")
            return
        }
        // `TakePicture` 只往 Intent 里塞 EXTRA_OUTPUT，不带读权限标记；
        // 必须显式把写权限授给默认相机，否则对面打不开这个 content:// 地址。
        grantCameraWrite(context, uri)
        cameraUri = uri
        cameraFile = target
        runCatching { cameraLauncher.launch(uri) }.onFailure {
            releaseCameraGrant(context, uri)
            cameraUri = null
            cameraFile = null
            runCatching { target.delete() }
            onNotice("没有找到可用的相机应用")
        }
    }

    Box(modifier.fillMaxSize()) {
        GlassActionSheet(
            visible = visible,
            onDismiss = onDismiss,
            title = "发送",
            actions = buildList {
                // ---- 通话（微信的「+」面板里就有「视频通话」）----
                if (convId.isNotBlank()) {
                    add(
                        SheetAction(
                            icon = LiuliIcons.Call,
                            label = if (isGroup) "语音通话（多人）" else "语音通话",
                            description = if (isGroup) "群主中转，最多 3 人" else "只走蓝牙，不花流量",
                            onClick = {
                                CallBridge.startCall(convId, false)?.let(onNotice)
                                onDismiss()
                            }
                        )
                    )
                    if (!isGroup) {
                        add(
                            SheetAction(
                                icon = LiuliIcons.Video,
                                label = "视频通话",
                                description = "会申请摄像头权限",
                                onClick = {
                                    CallBridge.startCall(convId, true)?.let(onNotice)
                                    onDismiss()
                                }
                            )
                        )
                    }
                    // ---- 对讲机：按住说话 + 对方自动接收 + 收到自动播放 ----
                    add(
                        SheetAction(
                            icon = if (walkieOn) LiuliIcons.Verified else LiuliIcons.Speed,
                            label = if (walkieOn) "对讲机：已开启" else "对讲机：已关闭",
                            description = if (walkieOn) {
                                "按住说话，对方自动接收并播放"
                            } else {
                                "点一下开启：按住即说，对方自动播放"
                            },
                            onClick = { Walkie.setEnabled(convId, !walkieOn) }
                        )
                    )
                }
                // ---- 发送媒体 ----
                add(
                    SheetAction(
                        icon = LiuliIcons.Image,
                        label = "相册图片",
                        description = "自动压缩到 1600px 再发送",
                        onClick = { imagePicker.launchImagePicker() }
                    )
                )
                add(
                    SheetAction(
                        icon = LiuliIcons.Camera,
                        label = "拍摄照片",
                        description = "拍完即压缩发送",
                        onClick = { shoot() }
                    )
                )
                add(
                    SheetAction(
                        // 用播放三角而不是摄像机，免得和上面的「视频通话」撞图标
                        icon = LiuliIcons.Play,
                        label = "相册视频",
                        description = "原画质发送，蓝牙较慢",
                        onClick = { videoPicker.launchVideoPicker() }
                    )
                )
                add(
                    SheetAction(
                        icon = LiuliIcons.Folder,
                        label = "文件",
                        description = "任意类型文件",
                        onClick = { filePicker.launch(arrayOf("*/*")) }
                    )
                )
            }
        )

        // 压缩/导入遮罩：挡住面板，避免连点两次触发两条消息。
        val label = busyLabel
        if (label != null) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.45f)),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    Modifier
                        .padding(horizontal = 40.dp)
                        .clip(RoundedCornerShape(Radii.panel))
                        .background(LiuliColors.Surface)
                        .padding(horizontal = 30.dp, vertical = 26.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(30.dp),
                        color = LiuliColors.Accent,
                        strokeWidth = 2.5.dp
                    )
                    Text(
                        label,
                        color = LiuliColors.TextPrimary,
                        style = MaterialTheme.typography.titleSmall,
                        textAlign = TextAlign.Center
                    )
                    Text(
                        "正在写入应用私有目录",
                        color = LiuliColors.TextTertiary,
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }
        }

        val body = dialog
        GlassConfirmDialog(
            visible = body != null,
            title = body?.title.orEmpty(),
            message = body?.message.orEmpty(),
            confirmText = when {
                body == null -> "确定"
                body.confirm == null -> "知道了"
                else -> "继续发送"
            },
            dismissText = if (body?.confirm == null) "返回" else "取消",
            destructive = body?.blocking == true,
            onDismiss = {
                // 用户点了「取消/返回」而不是「继续发送」：导入早就把字节写进 files/media 了，
                // 不删就是没人知道文件名的孤儿（删消息、清记录都不会碰到它）。
                // 注意 `body` 是本次组合的快照，而确认按钮只调 onConfirm（GlassSheet.kt 里两者互斥），
                // 所以这里不会误删刚确认发送的那份。
                body?.confirm?.let { discardImportedFiles(it) }
                dialog = null
            },
            onConfirm = {
                val pending = body?.confirm
                dialog = null
                if (pending != null) {
                    onReady(pending)
                    onDismiss()
                }
            }
        )
    }
}

/** 弹窗内容：`confirm` 非空表示"确认后继续发送"，为空表示只是告知。 */
private data class DialogBody(
    val title: String,
    val message: String,
    val confirm: ImportedMedia?,
    val blocking: Boolean
)

/** 兜底文案：媒体层没能给出更具体的原因时使用。 */
private const val TOO_LARGE_FALLBACK = "超过 50 MB 上限，无法通过蓝牙发送。"

/** 把 `content://` 的读写权限授给当前会响应拍照 Intent 的那个相机应用。 */
private fun grantCameraWrite(context: android.content.Context, uri: Uri) {
    val probe = android.content.Intent(android.provider.MediaStore.ACTION_IMAGE_CAPTURE)
    val pkg = runCatching {
        context.packageManager.resolveActivity(probe, 0)?.activityInfo?.packageName
    }.getOrNull() ?: return
    runCatching {
        context.grantUriPermission(
            pkg,
            uri,
            android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
        )
    }
}

private fun releaseCameraGrant(context: android.content.Context, uri: Uri?) {
    if (uri == null) return
    runCatching { context.revokeUriPermission(uri, android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
}
