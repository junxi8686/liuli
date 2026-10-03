package com.liuli.btchat

import com.liuli.btchat.bt.Engine
import com.liuli.btchat.core.Svc
import com.liuli.btchat.data.MediaVaultImpl
import com.liuli.btchat.data.Settings
import com.liuli.btchat.data.Store
import com.liuli.btchat.data.ringForIncoming
import com.liuli.btchat.media.call.BluetoothCallMedia

/**
 * Wires the four subsystems into [Svc]. This is the only place that knows all
 * of the implementation objects, which is what keeps every other file free to
 * depend on interfaces alone.
 */
object Di {

    fun install(ctx: android.content.Context) {
        if (Svc.installed) return
        Svc.install(
            store = Store,
            settings = Settings,
            media = MediaVaultImpl,
            engine = Engine
        )
        // Incoming-message reminder: the engine only knows the conversation.
        Svc.notifier = { convId, sender, preview ->
            runCatching { ringForIncoming(convId, sender, preview) }
        }
        // Call audio/video capture. Letting `bt` see this class directly would
        // invert the layering, so the transport exposes a plain setter and the
        // only place that knows both sides is here.
        runCatching { Engine.callMedia = BluetoothCallMedia(ctx.applicationContext) }

        // 拉黑功能已整体移除（用户要求）。`Engine.blockList` 保留着它的默认值
        // `{ emptySet() }`，也就是永不拦截 —— 代码里那个钩子留着，是因为删掉它
        // 要动 `Router` 的入站路径，而那条路径现在没有任何理由再被改动。

        // Lets the reminder path stay quiet during a call without `data`
        // needing to know anything about calls.
        Svc.inCall = { runCatching { Engine.call.value.busy }.getOrDefault(false) }
    }
}
