package com.liuli.btchat.bt

/**
 * 一条待处理的好友申请。
 *
 * 触发场景：陌生人第一次连接后发来消息、或者被删掉的好友又来消息 ——
 * 这类消息**不进会话列表**，而是变成一条申请，等我同意之后双方才互相写入联系人表。
 */
data class FriendRequest(
    val deviceId: String,
    val name: String,
    val avatarSeed: Int,
    /** 申请附言（对方发来的第一句话，或者验证消息）。 */
    val hello: String,
    /** 这次申请用的消息 id：同意/拒绝时以回执的形式回给对方。 */
    val reqId: String = "",
    val at: Long = System.currentTimeMillis(),
    /** 本机是否已经回过「同意/拒绝」，避免重复处理。 */
    val handled: Boolean = false
)
