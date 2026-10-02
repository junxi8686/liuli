package com.liuli.btchat.bt.call

import com.liuli.btchat.bt.Connection
import com.liuli.btchat.core.CallAcceptPacket
import com.liuli.btchat.core.CallEndPacket
import com.liuli.btchat.core.CallFrame
import com.liuli.btchat.core.CallInvitePacket
import com.liuli.btchat.core.CallRejectPacket
import com.liuli.btchat.core.CallStatePacket
import com.liuli.btchat.core.Envelope

/**
 * 通话信令与媒体帧的落点。
 *
 * [com.liuli.btchat.bt.Router] 只负责把「控制帧里的通话包」和「TYPE_CALL 媒体帧」
 * 转到这里，不关心状态机。由 [CallController] 实现；自测里可以换成假实现。
 */
interface CallSignaling {

    /** 收到来电邀请（群聊里成员发来的邀请会由群主先扇出一次）。 */
    fun onInvite(conn: Connection, env: Envelope, p: CallInvitePacket)

    /** 有人接听了。 */
    fun onAccept(conn: Connection, env: Envelope, p: CallAcceptPacket)

    /** 有人拒接。 */
    fun onReject(conn: Connection, env: Envelope, p: CallRejectPacket)

    /** 有人挂断。 */
    fun onEnd(conn: Connection, env: Envelope, p: CallEndPacket)

    /** 对端静音/摄像头状态变化。 */
    fun onPeerState(conn: Connection, env: Envelope, p: CallStatePacket)

    /** 一帧实时媒体（音频/视频），**不做重传也不做重排**。 */
    fun onMediaFrame(conn: Connection, frame: CallFrame)
}
