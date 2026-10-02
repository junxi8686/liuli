package com.liuli.btchat.bt

import com.liuli.btchat.core.Attachment
import com.liuli.btchat.core.ChatStore
import com.liuli.btchat.core.Chunk
import com.liuli.btchat.core.Conversation
import com.liuli.btchat.core.ConvKind
import com.liuli.btchat.core.Envelope
import com.liuli.btchat.core.FileAbortPacket
import com.liuli.btchat.core.FileAcceptPacket
import com.liuli.btchat.core.FileDonePacket
import com.liuli.btchat.core.FileOfferPacket
import com.liuli.btchat.core.FileRejectPacket
import com.liuli.btchat.core.ImportedMedia
import com.liuli.btchat.core.MediaVault
import com.liuli.btchat.core.Message
import com.liuli.btchat.core.MsgKind
import com.liuli.btchat.core.MsgState
import com.liuli.btchat.core.SettingsApi
import com.liuli.btchat.core.TransferProgress
import com.liuli.btchat.core.TransferState
import com.liuli.btchat.core.Wire
import com.liuli.btchat.core.newId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * 文件收发。
 *
 * **发送**：先发 FILE_OFFER（带文件名 / 大小 / sha256 / 缩略图），等对方 FILE_ACCEPT
 * 后用 [Wire.CHUNK_SIZE] 分片发送，边发边更新 [ChatStore.setMessageProgress] 与
 * [transfers]；发完发 FILE_DONE，收到对方回执后本地标记 DONE。
 *
 * **接收**：FILE_OFFER 落库为 OFFERED；`autoAcceptMedia` 打开时自动接受，否则等 UI
 * 调 [accept] / [reject]。接受后分片直接顺序写盘（读线程即写线程，天然背压），
 * FILE_DONE 到达时校验 sha256 再标记 DONE；校验失败会删掉半截文件并回 `ok = false`。
 *
 * **群聊**：星型拓扑下成员之间没有直连，FILE_ACCEPT / FILE_DONE 走群主定向中转，
 * 分片则由 [Router] 的 relayRoutes 扇出（见 [Router.onChunk]）。接收端按 seq 去重，
 * 所以中继扇出产生的重复分片不会写坏文件。
 *
 * 所有状态都在 [transfers] 里真实反映；终态条目会保留 [BtConstants.TRANSFER_LINGER_MS]
 * 后自动移除，方便 UI 展示「完成/失败」提示。
 */
class TransferManager(
    private val store: ChatStore,
    private val settings: SettingsApi,
    private val media: MediaVault,
    private val router: Router,
    private val scope: CoroutineScope,
    private val output: MutableStateFlow<List<TransferProgress>>
) {

    /** 进行中 + 刚结束的传输，供 UI 展示进度条。 */
    val transfers: StateFlow<List<TransferProgress>> = output

    private val outgoing = ConcurrentHashMap<String, Outgoing>()
    private val incoming = ConcurrentHashMap<String, Incoming>()

    init {
        // 超时扫描：接收方中途没数据、或发送方卡住时收尾，避免 UI 永久停在「传输中」。
        scope.launch {
            while (isActive) {
                delay(BtConstants.TRANSFER_SWEEP_MS)
                runCatching { sweepStale() }
            }
        }
    }

    /** 发现长时间没有任何进展的传输并收尾（对端掉线之外的最后一道保险）。 */
    private fun sweepStale() {
        val now = System.currentTimeMillis()
        for (inc in incoming.values.toList()) {
            if (inc.state != TransferState.TRANSFERRING) continue
            if (now - inc.lastActivityAt > BtConstants.TRANSFER_IDLE_TIMEOUT_MS) {
                cleanupIncoming(inc, TransferState.FAILED, "传输超时（对端已无数据）", notifyPeer = true)
            }
        }
        for (out in outgoing.values.toList()) {
            if (out.finished) continue
            if (out.state == TransferState.TRANSFERRING &&
                now - out.lastActivityAt > BtConstants.TRANSFER_IDLE_TIMEOUT_MS
            ) {
                finishOutgoing(out, "传输超时（对方无响应）")
            }
        }
    }

    private class Outgoing(
        val transferId: String,
        val msgId: String,
        val convId: String,
        val groupId: String?,
        val file: File,
        val fileName: String,
        val mime: String,
        val kind: MsgKind,
        val size: Long,
        val sha256: String,
        val thumbB64: String?,
        val width: Int,
        val height: Int,
        val durationMs: Long,
        val targets: List<String>
    ) {
        /** 已回 ACCEPT 的设备。 */
        val accepted: MutableSet<String> = ConcurrentHashMap.newKeySet()

        /** 已回 REJECT（或超时未回）的设备。 */
        val pending: MutableSet<String> = ConcurrentHashMap.newKeySet()

        /** 分片要发往的父链路：key = Connection.id。 */
        val parents: ConcurrentHashMap<String, Connection> = ConcurrentHashMap()

        /** 回过拒绝/中止的接收方 deviceId（用于判断「是不是所有接收方都失败了」）。 */
        val failedAcks: MutableSet<String> = ConcurrentHashMap.newKeySet()

        val firstAccept = CompletableDeferred<Boolean>()
        val ack = CompletableDeferred<Boolean>()

        @Volatile
        var job: Job? = null

        @Volatile
        var sentBytes: Long = 0L

        @Volatile
        var lastEmit: Long = 0L

        /** 最近一次有进展（发出去一帧/收到一帧）的时间，用于超时扫描。 */
        @Volatile
        var lastActivityAt: Long = System.currentTimeMillis()

        /** 当前阶段（WAIT_ACCEPT / TRANSFERRING / …），超时扫描要区分「等接受」和「在传」。 */
        @Volatile
        var state: TransferState = TransferState.WAIT_ACCEPT

        @Volatile
        var finished: Boolean = false
    }
    private class Incoming(
        val transferId: String,
        val msgId: String,
        val convId: String,
        val groupId: String?,
        val fromId: String,
        val fileName: String,
        val mime: String,
        val kind: MsgKind,
        val size: Long,
        val sha256: String,
        val source: Connection
    ) {
        @Volatile
        var file: File? = null

        @Volatile
        var sink: OutputStream? = null

        @Volatile
        var received: Long = 0L

        @Volatile
        var expectedSeq: Int = 0

        @Volatile
        var state: TransferState = TransferState.OFFERED

        @Volatile
        var lastEmit: Long = 0L

        /** 最近一次收到分片的时间，用于「对端没数据了」的超时扫描。 */
        @Volatile
        var lastActivityAt: Long = System.currentTimeMillis()

        @Volatile
        var replied: Boolean = false

        val digest: MessageDigest = MessageDigest.getInstance("SHA-256")
    }

    // ------------------------------------------------------------------ 发送

    /** 新建一条媒体消息并开始发送。没有可用链路时消息直接置 FAILED。 */
    fun send(convId: String, imported: ImportedMedia, kind: MsgKind): Message? {
        val conv = store.conversation(convId) ?: return null
        val src = File(imported.filePath)
        if (!src.exists() || !src.isFile) return null
        val me = settings.ensureIdentity()
        val transferId = newId()
        val sha = imported.sha256.ifBlank { sha256Of(src) }
        val att = imported.toAttachment(transferId, kind).copy(sha256 = sha)
        val msg = Message(
            id = newId(),
            convId = convId,
            senderId = me.myDeviceId,
            senderName = me.myName,
            kind = kind,
            attachment = att,
            sentAt = System.currentTimeMillis(),
            state = MsgState.SENDING,
            outgoing = true
        )
        store.saveMessage(msg)
        launchSend(msg, att, conv)
        return store.message(msg.id) ?: msg
    }

    /** 重发（复用原消息与 transferId，对端会用新的 OFFER 覆盖旧的传输状态）。 */
    fun resend(message: Message) {
        val att = message.attachment ?: return
        val conv = store.conversation(message.convId) ?: return
        if (att.localPath == null || !File(att.localPath).exists()) return
        store.setMessageState(message.id, MsgState.SENDING)
        launchSend(message, att, conv)
    }

    private fun launchSend(msg: Message, att: Attachment, conv: Conversation) {
        val src = att.localPath?.let { File(it) }
        if (src == null || !src.exists()) {
            markFailed(att, msg, "源文件不存在", 0L)
            return
        }
        val targets = router.targetsForConv(conv)
        if (targets.isEmpty() || targets.none { router.isOnline(it) }) {
            markFailed(att, msg, "没有可用链路", 0L)
            return
        }
        val out = Outgoing(
            transferId = att.transferId,
            msgId = msg.id,
            convId = msg.convId,
            groupId = if (conv.kind == ConvKind.GROUP) conv.id else null,
            file = src,
            fileName = att.fileName,
            mime = att.mime,
            kind = att.kind,
            size = if (att.size > 0) att.size else src.length(),
            sha256 = att.sha256,
            thumbB64 = att.thumbB64,
            width = att.width,
            height = att.height,
            durationMs = att.durationMs,
            targets = targets
        )
        out.pending.addAll(targets)
        outgoing[out.transferId] = out
        store.setMessageProgress(msg.id, 0L, 0f, TransferState.WAIT_ACCEPT)
        upsert(
            TransferProgress(
                transferId = out.transferId, messageId = msg.id, convId = msg.convId, outgoing = true,
                fileName = out.fileName, total = out.size, done = 0L, state = TransferState.WAIT_ACCEPT
            )
        )
        out.job = scope.launch { runSend(out) }
    }

    private suspend fun runSend(out: Outgoing) {
        try {
            // 1) OFFER
            val offered = router.broadcast(
                out.convId,
                FileOfferPacket(
                    transferId = out.transferId,
                    msgId = out.msgId,
                    convId = out.convId,
                    kind = out.kind.name,
                    fileName = out.fileName,
                    mime = out.mime,
                    size = out.size,
                    sha256 = out.sha256,
                    width = out.width,
                    height = out.height,
                    durationMs = out.durationMs,
                    thumbB64 = out.thumbB64,
                    at = System.currentTimeMillis()
                )
            )
            if (offered <= 0) {
                finishOutgoing(out, "没有可用链路")
                return
            }

            // 2) 等第一个 ACCEPT
            val accepted = withTimeoutOrNull(BtConstants.OFFER_TIMEOUT_MS) { out.firstAccept.await() } ?: false
            if (!accepted) {
                finishOutgoing(out, "对方未接受（等待超时）")
                return
            }
            if (out.finished) return

            // 3) 分片（父链路在开闸瞬间冻结，避免中途加入的接收方看到空洞）
            val parents = out.parents.values.toList().filter { !it.isClosed }
            if (parents.isEmpty()) {
                finishOutgoing(out, "链路已断开")
                return
            }
            store.setMessageProgress(out.msgId, 0L, 0f, TransferState.TRANSFERRING)
            out.state = TransferState.TRANSFERRING
            out.lastActivityAt = System.currentTimeMillis()
            emit(out, TransferState.TRANSFERRING)

            val digest = MessageDigest.getInstance("SHA-256")
            var seq = 0
            var sent = 0L
            FileInputStream(out.file).use { fin ->
                val buf = ByteArray(Wire.CHUNK_SIZE)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val read = fin.read(buf)
                    if (read <= 0) break
                    digest.update(buf, 0, read)
                    // 关键：把 seq / 数据长度固化成 val 再入队。写线程是异步执行的，
                    // 闭包若捕获可变的 seq，实际写出去的就是「执行那一刻」的值，
                    // 会让对端收到重复或跳号的分片。
                    val chunkSeq = seq
                    val data = buf.copyOf(read)
                    for (p in parents) {
                        if (p.isClosed) continue
                        val ok = p.enqueue(
                            Connection.WriteTask("chunk:$chunkSeq") { o ->
                                Wire.writeChunk(o, out.transferId, chunkSeq, data, 0, data.size)
                            },
                            waitMs = 5_000L
                        )
                        if (!ok) {
                            finishOutgoing(out, "链路拥塞，发送中断")
                            return
                        }
                    }
                    seq = chunkSeq + 1
                    sent += read
                    onSentProgress(out, sent)
                }
            }
            val actualSha = hex(digest.digest())

            // 4) DONE（群聊走广播，群主会负责扇出）
            val doneCount = router.broadcast(
                out.convId,
                FileDonePacket(out.transferId, ok = true, sha256 = actualSha)
            )
            emit(out, TransferState.TRANSFERRING)
            if (doneCount <= 0) {
                finishOutgoing(out, "链路已断开")
                return
            }

            // 5) 等接收方校验回执
            val ok = withTimeoutOrNull(BtConstants.DONE_ACK_TIMEOUT_MS) { out.ack.await() }
            when (ok) {
                null -> finishOutgoing(out, "等待对方确认超时") // 对端没回音，本地也标 FAILED，可重发
                false -> finishOutgoing(out, "对方校验失败")
                else -> finishOutgoing(out, null)
            }
        } catch (c: CancellationException) {
            // 已被 cancel()/onAbort() 收尾过就不要再覆盖更具体的原因
            if (!out.finished) {
                out.finished = true
                outgoing.remove(out.transferId)
                store.setMessageProgress(out.msgId, out.sentBytes, fraction(out.sentBytes, out.size), TransferState.CANCELLED)
                emit(out, TransferState.CANCELLED, "已取消")
            }
            throw c
        } catch (t: Throwable) {
            finishOutgoing(out, t.message ?: t.javaClass.simpleName)
        }
    }

    private fun finishOutgoing(out: Outgoing, error: String?) {
        if (out.finished) return
        out.finished = true
        outgoing.remove(out.transferId)
        if (error == null) {
            store.setMessageProgress(out.msgId, out.size, 1f, TransferState.DONE)
            store.setMessageState(out.msgId, MsgState.DELIVERED)
            emit(out, TransferState.DONE, null)
        } else {
            store.setMessageProgress(out.msgId, out.sentBytes, fraction(out.sentBytes, out.size), TransferState.FAILED)
            store.setMessageState(out.msgId, MsgState.FAILED)
            emit(out, TransferState.FAILED, error)
        }
    }

    private fun onSentProgress(out: Outgoing, sent: Long) {
        out.sentBytes = sent
        out.lastActivityAt = System.currentTimeMillis()
        val now = System.currentTimeMillis()
        if (sent < out.size && now - out.lastEmit < BtConstants.PROGRESS_THROTTLE_MS) return
        out.lastEmit = now
        store.setMessageProgress(out.msgId, sent, fraction(sent, out.size), TransferState.TRANSFERRING)
        emit(out, TransferState.TRANSFERRING)
    }

    // ------------------------------------------------------------------ 接收

    fun onOffer(conn: Connection, env: Envelope, p: FileOfferPacket) {
        if (incoming.containsKey(p.transferId)) return // 重复 OFFER
        val conv = router.incomingConv(env, p.convId, conn)
        val kind = runCatching { MsgKind.valueOf(p.kind) }.getOrDefault(MsgKind.FILE)
        val msgId = p.msgId.ifBlank { newId() }
        val thumb = runCatching { media.storeIncomingThumb(p.transferId, p.thumbB64) }.getOrNull()
        val att = Attachment(
            transferId = p.transferId,
            kind = kind,
            fileName = p.fileName,
            mime = p.mime,
            size = p.size,
            sha256 = p.sha256,
            width = p.width,
            height = p.height,
            durationMs = p.durationMs,
            thumbPath = thumb,
            thumbB64 = p.thumbB64,
            progress = 0f,
            state = TransferState.OFFERED,
            transferred = 0L
        )
        val existing = store.message(msgId)
        if (existing == null) {
            store.saveMessage(
                Message(
                    id = msgId,
                    convId = conv.id,
                    senderId = env.from,
                    senderName = store.contact(env.from)?.display?.takeIf { it.isNotBlank() }
                        ?: conn.remoteName.ifBlank { env.from.take(8) },
                    kind = kind,
                    text = "",
                    attachment = att,
                    sentAt = if (p.at > 0) p.at else System.currentTimeMillis(),
                    state = MsgState.SENT,
                    outgoing = false
                )
            )
        } else {
            // 注意：第 5 个位置参数是 localPath，缩略图必须走具名参数 thumbPath，
            // 否则缩略图路径会被当成 payload 路径写进消息里。
            store.setMessageProgress(msgId, 0L, 0f, TransferState.OFFERED, thumbPath = thumb)
        }
        val inc = Incoming(
            transferId = p.transferId,
            msgId = msgId,
            convId = conv.id,
            groupId = env.groupId,
            fromId = env.from,
            fileName = p.fileName,
            mime = p.mime,
            kind = kind,
            size = p.size,
            sha256 = p.sha256,
            source = conn
        )
        incoming[p.transferId] = inc
        upsert(
            TransferProgress(
                transferId = p.transferId, messageId = msgId, convId = conv.id, outgoing = false,
                fileName = p.fileName, total = p.size, done = 0L, state = TransferState.OFFERED
            )
        )
        // 语音（按住说话的短音频）**无条件自动接收**：它只有几十到几百 KB，
        // 而「对方说完还要等本机点接收」完全违背按住说话的交互。
        // 图片/视频/文件仍然受 autoAcceptMedia 开关控制。
        val isVoice = kind == MsgKind.VOICE || p.mime.startsWith("audio/", ignoreCase = true)
        val autoAccept = isVoice || settings.current().autoAcceptMedia
        router.onNotice?.invoke(
            "收到${if (isVoice) "语音" else "文件"}「${p.fileName}」${if (autoAccept) "，自动接收中" else "，等待接收"}"
        )
        if (autoAccept) accept(p.transferId)
    }

    /** UI / 调试页调用：接受一个 OFFERED 的传输。 */
    fun accept(transferId: String) {
        val inc = incoming[transferId] ?: return
        if (inc.state != TransferState.OFFERED) return
        val f = media.newIncomingFile(transferId, inc.fileName)
        f.parentFile?.mkdirs()
        inc.file = f
        inc.sink = BufferedOutputStream(FileOutputStream(f), 128 * 1024)
        inc.state = TransferState.TRANSFERRING
        store.setMessageProgress(inc.msgId, 0L, 0f, TransferState.TRANSFERRING)
        upsert(
            TransferProgress(
                transferId = transferId, messageId = inc.msgId, convId = inc.convId, outgoing = false,
                fileName = inc.fileName, total = inc.size, done = 0L, state = TransferState.TRANSFERRING
            )
        )
        // 先准备好落盘目标再回 ACCEPT，保证对方开闸后不会有分片丢失
        router.sendPacketTo(inc.fromId, inc.groupId, FileAcceptPacket(transferId, 0L))
    }

    /** UI / 调试页调用：拒绝一个 OFFERED 的传输。 */
    fun reject(transferId: String) {
        val inc = incoming[transferId] ?: return
        if (inc.state != TransferState.OFFERED) return
        cleanupIncoming(inc, TransferState.REJECTED, "已拒绝")
        router.sendPacketTo(inc.fromId, inc.groupId, FileRejectPacket(transferId, "rejected"))
    }

    /** 取消一条进行中的传输（发送端会通知对端，接收端会删掉半截文件）。 */
    fun cancel(transferId: String) {
        outgoing[transferId]?.let { out ->
            router.broadcast(out.convId, FileAbortPacket(transferId, "cancelled"))
            out.finished = true
            outgoing.remove(transferId)
            out.job?.cancel()
            store.setMessageProgress(out.msgId, out.sentBytes, fraction(out.sentBytes, out.size), TransferState.CANCELLED)
            emit(out, TransferState.CANCELLED, "已取消")
            return
        }
        incoming[transferId]?.let { inc ->
            router.sendPacketTo(inc.fromId, inc.groupId, FileAbortPacket(transferId, "cancelled"))
            cleanupIncoming(inc, TransferState.CANCELLED, "已取消")
        }
    }

    fun onChunk(conn: Connection, chunk: Chunk) {
        val inc = incoming[chunk.transferId] ?: return
        val sink = inc.sink ?: return
        if (inc.state != TransferState.TRANSFERRING) return
        if (chunk.seq < inc.expectedSeq) return // 中继扇出的重复分片
        if (chunk.seq > inc.expectedSeq) {
            // 一个字节都没收到就跳号 = 我们加入得太晚（群聊里只有群主转发的后续分片）。
            // 这种情况**不要回 ABORT**：发送端和先到的接收方都在正常传，
            // 回一个 ABORT 会把发送端整条传输打死、把先到的接收方永久卡在传输中。
            val lateJoin = inc.received == 0L
            cleanupIncoming(
                inc,
                TransferState.FAILED,
                if (lateJoin) "加入太晚，已错过文件开头" else "分片乱序（缺 ${inc.expectedSeq}）",
                notifyPeer = !lateJoin
            )
            return
        }
        try {
            sink.write(chunk.buf, chunk.offset, chunk.length)
            inc.digest.update(chunk.buf, chunk.offset, chunk.length)
        } catch (t: Throwable) {
            cleanupIncoming(inc, TransferState.FAILED, "写入失败: ${t.message}", notifyPeer = true)
            return
        }
        inc.received += chunk.length
        inc.expectedSeq++
        inc.lastActivityAt = System.currentTimeMillis()
        val now = System.currentTimeMillis()
        if (inc.received < inc.size && now - inc.lastEmit < BtConstants.PROGRESS_THROTTLE_MS) return
        inc.lastEmit = now
        store.setMessageProgress(inc.msgId, inc.received, fraction(inc.received, inc.size), TransferState.TRANSFERRING)
        upsert(
            TransferProgress(
                transferId = inc.transferId, messageId = inc.msgId, convId = inc.convId, outgoing = false,
                fileName = inc.fileName, total = inc.size, done = inc.received, state = TransferState.TRANSFERRING
            )
        )
    }

    fun onAccept(conn: Connection, env: Envelope, p: FileAcceptPacket) {
        val out = outgoing[p.transferId] ?: return
        out.pending.remove(env.from)
        out.accepted.add(env.from)
        out.parents[conn.id] = conn
        out.firstAccept.complete(true)
    }

    fun onReject(conn: Connection, env: Envelope, p: FileRejectPacket) {
        val out = outgoing[p.transferId] ?: return
        out.pending.remove(env.from)
        if (out.accepted.isEmpty() && out.pending.isEmpty()) out.firstAccept.complete(false)
    }

    fun onDone(conn: Connection, env: Envelope, p: FileDonePacket) {
        outgoing[p.transferId]?.let { out ->
            // 群聊里可能有多个接收方：任何一个 **校验成功** 就算这条传输成功；
            // 只有所有接收方都回了失败才判定失败。早先只认第一个回执，
            // 晚加入的接收方回一个 ok=false 就会把已经成功的传输标成失败。
            if (p.ok) {
                out.ack.complete(true)
            } else {
                out.failedAcks.add(env.from)
                if (out.failedAcks.size >= out.parents.size) out.ack.complete(false)
            }
            return
        }
        val inc = incoming[p.transferId] ?: return
        if (inc.replied || inc.state == TransferState.DONE) return
        val actual = finalizeIncoming(inc)
        val ok = inc.state == TransferState.DONE
        router.sendPacketTo(inc.fromId, inc.groupId, FileDonePacket(p.transferId, ok, actual))
    }

    fun onAbort(conn: Connection, env: Envelope, p: FileAbortPacket) {
        outgoing[p.transferId]?.let { out ->
            // 某一路上/接收方中止 ≠ 整条传输失败：群聊里还有别的接收方在收。
            // 只有所有父链路都退出，才真的把发送端收掉。
            out.parents.remove(conn.id)
            out.failedAcks.add(env.from)
            if (out.parents.isNotEmpty() && !out.finished) {
                emit(out, TransferState.TRANSFERRING, "有接收方中止：${env.from.take(8)}")
                return
            }
            out.finished = true
            outgoing.remove(p.transferId)
            out.job?.cancel()
            store.setMessageProgress(out.msgId, out.sentBytes, fraction(out.sentBytes, out.size), TransferState.CANCELLED)
            emit(out, TransferState.CANCELLED, p.reason.ifBlank { "对方取消" })
            return
        }
        incoming[p.transferId]?.let { inc -> cleanupIncoming(inc, TransferState.CANCELLED, p.reason.ifBlank { "对方取消" }) }
    }

    /** 链路断开：相关传输立即失败，半截文件清掉，避免留下脏数据。 */
    fun onLinkClosed(conn: Connection) {
        for (out in outgoing.values.toList()) {
            if (!out.parents.containsKey(conn.id)) continue
            out.parents.remove(conn.id)
            if (out.parents.isEmpty() && !out.finished) {
                out.finished = true
                outgoing.remove(out.transferId)
                out.job?.cancel()
                store.setMessageProgress(out.msgId, out.sentBytes, fraction(out.sentBytes, out.size), TransferState.FAILED)
                store.setMessageState(out.msgId, MsgState.FAILED)
                emit(out, TransferState.FAILED, "连接断开")
            }
        }
        for (inc in incoming.values.toList()) {
            if (inc.source === conn) cleanupIncoming(inc, TransferState.FAILED, "连接断开")
        }
    }

    // ------------------------------------------------------------- 收尾处理

    /** 关闭文件、校验 sha256、更新消息状态；返回实际 sha256。 */
    private fun finalizeIncoming(inc: Incoming): String {
        inc.replied = true
        val f = inc.file
        runCatching {
            inc.sink?.flush()
            inc.sink?.close()
        }
        inc.sink = null
        val actual = hex(inc.digest.digest())
        val sizeOk = inc.received == inc.size
        val shaOk = inc.sha256.isBlank() || actual.equals(inc.sha256, ignoreCase = true)
        incoming.remove(inc.transferId)
        if (sizeOk && shaOk) {
            inc.state = TransferState.DONE
            store.setMessageProgress(inc.msgId, inc.received, 1f, TransferState.DONE, localPath = f?.absolutePath)
            store.setMessageState(inc.msgId, MsgState.DELIVERED)
            upsert(
                TransferProgress(
                    transferId = inc.transferId, messageId = inc.msgId, convId = inc.convId, outgoing = false,
                    fileName = inc.fileName, total = inc.size, done = inc.received, state = TransferState.DONE
                )
            )
            scheduleRemoval(inc.transferId)
            // 图片/视频自动进系统相册，符合用户对「收到照片」的预期
            if ((inc.kind == MsgKind.IMAGE || inc.kind == MsgKind.VIDEO) && f != null) {
                runCatching { media.exportToGallery(f, inc.mime) }
            }
            router.onNotice?.invoke("文件「${inc.fileName}」接收完成")
        } else {
            runCatching { f?.delete() }
            inc.state = TransferState.FAILED
            val reason = if (!sizeOk) "大小不符（${inc.received}/${inc.size}）" else "sha256 校验失败"
            store.setMessageProgress(inc.msgId, inc.received, fraction(inc.received, inc.size), TransferState.FAILED)
            upsert(
                TransferProgress(
                    transferId = inc.transferId, messageId = inc.msgId, convId = inc.convId, outgoing = false,
                    fileName = inc.fileName, total = inc.size, done = inc.received, state = TransferState.FAILED,
                    error = reason
                )
            )
        }
        return actual
    }

    private fun cleanupIncoming(inc: Incoming, state: TransferState, reason: String, notifyPeer: Boolean = false) {
        incoming.remove(inc.transferId)
        inc.state = state
        runCatching {
            inc.sink?.close()
        }
        inc.sink = null
        runCatching { inc.file?.delete() }
        store.setMessageProgress(inc.msgId, inc.received, fraction(inc.received, inc.size), state)
        upsert(
            TransferProgress(
                transferId = inc.transferId, messageId = inc.msgId, convId = inc.convId, outgoing = false,
                fileName = inc.fileName, total = inc.size, done = inc.received, state = state, error = reason
            )
        )
        // 中途失败的接收（乱序/写盘失败）要立刻告诉发送方，别让它一直推到超时
        if (notifyPeer) {
            router.sendPacketTo(inc.fromId, inc.groupId, FileAbortPacket(inc.transferId, reason))
        }
    }

    private fun markFailed(att: Attachment, msg: Message, reason: String, sent: Long) {
        store.setMessageProgress(msg.id, sent, fraction(sent, att.size), TransferState.FAILED)
        store.setMessageState(msg.id, MsgState.FAILED)
        upsert(
            TransferProgress(
                transferId = att.transferId, messageId = msg.id, convId = msg.convId, outgoing = true,
                fileName = att.fileName, total = att.size, done = sent, state = TransferState.FAILED, error = reason
            )
        )
    }

    // ------------------------------------------------------------------ 工具

    private fun emit(out: Outgoing, state: TransferState, error: String? = null) {
        upsert(
            TransferProgress(
                transferId = out.transferId, messageId = out.msgId, convId = out.convId, outgoing = true,
                fileName = out.fileName, total = out.size, done = out.sentBytes, state = state, error = error
            )
        )
        if (state == TransferState.DONE || state == TransferState.FAILED || state == TransferState.CANCELLED) {
            scheduleRemoval(out.transferId)
        }
    }

    private fun upsert(p: TransferProgress) {
        output.update { list ->
            if (list.any { it.transferId == p.transferId }) {
                list.map { if (it.transferId == p.transferId) p.copy(error = p.error ?: it.error) else it }
            } else {
                list + p
            }
        }
    }

    private fun scheduleRemoval(transferId: String) {
        scope.launch {
            delay(BtConstants.TRANSFER_LINGER_MS)
            output.update { list -> list.filterNot { it.transferId == transferId } }
        }
    }

    private fun fraction(done: Long, total: Long): Float =
        if (total <= 0L) 0f else (done.toFloat() / total).coerceIn(0f, 1f)

    private fun sha256Of(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        FileInputStream(f).use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        return hex(md.digest())
    }

    private fun hex(bytes: ByteArray): String {
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            sb.append(HEX[v ushr 4]).append(HEX[v and 0x0F])
        }
        return sb.toString()
    }

    private companion object {
        val HEX = "0123456789abcdef".toCharArray()
    }
}
