package com.liuli.btchat.bt

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import com.liuli.btchat.core.LinkState
import com.liuli.btchat.core.Peer
import com.liuli.btchat.core.PeerSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 经典蓝牙适配器门面：状态、已配对设备、设备发现。
 *
 * 发现结果以 [Peer] 列表形式暴露；[deviceId] 在 RFCOMM 握手前是未知的（经典蓝牙
 * 只在应用层交换身份），所以发现到的 [Peer] 只用 MAC 作为 [Peer.key]，连接握手后
 * 由 [Router] 补齐真实 deviceId。
 *
 * 所有对 BluetoothAdapter 的调用都先检查运行时权限并用 `runCatching` 兜底 ——
 * 权限被撤销时系统会抛 [SecurityException]，不应该让引擎崩掉。
 */
class BtDiscovery(private val ctx: Context) {

    private val discoveredState = MutableStateFlow<List<Peer>>(emptyList())

    /** 扫描到的（未配对）设备。 */
    val discovered: StateFlow<List<Peer>> = discoveredState

    private val scanningState = MutableStateFlow(false)

    /** 是否正在扫描。 */
    val scanning: StateFlow<Boolean> = scanningState

    private val adapterStateFlow = MutableStateFlow(LinkState.OFF)

    /** 适配器开关状态（ACTION_STATE_CHANGED 实时驱动）。 */
    val adapterState: StateFlow<LinkState> = adapterStateFlow

    private var receiver: BroadcastReceiver? = null
    private var registered = false

    /** 发现到一台设备时的回调（已配对设备出现 → 触发自动连接）。 */
    @Volatile
    var onPeerFound: ((Peer) -> Unit)? = null

    private val scanResults = java.util.concurrent.atomic.AtomicInteger(0)

    @Volatile
    private var lastScanError: String? = null

    /** 本次扫描发现了多少台设备。 */
    val scanResultCount: Int get() = scanResults.get()

    /** 最近一次扫描失败/无结果的原因；一切正常时为 null。 */
    val scanProblem: String? get() = lastScanError

    /** 系统蓝牙适配器；设备无蓝牙或服务缺失时为 null。 */
    val adapter: BluetoothAdapter?
        get() = runCatching {
            (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
        }.getOrNull()

    /** 本机蓝牙名称（需要 BLUETOOTH_CONNECT）。 */
    val adapterName: String
        get() = runCatching { adapter?.name.orEmpty() }.getOrDefault("")

    /** 适配器是否可用（存在且已开启）。 */
    fun isEnabled(): Boolean = runCatching { adapter?.isEnabled == true }.getOrDefault(false)

    // ------------------------------------------------------------ 生命周期

    /** 注册广播接收器并同步一次适配器状态。重复调用安全。 */
    fun start() {
        if (!registered) {
            val r = object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    when (intent?.action) {
                        BluetoothDevice.ACTION_FOUND -> onFound(intent)
                        BluetoothAdapter.ACTION_DISCOVERY_STARTED -> scanningState.value = true
                        BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                            scanningState.value = false
                            // 一台都没搜到：把最可能的原因如实记下来，别让 UI 猜
                            if (scanResults.get() == 0) {
                                lastScanError =
                                    "扫描完成但一台设备都没发现：Android 12+ 上若 BLUETOOTH_SCAN 未声明 " +
                                        "neverForLocation，系统会要求定位权限+定位服务开启才返回结果；" +
                                        "另外对方需要处于「可被发现」状态（已配对设备不会出现在这里）"
                            }
                        }
                        BluetoothAdapter.ACTION_STATE_CHANGED -> {
                            refreshAdapterState()
                            // 蓝牙被关掉时扫描也随之结束
                            val st = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
                            if (st != BluetoothAdapter.STATE_ON) scanningState.value = false
                        }
                        BluetoothDevice.ACTION_BOND_STATE_CHANGED -> onBondChanged(intent)
                    }
                }
            }
            val filter = IntentFilter().apply {
                addAction(BluetoothDevice.ACTION_FOUND)
                addAction(BluetoothAdapter.ACTION_DISCOVERY_STARTED)
                addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
                addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
                addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
            }
            // 监听的全部是系统广播，用 NOT_EXPORTED 即可（避免收到第三方应用伪造的广播）。
            runCatching {
                ContextCompat.registerReceiver(ctx, r, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
                receiver = r
                registered = true
            }
        }
        refreshAdapterState()
    }

    /** 注销接收器、停止扫描。重复调用安全。 */
    fun stop() {
        stopScan()
        receiver?.let { runCatching { ctx.unregisterReceiver(it) } }
        receiver = null
        registered = false
    }

    /** 依据适配器实际状态刷新 [adapterState]。 */
    fun refreshAdapterState() {
        adapterStateFlow.value = when {
            !BtPermissions.canConnect(ctx) -> LinkState.UNAUTHORIZED
            adapter == null -> LinkState.OFF
            !isEnabled() -> LinkState.OFF
            else -> LinkState.READY
        }
    }

    // ------------------------------------------------------------ 已配对设备

    /** 系统里已配对的设备列表。没有权限时返回空表而不是抛异常。 */
    fun bondedPeers(): List<Peer> {
        if (!BtPermissions.canConnect(ctx)) return emptyList()
        val devices = runCatching { adapter?.bondedDevices }.getOrNull() ?: return emptyList()
        return devices.mapNotNull { d -> d.toPeer(PeerSource.BONDED) }
    }

    // -------------------------------------------------------------- 扫描

    /**
     * 启动设备发现。返回 false 表示权限不足或适配器拒绝。
     *
     * 调用方负责在结束后 [stopScan]；系统扫描上限约 12 秒，随后会收到
     * ACTION_DISCOVERY_FINISHED，[scanning] 会自动复位。
     */
    fun startScan(): Boolean {
        refreshAdapterState()
        val a = adapter
        if (a == null) {
            lastScanError = "本机没有蓝牙适配器"
            return false
        }
        if (!BtPermissions.canScan(ctx)) {
            lastScanError = "缺少 BLUETOOTH_SCAN 权限（授权后才能搜索）"
            return false
        }
        if (runCatching { a.isEnabled }.getOrDefault(false) != true) {
            lastScanError = "蓝牙未开启"
            return false
        }
        // 扫描新设备前清掉旧结果，列表才是「本次搜到的」
        discoveredState.value = emptyList()
        scanResults.set(0)
        lastScanError = null
        val started = runCatching { a.startDiscovery() }.getOrDefault(false)
        if (!started) {
            lastScanError = "startDiscovery() 被系统拒绝：适配器忙或扫描权限不可用"
        }
        if (started) scanningState.value = true
        return started
    }

    /**
     * 扫描诊断。Android 12+ 最容易踩的坑：`BLUETOOTH_SCAN` 没声明
     * `android:usesPermissionFlags="neverForLocation"` 时，系统认为扫描结果可能用于
     * 推断位置，于是要求**定位权限 + 定位服务开启**才回调 ACTION_FOUND ——
     * 表现就是「startDiscovery() 成功、一台都搜不到」。这里把各项条件如实列出来。
     */
    fun diagnostics(): String {
        val scanGranted = BtPermissions.canScan(ctx)
        val locationGranted = runCatching {
            androidx.core.content.ContextCompat.checkSelfPermission(
                ctx, android.Manifest.permission.ACCESS_FINE_LOCATION
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)
        return buildString {
            append("适配器=").append(adapterName.ifBlank { "无" })
            append("，蓝牙开启=").append(isEnabled())
            append("，SCAN 权限=").append(scanGranted)
            append("，定位权限=").append(locationGranted)
            append("，已配对=").append(bondedPeers().size)
            append("，扫描中=").append(scanningState.value)
            append("，本次发现=").append(scanResults.get())
            lastScanError?.let { append("；问题: ").append(it) }
        }
    }

    /** 停止设备发现。 */
    fun stopScan() {
        runCatching { if (adapter?.isDiscovering == true) adapter?.cancelDiscovery() }
        scanningState.value = false
    }

    // ------------------------------------------------------------- internals

    private fun onFound(intent: Intent) {
        val device: BluetoothDevice? = IntentCompat.getParcelableExtra(
            intent, BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java
        )
        val peer = device?.toPeer(PeerSource.DISCOVERED) ?: return
        val rssi = intent.getShortExtra(BluetoothDevice.EXTRA_RSSI, 0).toInt()
        scanResults.incrementAndGet()
        val enriched = peer.copy(rssi = rssi, lastSeen = System.currentTimeMillis())
        upsert(enriched)
        onPeerFound?.invoke(enriched)
    }

    private fun onBondChanged(intent: Intent) {
        val device = IntentCompat.getParcelableExtra(
            intent, BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java
        ) ?: return
        val state = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR)
        if (state == BluetoothDevice.BOND_BONDED) {
            device.toPeer(PeerSource.BONDED)?.let { upsert(it) }
        }
    }

    private fun upsert(peer: Peer) {
        val list = discoveredState.value
        val idx = list.indexOfFirst { it.address == peer.address }
        discoveredState.value = if (idx >= 0) {
            list.toMutableList().also { it[idx] = peer.copy(deviceId = list[idx].deviceId) }
        } else {
            list + peer
        }
    }

    /**
     * 读设备名称。API 30+ 用 [BluetoothDevice.getAlias]（用户在系统设置里改过的别名），
     * 取不到再退回原名，最后退回 MAC。
     */
    private fun BluetoothDevice.displayName(): String = runCatching { alias }.getOrNull()?.takeIf { it.isNotBlank() }
        ?: runCatching { name }.getOrNull()?.takeIf { it.isNotBlank() }
        ?: address

    private fun BluetoothDevice.toPeer(source: PeerSource): Peer? {
        if (!BtPermissions.canConnect(ctx)) return null
        val mac = runCatching { address }.getOrNull()?.takeIf { it.isNotBlank() } ?: return null
        return Peer(
            address = mac,
            deviceId = "",
            name = displayName(),
            source = source,
            lastSeen = System.currentTimeMillis()
        )
    }
}
