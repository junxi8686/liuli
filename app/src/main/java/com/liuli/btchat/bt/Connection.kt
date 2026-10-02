package com.liuli.btchat.bt

import com.liuli.btchat.core.ByePacket
import com.liuli.btchat.core.CallFrame
import com.liuli.btchat.core.Chunk
import com.liuli.btchat.core.Duplex
import com.liuli.btchat.core.Envelope
import com.liuli.btchat.core.HelloAckPacket
import com.liuli.btchat.core.HelloPacket
import com.liuli.btchat.core.PingPacket
import com.liuli.btchat.core.PongPacket
import com.liuli.btchat.core.Wire
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.OutputStream
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * 一条链路的收发实现。
 *
 * 一条 [Connection] 内部有三个协程（都跑在 [Dispatchers.IO]）：
 *
 * * **读循环** —— `Wire.readFrame` 逐帧读取，负责握手、心跳应答，其余帧交给
 *   [Sink]。因为 RFCOMM 是串行流，读循环顺序调用 sink 就天然保证了「同一个文件
 *   的分片按序落盘」，同时也给发送方提供了背压。
 * * **写循环** —— 从 [LinkedBlockingQueue] 取 [WriteTask] 串行写。所有对外发送
 *   接口都只是入队，所以多线程（文本、回执、文件分片、中继转发）同时发包也不会
 *   交错写坏帧。
 * * **心跳循环** —— 定期发 PING、检查静默超时和握手超时。
 *
 * 握手机制：双方 [start] 后都立刻发 HELLO，收到 HELLO 的一方回 HELLO_ACK 并置为
 * READY；收到 HELLO_ACK 也置为 READY。先完成的一侧会通过 [Sink.onReady] 通知上层。
 */
class Connection(
    val duplex: Duplex,
    private val helloFactory: () -> HelloPacket,
    private val sink: Sink,
    private val scope: CoroutineScope,
    /** 对端 MAC（监听端来自 socket，连接端来自入参）；自测里为空。 */
    val address: String = "",
    /** 是否走加密通道。 */
    val secure: Boolean = false,
    /** 是否由本机被动接受（对端主动连过来）。 */
    val incoming: Boolean = false
) {

    enum class State { NEW, HANDSHAKING, READY, CLOSED }

    /** 对端身份，握手后才有值。 */
    data class Remote(val deviceId: String = "", val name: String = "", val avatarSeed: Int = 0)

    /** 上层（[Router]）的回调接口，全部在 IO 线程上调用。 */
    interface Sink {
        fun onEnvelope(conn: Connection, env: Envelope)
        fun onChunk(conn: Connection, chunk: Chunk)

        /** 实时通话媒体帧（[Wire.TYPE_CALL]）。与业务包完全分离，避免被大文件拖住。 */
        fun onCallFrame(conn: Connection, frame: CallFrame)
        fun onReady(conn: Connection)
        fun onClosed(conn: Connection, reason: String)
    }

    /**
     * 一个待写入的帧。持有的是「怎么写」，不是字节，避免多一次 16 KB 拷贝。
     *
     * [bytes] 只用于吞吐统计（估计值即可），放在中间是为了让 `write` 保持在最后，
     * 这样 `WriteTask("chunk") { ... }` 的尾随 lambda 写法依然可用。
     */
    class WriteTask(val label: String, val bytes: Int = 0, val write: (OutputStream) -> Unit)

    private val writes = LinkedBlockingQueue<WriteTask>(BtConstants.WRITE_QUEUE_CAPACITY)

    /**
     * 高优先写队列：只放实时通话媒体帧。
     *
     * 写线程每一轮先清空这条队列，再写一帧普通任务 —— 所以一个正在传输的 16 KB
     * 文件分片最多让语音帧多等一帧的时间，而语音帧永远不会排在一个巨大的普通队列
     * 后面。队列满时丢**最旧**的帧：语音过期就没有价值了，丢比等好。
     */
    private val callWrites = LinkedBlockingQueue<WriteTask>(BtConstants.CALL_QUEUE_CAPACITY)
    private val callDrops = AtomicLong(0)

    /** 因高优先队列溢出被丢弃的媒体帧数（诊断用，见 [BtConstants.CALL_QUEUE_CAPACITY]）。 */
    val callFramesDropped: Long get() = callDrops.get()

    /** 累计写出的字节数。 */
    @Volatile
    var bytesWritten: Long = 0L
        private set

    private var windowBytes = 0L
    private var windowBusyNs = 0L
    /** 实测写入吞吐（字节/秒，EWMA）；0 = 样本还不够。 */
    @Volatile
    private var writeBps: Long = 0L

    private val bpsFlow = MutableStateFlow(0L)

    /** 实测吞吐变化（诊断/自测可观察）。 */
    val writeBpsFlow: StateFlow<Long> = bpsFlow

    /**
     * 最近实测到的写入吞吐（字节/秒）；0 表示还没有样本。
     *
     * 用来判断「这条链路够不够通畅」—— 低于 [BtConstants.MIN_USABLE_BPS] 就先不下大文件，
     * 免得爬十分钟然后失败。
     */
    fun currentWriteBps(): Long = writeBps

    private val closed = AtomicBoolean(false)
    private val ready = AtomicBoolean(false)

    /** 进程内唯一 id，[Router] 用它做链路表的键。 */
    val id: String = java.util.UUID.randomUUID().toString()

    private val stateFlow = MutableStateFlow(State.NEW)

    /** NEW → HANDSHAKING → READY → CLOSED。 */
    val state: StateFlow<State> = stateFlow

    private val remoteFlow = MutableStateFlow(Remote())

    /** 对端身份，随握手更新。 */
    val remote: StateFlow<Remote> = remoteFlow

    private val rttFlow = MutableStateFlow(-1L)

    /** 最近一次心跳往返时延（毫秒），-1 表示还没测到。 */
    val rttMs: StateFlow<Long> = rttFlow

    /** 最后一次收到对端任何字节的时间。 */
    @Volatile
    var lastSeenAt: Long = System.currentTimeMillis()
        private set

    private val startedAt = System.currentTimeMillis()

    /** 本机身份，懒加载一次（内部会调用 settings.ensureIdentity）。 */
    private val localHello: HelloPacket by lazy { helloFactory() }

    val remoteId: String get() = remoteFlow.value.deviceId
    val remoteName: String get() = remoteFlow.value.name
    val remoteSeed: Int get() = remoteFlow.value.avatarSeed
    val isReady: Boolean get() = ready.get()
    val isClosed: Boolean get() = closed.get()

    /** 该链路在 [Router] 里的唯一键（握手前退化为 MAC / label）。 */
    val key: String get() = when {
        remoteId.isNotBlank() -> remoteId
        address.isNotBlank() -> address
        else -> duplex.label
    }

    // ------------------------------------------------------------- 生命周期

    /** 启动读写与心跳。重复调用安全。 */
    fun start() {
        if (closed.get()) return
        if (stateFlow.value != State.NEW) return
        stateFlow.value = State.HANDSHAKING

        scope.launch(Dispatchers.IO) { readLoop() }
        scope.launch(Dispatchers.IO) { writeLoop() }
        scope.launch(Dispatchers.IO) { heartbeatLoop() }

        // 双方同时发 HELLO，谁先到谁先完成握手
        val hello = localHello
        send(Envelope(from = hello.deviceId, packet = hello))
    }

    /** 关闭链路。幂等；会触发一次 [Sink.onClosed]。 */
    fun close(reason: String) {
        if (!closed.compareAndSet(false, true)) return
        stateFlow.value = State.CLOSED
        runCatching { duplex.close() }
        runCatching { sink.onClosed(this, reason) }
    }

    // --------------------------------------------------------------- 发送

    /** 入队一个控制包。队列满或链路已关时返回 false（不阻塞调用者）。 */
    fun send(env: Envelope): Boolean = tryEnqueue(WriteTask("control:${env.packet::class.simpleName}") { out ->
        Wire.writeControl(out, env)
    })

    /** 入队一个裸帧（心跳等）。 */
    fun sendFrame(type: Int, payload: ByteArray): Boolean =
        tryEnqueue(WriteTask("frame:$type") { out -> Wire.writeFrame(out, type, payload) })

    /** 入队一个文件分片。 */
    fun sendChunk(transferId: String, seq: Int, data: ByteArray, offset: Int, len: Int): Boolean =
        tryEnqueue(WriteTask("chunk:$seq", len) { out -> Wire.writeChunk(out, transferId, seq, data, offset, len) })

    /**
     * 带背压的入队：队列满时最多等 [waitMs] 毫秒（每 100 ms 让出一次线程，
     * 便于协程取消）。文件发送用它，避免把整个文件塞进内存。
     */
    suspend fun enqueue(task: WriteTask, waitMs: Long = 1_500L): Boolean {
        var waited = 0L
        while (waited < waitMs) {
            if (closed.get()) return false
            if (writes.offer(task, 100, TimeUnit.MILLISECONDS)) return true
            waited += 100
            currentCoroutineContext().ensureActive()
        }
        return !closed.get() && writes.offer(task)
    }

    /** 非阻塞入队，队列满则丢弃。 */
    fun tryEnqueue(task: WriteTask): Boolean {
        if (closed.get()) return false
        return writes.offer(task)
    }

    /**
     * 投递一帧实时通话媒体到**高优先队列**。
     *
     * 语音/视频帧永远不走普通队列：队列满时丢最旧的帧并计数（[callFramesDropped]），
     * 保证调用方（采集线程）永不阻塞。通话不做重传也不做重排 —— 实时媒体宁可丢帧，
     * 也不要延迟。
     */
    fun tryEnqueueCall(block: (OutputStream) -> Unit): Boolean {
        if (closed.get()) return false
        val task = WriteTask("call") { out -> block(out) }
        if (callWrites.offer(task)) return true
        // 队列满：丢最旧的一帧，给最新的让位
        callWrites.poll()
        callDrops.incrementAndGet()
        return callWrites.offer(task)
    }

    /** 高优先队列里待写的媒体帧数（诊断用）。 */
    val pendingCallFrames: Int get() = callWrites.size

    /** 队列中待写的帧数（调试用）。 */
    val pendingWrites: Int get() = writes.size

    // ------------------------------------------------------------- 内部循环

    private suspend fun readLoop() {
        var reason = "连接已关闭"
        try {
            val input = duplex.input
            while (currentCoroutineContext().isActive && !closed.get()) {
                val frame = Wire.readFrame(input) ?: break
                lastSeenAt = System.currentTimeMillis()
                handleFrame(frame.type, frame.payload)
                if (closed.get()) break
            }
            if (!closed.get()) reason = "对方已断开"
        } catch (t: Throwable) {
            reason = "读取出错: ${t.message ?: t.javaClass.simpleName}"
        } finally {
            close(reason)
        }
    }

    private suspend fun writeLoop() {
        try {
            val out = duplex.output
            while (currentCoroutineContext().isActive) {
                // 1) 先把高优先队列里的实时媒体帧全部写出去
                drainCallQueue(out)
                // 2) 再写一帧普通任务。等待上限刻意取很短（CALL_WRITE_POLL_MS），
                //    这样通话期间新到的语音帧最坏只多等这一小段，而不会等到文件分片写完。
                val task = writes.poll(BtConstants.CALL_WRITE_POLL_MS, TimeUnit.MILLISECONDS)
                if (task == null) {
                    if (closed.get()) break
                    continue
                }
                val t0 = System.nanoTime()
                task.write(out)
                out.flush()
                accountWritten(task.bytes, System.nanoTime() - t0)
            }
        } catch (t: Throwable) {
            close("写入失败: ${t.message ?: t.javaClass.simpleName}")
        }
    }

    /** 清空高优先队列。返回是否写出去过帧。 */
    private fun drainCallQueue(out: OutputStream): Boolean {
        var wrote = false
        while (!closed.get()) {
            val task = callWrites.poll() ?: break
            val t0 = System.nanoTime()
            task.write(out)
            out.flush()
            accountWritten(task.bytes, System.nanoTime() - t0)
            wrote = true
        }
        return wrote
    }

    /**
     * 统计吞吐：**只累加真正在写的那段时间**。
     *
     * 关键点：不能用「墙钟时间」当分母 —— 链路空闲 5 秒后写 16 KB，
     * 按墙钟算出来是 3 KB/s，会误判成「链路很差」，从而永远不敢下载。
     * 这里累加每次 write 的耗时，算出来的是真实写入吞吐（goodput）。
     * 写线程独有，无需加锁。
     */
    private fun accountWritten(bytes: Int, busyNs: Long) {
        if (bytes <= 0) return
        bytesWritten += bytes
        windowBytes += bytes
        windowBusyNs += busyNs
        // 攒够 ~500ms 的忙碌时间才算一个样本
        if (windowBusyNs >= MIN_SAMPLE_NS) {
            val bps = windowBytes * 1_000_000_000L / windowBusyNs
            writeBps = if (writeBps <= 0) bps else (writeBps * 7 + bps * 3) / 10 // α≈0.3
            bpsFlow.value = writeBps
            windowBytes = 0
            windowBusyNs = 0
        }
    }

    private suspend fun heartbeatLoop() {
        while (currentCoroutineContext().isActive && !closed.get()) {
            delay(BtConstants.HEARTBEAT_INTERVAL_MS)
            if (closed.get()) break
            val idle = System.currentTimeMillis() - lastSeenAt
            if (!ready.get() && System.currentTimeMillis() - startedAt > BtConstants.HANDSHAKE_TIMEOUT_MS) {
                close("握手超时")
                break
            }
            if (idle > BtConstants.LINK_TIMEOUT_MS) {
                close("心跳超时")
                break
            }
            if (ready.get()) sendFrame(Wire.TYPE_PING, longToBytes(System.currentTimeMillis()))
        }
    }

    private fun handleFrame(type: Int, payload: ByteArray) {
        when (type) {
            Wire.TYPE_CONTROL -> {
                val env = runCatching { Wire.decode(payload) }.getOrNull() ?: return
                when (val p = env.packet) {
                    is HelloPacket -> {
                        adoptRemote(p.deviceId, p.name, p.avatarSeed)
                        // 回 ACK；如果之前已经回过，重复也无害（幂等）
                        val me = localHello
                        send(
                            Envelope(
                                from = me.deviceId,
                                to = p.deviceId.ifBlank { null },
                                packet = HelloAckPacket(me.deviceId, me.name, me.avatarSeed, accepted = true)
                            )
                        )
                        markReady()
                    }

                    is HelloAckPacket -> {
                        adoptRemote(p.deviceId, p.name, p.avatarSeed)
                        markReady()
                    }

                    is PingPacket -> {
                        val me = localHello
                        send(Envelope(from = me.deviceId, to = env.from.ifBlank { null }, packet = PongPacket(p.at, me.deviceId)))
                    }

                    is PongPacket -> {
                        if (p.at > 0) rttFlow.value = (System.currentTimeMillis() - p.at).coerceAtLeast(0L)
                    }

                    is ByePacket -> close("对方挂断${if (p.reason.isBlank()) "" else ": ${p.reason}"}")

                    else -> sink.onEnvelope(this, env)
                }
            }

            Wire.TYPE_CHUNK -> {
                val chunk = runCatching { Wire.decodeChunk(payload) }.getOrNull() ?: return
                sink.onChunk(this, chunk)
            }

            // 实时通话媒体：不进业务分派，直接交给通话层，保证零额外开销
            Wire.TYPE_CALL -> {
                val frame = runCatching { Wire.decodeCall(payload) }.getOrNull() ?: return
                sink.onCallFrame(this, frame)
            }

            // 帧级心跳。
            //
            // This used to echo the payload straight back "so the peer can
            // compute an RTT". That was wrong in two ways, and the second one
            // was severe: nothing anywhere decodes the echoed timestamp, and
            // since **both** sides run the same code, every echo was itself
            // answered by another echo. Fifteen seconds after a link went ready
            // the two phones locked into an unbounded ping-pong — measured at
            // roughly 2 MB/s and 120k frames/s, never stopping — which saturated
            // the write queue, kept the radio busy and drained the battery,
            // while still producing no RTT at all.
            //
            // A heartbeat only has to prove liveness, and the read timeout
            // already does that: any inbound frame refreshes `lastInbound`. So
            // the correct handler is to answer nothing.
            Wire.TYPE_PING -> Unit

            Wire.TYPE_BYE -> close("对方挂断")

            else -> Unit
        }
    }

    private fun adoptRemote(id: String, name: String, seed: Int) {
        if (id.isBlank()) return
        val cur = remoteFlow.value
        remoteFlow.value = cur.copy(
            deviceId = id,
            name = name.ifBlank { cur.name },
            avatarSeed = if (seed != 0) seed else cur.avatarSeed
        )
    }

    private fun markReady() {
        if (!ready.compareAndSet(false, true)) return
        stateFlow.value = State.READY
        runCatching { sink.onReady(this) }
    }

    private fun longToBytes(v: Long): ByteArray = ByteArray(8) { i -> (v ushr (56 - 8 * i)).toByte() }

    private companion object {
        /** 攒够这么多「忙碌纳秒」才算一个吞吐样本（约 500ms 的持续写入）。 */
        const val MIN_SAMPLE_NS = 500_000_000L
    }
}
