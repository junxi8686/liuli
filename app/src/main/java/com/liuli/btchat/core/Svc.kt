package com.liuli.btchat.core

/**
 * Process-wide service locator. [com.liuli.btchat.Di] fills it during
 * `Application.onCreate`; everything else only reads it.
 */
object Svc {

    lateinit var store: ChatStore
        private set

    lateinit var settings: SettingsApi
        private set

    lateinit var media: MediaVault
        private set

    lateinit var engine: ChatEngine
        private set

    @Volatile
    var installed: Boolean = false
        private set

    /**
     * Optional hook the transport calls when a message arrives, so `bt` can
     * raise a reminder without knowing anything about notifications, audio or
     * the UI layer. Wired up by `Di`.
     */
    var notifier: ((convId: String, senderName: String, preview: String) -> Unit)? = null

    /**
     * The conversation currently open on screen, or null.
     *
     * The chat page publishes itself here while it is composed. The reminder
     * path reads it to stay quiet for the chat the user is already looking at —
     * before this existed, a message arriving in the open conversation still
     * rang and buzzed, which is the one case where a notification is purely
     * noise.
     */
    val activeConvId = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)

    /** True while any of this app's activities is started (i.e. on screen). */
    @Volatile
    var appVisible: Boolean = false

    /**
     * True while a call is up. Set by `Di`.
     *
     * A message that arrives mid-call must not ring the phone — the ringtone is
     * already how the call got here, and a second tone on top of a live
     * conversation is just noise. Kept as a lambda so `data` never has to know
     * about the transport.
     */
    var inCall: () -> Boolean = { false }

    /**
     * Set when the user taps a message notification; the shell picks it up and
     * opens that conversation, then clears it. A one-shot mailbox rather than a
     * nav argument because the Compose tree is already running by then.
     */
    val pendingOpenConvId = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)

    fun install(
        store: ChatStore,
        settings: SettingsApi,
        media: MediaVault,
        engine: ChatEngine
    ) {
        this.store = store
        this.settings = settings
        this.media = media
        this.engine = engine
        installed = true
    }
}
