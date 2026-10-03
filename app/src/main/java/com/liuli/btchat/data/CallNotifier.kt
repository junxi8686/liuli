package com.liuli.btchat.data

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import com.liuli.btchat.MainActivity
import com.liuli.btchat.bt.Engine
import com.liuli.btchat.bt.call.CallState
import com.liuli.btchat.core.Svc
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * 来电提醒。
 *
 * ## 为什么必须存在
 *
 * 在它之前，来电只做了一件事：把 [CallState.phase] 置成 `RINGING`。唯一的消费者
 * 是 `CallScreen`，而它由 `LiuliRoot` 挂在整个界面上 —— **只有琉璃正开着、而且就在
 * 屏幕上时，来电才可见**。没有通知、没有铃声、没有震动：对方在别的地方时，这通
 * 电话在他那边等于没发生，45 秒后超时挂断，发起方看到的就是「语音通话 未接通」。
 *
 * 这解释了那个忽通忽不通的现象：**对方此刻在不在琉璃里，决定电话能不能打通**。
 *
 * ## 职责边界
 *
 * 这里**只观察** `Engine.call`，不改状态机，也不碰信令。接听/挂断是通知上的按钮，
 * 通过 [CallActionReceiver] 回到 `Engine.accept()` / `Engine.reject()` —— 和用户在
 * 通话界面里按按钮走的是同一条路。
 */
object CallNotifier {

    const val CHANNEL_ID = "liuli_calls"

    /** 通知按钮的 action；`CallActionReceiver` 认这两个。 */
    const val ACTION_ACCEPT = "com.liuli.btchat.call.ACCEPT"
    const val ACTION_HANGUP = "com.liuli.btchat.call.HANGUP"

    private const val NOTIFICATION_ID = 0x1101

    private var job: Job? = null
    private var ringtone: Ringtone? = null
    private var vibrator: Vibrator? = null

    /** 上一次通知画的是什么，避免每帧都重建（Compose 状态会频繁变化）。 */
    private var lastSignature: String? = null

    fun install(ctx: Context, scope: CoroutineScope, state: StateFlow<CallState>) {
        if (job != null) return
        val app = ctx.applicationContext
        ensureChannel(app)
        job = scope.launch(Dispatchers.Default) {
            state.collectLatest { call ->
                runCatching { render(app, call) }
            }
        }
    }

    private fun render(app: Context, call: CallState) {
        val signature = "${call.phase}|${call.callId}|${call.peerName}|${call.video}|${call.muted}"
        val changed = signature != lastSignature
        when (call.phase) {
            CallState.Phase.RINGING -> {
                if (changed) {
                    post(app, buildIncoming(app, call))
                    startRinging(app)
                }
            }
            CallState.Phase.OUTGOING, CallState.Phase.ACTIVE -> {
                stopRinging()
                // 通话中给一条常驻通知：通话界面被别的应用盖住时，用户还有地方
                // 回到通话、也知道麦克风/摄像头正在被使用。
                if (changed) post(app, buildOngoing(app, call))
            }
            CallState.Phase.IDLE, CallState.Phase.ENDED -> {
                stopRinging()
                if (lastSignature != null) cancel(app)
            }
        }
        lastSignature = signature
    }

    // ------------------------------------------------------------ 通知

    private fun ensureChannel(app: Context) {
        val mgr = app.getSystemService(NotificationManager::class.java) ?: return
        if (mgr.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "通话",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "来电响铃与通话中的常驻提示"
            // 铃声和震动由 `CallNotifier` 自己控制：来电要在接通的那一刻立刻停，
            // 而交给系统通道做就得等通知被撤销，中间会多响一下。
            setSound(null, null)
            enableVibration(false)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        mgr.createNotificationChannel(channel)
    }

    private fun buildIncoming(app: Context, call: CallState): Notification {
        val full = PendingIntent.getActivity(
            app,
            1,
            Intent(app, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        // The full-screen intent is only for the case it exists for: the user is
        // *not* looking at 琉璃. Firing it while the call screen is already on
        // screen would shove a second copy of the same UI over the first.
        val wantFullScreen = !Svc.appVisible
        return Notification.Builder(app, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_call)
            .setContentTitle(if (call.video) "视频通话" else "语音通话")
            .setContentText(call.peerName.ifBlank { "有来电" })
            .setCategory(Notification.CATEGORY_CALL)
            .setPriority(Notification.PRIORITY_MAX)
            .setOngoing(true)
            .setAutoCancel(false)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setContentIntent(full)
            // 锁屏直接弹出接听界面。系统可能降级成普通通知（用户没给权限时），
            // 那样也只是退回到「必须在通知栏点一下」，不会更糟。
            .setFullScreenIntent(full, wantFullScreen)
            .addAction(
                Notification.Action.Builder(
                    null, "接听",
                    action(app, ACTION_ACCEPT, 2)
                ).build()
            )
            .addAction(
                Notification.Action.Builder(
                    null, "挂断",
                    action(app, ACTION_HANGUP, 3)
                ).build()
            )
            .build()
    }

    private fun buildOngoing(app: Context, call: CallState): Notification {
        val full = PendingIntent.getActivity(
            app,
            4,
            Intent(app, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val what = if (call.video) "视频通话" else "语音通话"
        val who = call.peerName.ifBlank { "通话中" }
        return Notification.Builder(app, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_call)
            .setContentTitle(who)
            .setContentText(if (call.phase == CallState.Phase.OUTGOING) "$what 呼叫中…" else "$what 进行中")
            .setCategory(Notification.CATEGORY_CALL)
            .setOngoing(true)
            .setAutoCancel(false)
            .setContentIntent(full)
            .addAction(
                Notification.Action.Builder(null, "挂断", action(app, ACTION_HANGUP, 5)).build()
            )
            .build()
    }

    private fun action(app: Context, what: String, requestCode: Int): PendingIntent {
        val intent = Intent(app, CallActionReceiver::class.java)
            .setAction(what)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return PendingIntent.getBroadcast(
            app,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun post(app: Context, n: Notification) {
        val mgr = app.getSystemService(NotificationManager::class.java) ?: return
        runCatching { mgr.notify(NOTIFICATION_ID, n) }
    }

    private fun cancel(app: Context) {
        val mgr = app.getSystemService(NotificationManager::class.java) ?: return
        runCatching { mgr.cancel(NOTIFICATION_ID) }
        lastSignature = null
    }

    // ------------------------------------------------------- 铃声与震动

    private fun startRinging(app: Context) {
        if (ringtone?.isPlaying == true) return
        runCatching {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
                ?: return@runCatching
            val tone = RingtoneManager.getRingtone(app, uri) ?: return@runCatching
            // 只在非通话状态下用 STREAM_RING：用 A2DP/USAGE_NOTIFICATION 会让
            // 蓝牙耳机在通话开始前就先占上音频通道。
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                tone.isLooping = true
                tone.audioAttributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            }
            tone.play()
            ringtone = tone
        }
        runCatching {
            val v = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                (app.getSystemService(VibratorManager::class.java))?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                app.getSystemService(Vibrator::class.java)
            } ?: return@runCatching
            vibrator = v
            val pattern = longArrayOf(0, 700, 700)
            v.vibrate(VibrationEffect.createWaveform(pattern, 0))
        }
    }

    private fun stopRinging() {
        runCatching { ringtone?.stop() }
        ringtone = null
        runCatching { vibrator?.cancel() }
        vibrator = null
    }
}

/**
 * 通知按钮的落点。
 *
 * 刻意复原成 `Engine.accept()` / `Engine.reject()` —— 和通话界面里那两个按钮
 * 调用的是同一对方法，所以「从通知接听」和「在界面里接听」不会有两条行为路径。
 */
class CallActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!Svc.installed) return
        runCatching {
            when (intent.action) {
                CallNotifier.ACTION_ACCEPT -> Engine.accept()
                CallNotifier.ACTION_HANGUP -> {
                    val call = Engine.call.value
                    if (call.phase == CallState.Phase.RINGING) Engine.reject() else Engine.hangUp()
                }
            }
        }
    }
}
