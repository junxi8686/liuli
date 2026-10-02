package com.liuli.btchat.ui.components

import com.liuli.btchat.core.Message
import com.liuli.btchat.core.MsgKind
import com.liuli.btchat.data.ChatPrefs

/**
 * 对讲机模式的读写门面。
 *
 * 定义（lead 给的，不自己发明）：**按住说话 → 对方自动接收 → 收到自动播放**。
 *  * 「自动接收」由 bt-transport 保证（语音无条件自动接收），UI 不重复做；
 *  * 「自动播放」**默认开、必须能关**（自动外放是隐私敏感行为）；
 *  * 「对讲」标记走 `MsgKind.VOICE_PTT`（发送时把 `ImportedMedia.ptt = true` 交给引擎）。
 *
 * 两个开关都持久化在 [ChatPrefs]（写入会 `bump()` revision，UI 只要 collect 那个 flow
 * 就会自动刷新，不需要在页面里自己存一份状态）。这里留一层薄门面，是为了让调用点
 * 读起来是「对讲机」而不是「聊天偏好」。
 */
object Walkie {

    /** 这个会话是否开着对讲机模式。 */
    fun enabled(convId: String): Boolean =
        runCatching { ChatPrefs.walkie(convId) }.getOrDefault(false)

    fun setEnabled(convId: String, on: Boolean) {
        runCatching { ChatPrefs.setWalkie(convId, on) }
    }

    /** 收到对讲语音是否自动外放（全局，默认 true）。 */
    fun autoPlayVoice(): Boolean =
        runCatching { ChatPrefs.autoPlayVoice() }.getOrDefault(true)

    fun setAutoPlayVoice(on: Boolean) {
        runCatching { ChatPrefs.setAutoPlayVoice(on) }
    }

    /** 这条消息是不是对讲语音。 */
    fun isPtt(message: Message): Boolean = message.kind == MsgKind.VOICE_PTT
}
