package com.liuli.btchat.bt

import com.liuli.btchat.core.Peer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * 自动连接已配对（且是好友）的设备。
 *
 * 需求原话：「隔多少秒就会检测到附近有没有已经配对的手机，如果有就会自动连接，
 * 打开软件就会自动连接」。
 *
 * 设计要点：
 * * **打开 App 立刻试一次**（[start] 后立刻跑第一轮），之后按 [intervalMs] 周期跑；
 * * 只在**当前没有任何已就绪链路**时才发起（有链路就说明已经连上了，避免反复折腾）；
 * * 每轮最多发起 [maxAttemptsPerTick] 个连接，避免多台设备同时连造成风暴；
 * * 每个地址单独退避：连续失败 15s → 30s → 60s → 上限 120s，成功后清零；
 * * 通话中或正在传输大文件时跳过本轮（不打扰正在进行的业务）；
 * * 息屏时 [intervalMs] 由调用方放宽到 30s（省电）。
 *
 * 所有外部依赖都用 lambda 注入，所以自测可以在纯 JVM 里驱动 [tickOnce]，不需要蓝牙。
 */
class AutoConnector(
    private val scope: CoroutineScope,
    /** 候选设备：已配对且是好友。 */
    private val peers: () -> List<Peer>,
    /** 该 deviceId / MAC 当前是否已经连着。 */
    private val isConnected: (String) -> Boolean,
    /** 发起连接，返回 false 表示适配器拒绝（不计入失败退避）。 */
    private val connect: (Peer) -> Boolean,
    /** 当前是否在通话或传大文件。 */
    private val busy: () -> Boolean,
    /** 轮询间隔（毫秒）。 */
    private val intervalMs: () -> Long,
    private val maxAttemptsPerTick: Int = 1
) {

    private var started = false
    private var job: kotlinx.coroutines.Job? = null

    /** address → 允许下次尝试的时间戳。 */
    private val nextAttemptAt = ConcurrentHashMap<String, Long>()

    /** address → 连续失败次数。 */
    private val failures = ConcurrentHashMap<String, Int>()

    /** 累计发起过多少次自动连接（诊断用）。 */
    @Volatile
    var attempts: Long = 0L
        private set

    /** 累计成功入队多少次（诊断用）。 */
    @Volatile
    var successes: Long = 0L
        private set

    /** 启动轮询；重复调用安全。 */
    fun start() {
        if (started) return
        started = true
        job = scope.launch {
            var first = true
            while (isActive) {
                if (!first) delay(intervalMs().coerceAtLeast(1_000L))
                first = false
                runCatching { tickOnce() }
            }
        }
    }

    fun stop() {
        started = false
        job?.cancel()
        job = null
        nextAttemptAt.clear()
        failures.clear()
    }

    /** 外部事件（例如扫描到了已配对设备）触发一次即时尝试。 */
    fun kick() {
        if (!started) return
        scope.launch { runCatching { tickOnce() } }
    }

    /**
     * 跑一轮。返回本轮实际发起的连接数。自测直接调它，行为完全确定。
     */
    fun tickOnce(now: Long = System.currentTimeMillis()): Int {
        if (busy()) return 0
        var launched = 0
        for (peer in peers()) {
            if (launched >= maxAttemptsPerTick) break
            val key = peer.address.ifBlank { peer.key }
            if (key.isBlank()) continue
            if (isConnected(peer.deviceId) || isConnected(peer.address)) continue
            val due = nextAttemptAt[key] ?: 0L
            if (now < due) continue
            val ok = connect(peer)
            attempts++
            if (ok) {
                successes++
                failures[key] = 0
                // 已经交给蓝牙去建链了，给一个短冷却，别在同一台设备上连点
                nextAttemptAt[key] = now + COOLDOWN_MS
                launched++
            } else {
                val n = (failures[key] ?: 0) + 1
                failures[key] = n
                nextAttemptAt[key] = now + backoffMs(n)
            }
        }
        return launched
    }

    /** 诊断快照：地址 → 还要等多久 / 失败次数。 */
    fun snapshot(): List<String> = nextAttemptAt.entries.map { (addr, due) ->
        val wait = ((due - System.currentTimeMillis()) / 1000).coerceAtLeast(0)
        "$addr 冷却=${wait}s 失败=${failures[addr] ?: 0}"
    }

    private fun backoffMs(failures: Int): Long {
        var ms = BASE_BACKOFF_MS
        repeat((failures - 1).coerceAtMost(4)) { ms *= 2 }
        return ms.coerceAtMost(MAX_BACKOFF_MS)
    }

    private companion object {
        const val BASE_BACKOFF_MS = 15_000L
        const val MAX_BACKOFF_MS = 120_000L

        /** 交给系统建链后的冷却时间。 */
        const val COOLDOWN_MS = 20_000L
    }
}
