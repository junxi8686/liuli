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

        // The block list is kept as a plain local preference by the profile
        // screen; `bt` gets it as a supplier so the transport never has to know
        // where it is stored. `internal` visibility is module-wide, so calling
        // into `ui` from here is legal and keeps both sides untouched.
        val app = ctx.applicationContext
        Engine.blockList = {
            runCatching { com.liuli.btchat.ui.screens.blockedContacts(app).toSet() }
                .getOrDefault(emptySet())
        }

        // Lets the reminder path stay quiet during a call without `data`
        // needing to know anything about calls.
        Svc.inCall = { runCatching { Engine.call.value.busy }.getOrDefault(false) }
    }
}
