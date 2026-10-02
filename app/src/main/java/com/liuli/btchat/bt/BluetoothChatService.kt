package com.liuli.btchat.bt

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.liuli.btchat.MainActivity
import com.liuli.btchat.R
import com.liuli.btchat.core.ChatEngine
import com.liuli.btchat.core.LinkState
import com.liuli.btchat.core.LinkStatus
import com.liuli.btchat.core.Svc
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * 常驻前台服务：`foregroundServiceType="connectedDevice"`。
 *
 * 作用有两个：
 * 1. 让系统知道「这个进程正在维持一条蓝牙连接」，退到后台（甚至清掉最近任务）也不会
 *    被当成缓存进程回收，RFCOMM 链路因此不断；
 * 2. 通过常驻通知给用户一个明确的「正在连接」入口 —— 点开回到聊天，通知上的
 *    「停止连接」直接断开。
 *
 * 服务本身不做蓝牙 I/O，一切都通过 [Engine]；[onBind] 返回的 [LocalBinder] 让界面
 * 可以在不重新建链的前提下拿到同一个引擎实例。
 */
class BluetoothChatService : Service() {

    /** 绑定接口：拿到引擎，拿到服务自身。 */
    inner class LocalBinder : Binder() {
        fun engine(): ChatEngine = Engine
        fun service(): BluetoothChatService = this@BluetoothChatService
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var binder: LocalBinder? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var observing = false

    override fun onCreate() {
        super.onCreate()
        binder = LocalBinder()
        createChannel()
        acquireWakeLock()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // startForegroundService 之后必须在 5 秒内转前台，所以第一件事就是它
        promoteToForeground(Engine.link.value)

        if (intent?.action == ACTION_STOP) {
            if (Svc.installed) runCatching { Engine.stop() }
            stopSelf()
            return START_NOT_STICKY
        }

        if (Svc.installed && !Engine.isRunning) {
            runCatching { Engine.start() }
        }
        observeEngine()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = binder ?: LocalBinder().also { binder = it }

    override fun onDestroy() {
        scope.cancel()
        releaseWakeLock()
        super.onDestroy()
    }

    // ------------------------------------------------------------ 通知与前台

    private fun promoteToForeground(status: LinkStatus) {
        val notification = buildNotification(status)
        runCatching {
            startForeground(
                BtConstants.NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            )
        }.onFailure {
            // 极少数机型在没有 connectedDevice 权限时会拒绝带类型的 startForeground
            runCatching { startForeground(BtConstants.NOTIFICATION_ID, notification) }
        }
    }

    private fun observeEngine() {
        if (observing) return
        observing = true
        scope.launch {
            combine(Engine.link, Engine.transfers) { link, transfers -> link to transfers }
                .collectLatest { (link, transfers) ->
                    val active = transfers.count {
                        it.state == com.liuli.btchat.core.TransferState.TRANSFERRING ||
                            it.state == com.liuli.btchat.core.TransferState.WAIT_ACCEPT
                    }
                    runCatching {
                        NotificationManagerCompat.from(this@BluetoothChatService)
                            .notify(BtConstants.NOTIFICATION_ID, buildNotification(link, active))
                    }
                }
        }
    }

    private fun buildNotification(status: LinkStatus, activeTransfers: Int = 0): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, BluetoothChatService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val detail = when {
            activeTransfers > 0 -> "正在传输 $activeTransfers 个文件…"
            status.state == LinkState.CONNECTED -> status.message
            status.state == LinkState.SCANNING -> "正在搜索附近设备…"
            status.state == LinkState.ERROR -> status.message
            status.message.isNotBlank() -> status.message
            else -> "蓝牙待命"
        }
        val title = if (status.adapterName.isNotBlank()) "琉璃 · ${status.adapterName}" else "琉璃"
        return NotificationCompat.Builder(this, BtConstants.NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(detail)
            .setOngoing(true)
            .setShowWhen(false)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(open)
            .addAction(0, "停止连接", stop)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(BtConstants.NOTIFICATION_CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            BtConstants.NOTIFICATION_CHANNEL_ID,
            BtConstants.NOTIFICATION_CHANNEL_NAME,
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "保持蓝牙聊天连接不断开"
            setShowBadge(false)
            enableVibration(false)
            setSound(null, null)
        }
        runCatching { manager.createNotificationChannel(channel) }
    }

    // ---------------------------------------------------------------- 唤醒锁

    /**
     * 维持链路需要 CPU 在分片收发时不休眠。持锁时间与前台服务同生命周期，
     * [onDestroy] 一定释放。
     */
    private fun acquireWakeLock() {
        if (wakeLock != null) return
        runCatching {
            val pm = getSystemService(PowerManager::class.java) ?: return
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "liuli:bluetooth-link").apply {
                setReferenceCounted(false)
                acquire()
            }
        }
    }

    private fun releaseWakeLock() {
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        wakeLock = null
    }

    companion object {
        private const val ACTION_START = "com.liuli.btchat.action.START_LINK"
        private const val ACTION_STOP = "com.liuli.btchat.action.STOP_LINK"

        /** 拉起前台服务（重复调用安全）。 */
        fun start(ctx: Context) {
            runCatching {
                ContextCompat.startForegroundService(
                    ctx,
                    Intent(ctx, BluetoothChatService::class.java).setAction(ACTION_START)
                )
            }
        }

        /** 停止前台服务。 */
        fun stop(ctx: Context) {
            runCatching { ctx.stopService(Intent(ctx, BluetoothChatService::class.java)) }
        }
    }
}
