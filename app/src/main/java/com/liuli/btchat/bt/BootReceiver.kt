package com.liuli.btchat.bt

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.liuli.btchat.core.Svc

/**
 * 让链路自己回来 —— 手机重启之后，以及应用被更新之后。
 *
 * ## 为什么需要
 *
 * [BluetoothChatService] 是前台服务，应用运行期间进程能活住。但它只在
 * `Engine.start()` 里被拉起，而 `Engine.start()` 只有**界面起来**才会调。
 * 于是有两条路会静默地断掉一切：
 *
 * * **重启手机** —— 服务没了，用户不打开应用就收不到任何消息，也收不到来电，
 *   而且没有任何提示说明为什么；
 * * **安装更新** —— 系统会强制停止应用，正在跑的连接被切断。用户看到的现象
 *   和重启一样。
 *
 * 这两件事都不该要求用户「记得先打开一次应用」。收得到消息是聊天应用的基本
 * 承诺，不是打开应用之后才生效的功能。
 *
 * 这里只负责把服务拉起来；真正的连接、重连、退避全在 [Engine] 和
 * [AutoConnector] 里，它们本来就会处理「服务起来了但还没连上」的状态。
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED -> Unit
            else -> return
        }
        // 进程刚起来，`Svc` 还没装配：`Engine` 的每个入口都会检查它，
        // 所以这里先确认再动，免得在没有存储/设置的情况下启动引擎。
        if (!Svc.installed) return
        runCatching { BluetoothChatService.start(context.applicationContext) }
    }
}
