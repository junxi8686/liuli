package com.liuli.btchat.bt

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.Context
import com.liuli.btchat.core.Duplex
import com.liuli.btchat.core.SocketDuplex
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

/** 建立成功的一条链路：字节管道 + 对端 MAC + 是否走的加密通道。 */
data class OpenedLink(
    val duplex: Duplex,
    val address: String,
    val secure: Boolean
)

/**
 * RFCOMM 通道管理：监听 + 主动连接。
 *
 * * **监听**：安全/不安全两条服务记录各起一个守护线程跑 `accept()` 循环，
 *   把接受到的 socket 包成 [SocketDuplex] 交给上层。监听线程是阻塞式的，
 *   所以用真实线程而不是协程 —— [stop] 里关掉 server socket 就能让 accept 立刻返回。
 * * **连接**：先试安全通道 [BtConstants.UUID_SECURE]，任何一步失败（未配对、对端
 *   只开了不安全通道等）都回落到不安全通道 [BtConstants.UUID_INSECURE]。
 *   建链整体跑在 [Dispatchers.IO]，并带超时兜底（关 socket 中断阻塞的 connect）。
 */
class BtTransport(
    private val ctx: Context,
    private val scope: CoroutineScope
) {

    /** 收到对端连接时的回调（在监听线程上调用，实现方需自己切线程）。 */
    @Volatile
    var onAccepted: ((OpenedLink) -> Unit)? = null

    /** 监听状态变化（true = 至少一条通道在监听）。 */
    @Volatile
    var onListeningChanged: ((Boolean) -> Unit)? = null

    private val servers = ArrayList<BluetoothServerSocket>(2)
    private val acceptThreads = ArrayList<Thread>(2)

    /**
     * 建链超时看门狗的作用域。刻意独立于调用方的 scope：Engine.stop() 会取消它自己的
     * scope，看门狗若跟着一起死，socket.connect() 就会永久阻塞（25 秒超时形同虚设）。
     */
    @Volatile
    private var timeoutScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var running = false

    /** 是否至少有一条通道在监听。 */
    val listening: Boolean get() = running && servers.isNotEmpty()

    private val adapter: BluetoothAdapter?
        get() = runCatching {
            (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
        }.getOrNull()

    // ------------------------------------------------------------- 监听端

    /**
     * 开始监听。返回是否至少成功注册了一条通道。
     *
     * 安全通道注册失败（例如适配器刚开、权限被撤）不影响不安全通道，
     * 反之亦然：两条都失败才算启动失败。
     */
    fun start(): Boolean {
        if (running) return true
        if (!BtPermissions.canConnect(ctx)) return false
        val a = adapter ?: return false
        if (runCatching { a.isEnabled }.getOrDefault(false) != true) return false

        val secure = runCatching {
            a.listenUsingRfcommWithServiceRecord(
                BtConstants.SERVICE_NAME_SECURE, BtConstants.UUID_SECURE
            )
        }.getOrNull()

        val insecure = runCatching {
            a.listenUsingInsecureRfcommWithServiceRecord(
                BtConstants.SERVICE_NAME_INSECURE, BtConstants.UUID_INSECURE
            )
        }.getOrNull()

        if (secure == null && insecure == null) return false

        running = true
        secure?.let { spawnAcceptLoop(it, secure = true) }
        insecure?.let { spawnAcceptLoop(it, secure = false) }
        onListeningChanged?.invoke(true)
        return true
    }

    /** 关闭所有监听通道并结束 accept 线程。重复调用安全。 */
    fun stop() {
        if (!running && servers.isEmpty()) return
        running = false
        runCatching { timeoutScope.cancel() }
        for (s in servers.toList()) runCatching { s.close() }
        servers.clear()
        // accept() 会因 close 抛 IOException 而返回，join 一小会儿保证线程退出。
        for (t in acceptThreads.toList()) runCatching { t.join(500) }
        acceptThreads.clear()
        onListeningChanged?.invoke(false)
    }

    private fun spawnAcceptLoop(server: BluetoothServerSocket, secure: Boolean) {
        servers.add(server)
        val t = Thread({ acceptLoop(server, secure) }, "liuli-rfcomm-${if (secure) "secure" else "insecure"}")
        t.isDaemon = true
        acceptThreads.add(t)
        t.start()
    }

    private fun acceptLoop(server: BluetoothServerSocket, secure: Boolean) {
        while (running) {
            val socket: BluetoothSocket = try {
                server.accept()
            } catch (_: IOException) {
                // stop() 关掉了 server socket，或者适配器被关闭
                return
            } catch (_: Throwable) {
                if (!running) return
                // 偶发失败（例如适配器瞬时不可用），稍等再试
                runCatching { Thread.sleep(300) }
                continue
            }
            val link = runCatching { socket.toLink(secure) }.getOrNull()
            if (link == null) {
                runCatching { socket.close() }
                continue
            }
            onAccepted?.invoke(link)
        }
    }

    private fun BluetoothSocket.toLink(secure: Boolean): OpenedLink? {
        val mac = runCatching { remoteDevice?.address }.getOrNull().orEmpty()
        val duplex = SocketDuplex(
            label = "bt-in-${mac.ifBlank { hashCode().toString() }}",
            rawIn = inputStream,
            rawOut = outputStream,
            onClose = { runCatching { close() } }
        )
        return OpenedLink(duplex, mac, secure)
    }

    // ------------------------------------------------------------- 连接端

    /**
     * 主动连接 [address]。先安全通道、失败回落不安全通道。
     *
     * 整个建链过程带着 [BtConstants.CONNECT_TIMEOUT_MS] 的超时：超时后强关 socket
     * 让阻塞中的 `connect()` 抛异常退出。失败时抛出最后一次的异常。
     */
    suspend fun open(address: String): OpenedLink = withContext(Dispatchers.IO) {
        val a = adapter ?: throw IOException("本机没有蓝牙适配器")
        if (!BtPermissions.canConnect(ctx)) throw SecurityException("缺少 BLUETOOTH_CONNECT 权限")
        if (runCatching { a.isEnabled }.getOrDefault(false) != true) throw IOException("蓝牙未开启")

        // 扫描会显著降低建链成功率，先停掉
        runCatching { if (a.isDiscovering) a.cancelDiscovery() }

        val device = runCatching { a.getRemoteDevice(address) }
            .getOrElse { throw IOException("无效的蓝牙地址: $address") }

        var lastError: Throwable? = null
        for (secure in booleanArrayOf(true, false)) {
            val socket = try {
                if (secure) device.createRfcommSocketToServiceRecord(BtConstants.UUID_SECURE)
                else device.createInsecureRfcommSocketToServiceRecord(BtConstants.UUID_INSECURE)
            } catch (t: Throwable) {
                lastError = t
                continue
            }
            try {
                connectWithTimeout(socket)
                val duplex = SocketDuplex(
                    label = "bt-out-$address",
                    rawIn = socket.inputStream,
                    rawOut = socket.outputStream,
                    onClose = { runCatching { socket.close() } }
                )
                return@withContext OpenedLink(duplex, address, secure)
            } catch (t: Throwable) {
                lastError = t
                runCatching { socket.close() }
                // 安全通道失败是常态（对端未配对 / 只开了不安全通道），静默回落
            }
        }
        throw IOException("连接 $address 失败: ${lastError?.message ?: "未知错误"}", lastError)
    }

    /** 带超时的 `socket.connect()`。 */
    private suspend fun connectWithTimeout(socket: BluetoothSocket) {
        val killer: Job = ensureTimeoutScope().launch {
            delay(BtConstants.CONNECT_TIMEOUT_MS)
            runCatching { socket.close() }
        }
        try {
            socket.connect()
        } finally {
            killer.cancel()
        }
    }

    private fun ensureTimeoutScope(): CoroutineScope {
        val cur = timeoutScope
        if (cur.isActive) return cur
        val fresh = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        timeoutScope = fresh
        return fresh
    }
}
