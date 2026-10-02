package com.liuli.btchat.bt.relay

import com.liuli.btchat.bt.BtConstants

/**
 * 中继信封的一条缓存记录。
 *
 * 一条记录对应**一个原始消息**（`msgId`），可能同时挂着多个还没确认的收件人
 * （[pending]）—— 群消息一次要投给多个成员，共用同一份 payload，省内存。
 */
class RelayEntry(
    val msgId: String,
    val originId: String,
    val convId: String,
    val groupId: String?,
    val hop: Int,
    val expiresAt: Long,
    val payload: String,
    val bytes: Int,
    val storedAt: Long,
    /** 还没确认收到的收件人 deviceId。全部确认后这条记录就可以删了。 */
    val pending: MutableSet<String>
)

/**
 * 中继缓存：**有界 + 带 TTL**。
 *
 * 为什么必须有界：这是一个 epidemic（传染病式）转发网络，任何一条消息都可能在
 * 多台设备上被复制保管。没有上限的话，用户手机存储会被别人的聊天记录吃光。
 *
 * 淘汰策略：
 * 1. 先清 TTL 过期的（[BtConstants.RELAY_TTL_MS]，默认 48 小时）；
 * 2. 仍然超限就按「最久以前存入」淘汰，直到同时满足条数 [maxEntries] 与字节 [maxBytes]。
 *
 * 所有淘汰/过期/拒收都有计数（[expired] / [evicted] / [rejected]），调试页与自测可读。
 */
class RelayStore(
    private val maxEntries: Int = BtConstants.RELAY_MAX_ENTRIES,
    private val maxBytes: Long = BtConstants.RELAY_MAX_BYTES,
    private val ttlMs: Long = BtConstants.RELAY_TTL_MS,
    private val now: () -> Long = { System.currentTimeMillis() }
) {

    private val lock = Any()
    private val entries = LinkedHashMap<String, RelayEntry>() // 插入顺序 = 时间顺序
    private var totalBytes = 0L

    /** 累计存入条数。 */
    @Volatile
    var stored: Long = 0L
        private set

    /** 因为收件人全部确认而被删掉的条数。 */
    @Volatile
    var delivered: Long = 0L
        private set

    /** TTL 过期清理掉的条数。 */
    @Volatile
    var expired: Long = 0L
        private set

    /** 超过上限被淘汰的条数。 */
    @Volatile
    var evicted: Long = 0L
        private set

    /** 因为超限或非法被直接拒收的条数。 */
    @Volatile
    var rejected: Long = 0L
        private set

    fun now(): Long = now.invoke()

    /**
     * 存入 / 合并一条记录。
     *
     * 返回 true 表示缓存里现在有这条消息（可能是新插入，也可能是把新的收件人
     * 合并进了已有记录）。返回 false 表示被拒收（单条就超过字节上限）。
     */
    fun put(entry: RelayEntry): Boolean {
        if (entry.payload.length > maxBytes) {
            rejected++
            return false
        }
        val t = now()
        synchronized(lock) {
            sweepLocked(t)
            val existing = entries[entry.msgId]
            if (existing != null && existing.expiresAt > t) {
                // 同一条消息的新收件人合并进来，payload 只留一份
                existing.pending.addAll(entry.pending)
                touch(existing)
                return true
            }
            if (existing != null) removeLocked(existing.msgId, expired = true)
            entries[entry.msgId] = entry
            totalBytes += entry.bytes
            stored++
            enforceCapsLocked()
            return true
        }
    }

    /** 收件人确认：把该收件人从 pending 里去掉；全部确认后整条删除。 */
    fun confirm(msgId: String, destId: String): Boolean {
        synchronized(lock) {
            val e = entries[msgId] ?: return false
            e.pending.remove(destId)
            if (e.pending.isEmpty()) {
                removeLocked(msgId, expired = false)
                delivered++
            }
            return true
        }
    }

    fun remove(msgId: String): Boolean = synchronized(lock) {
        entries.containsKey(msgId) && removeLocked(msgId, expired = false)
    }

    fun contains(msgId: String): Boolean = synchronized(lock) { entries.containsKey(msgId) }

    fun hasPendingFor(destId: String): Boolean =
        synchronized(lock) { entries.values.any { destId in it.pending } }

    /** 缓存快照（按存入顺序）。 */
    fun snapshot(): List<RelayEntry> = synchronized(lock) {
        sweepLocked(now())
        entries.values.toList()
    }

    fun size(): Int = synchronized(lock) { entries.size }

    fun bytes(): Long = synchronized(lock) { totalBytes }

    /** 立刻清一次过期项，返回清理条数。 */
    fun sweep(): Int = synchronized(lock) { sweepLocked(now()) }

    fun clear() = synchronized(lock) {
        entries.clear()
        totalBytes = 0
    }

    fun statsLine(): String =
        "缓存=${size()} 条 / ${bytes()} B（累计存入=$stored 投递完成=$delivered 过期=$expired 淘汰=$evicted 拒收=$rejected）"

    // ------------------------------------------------------------- internals

    private fun touch(entry: RelayEntry) {
        // 重新插到末尾，保持 LRU 顺序
        entries.remove(entry.msgId)
        entries[entry.msgId] = entry
    }

    private fun removeLocked(msgId: String, expired: Boolean): Boolean {
        val e = entries.remove(msgId) ?: return false
        totalBytes -= e.bytes
        if (expired) this.expired++
        return true
    }

    private fun sweepLocked(t: Long): Int {
        var n = 0
        val it = entries.values.iterator()
        while (it.hasNext()) {
            val e = it.next()
            if (e.expiresAt in 1..t) {
                it.remove()
                totalBytes -= e.bytes
                expired++
                n++
            }
        }
        return n
    }

    private fun enforceCapsLocked() {
        while (entries.size > maxEntries || totalBytes > maxBytes) {
            val oldest = entries.values.firstOrNull() ?: break
            removeLocked(oldest.msgId, expired = false)
            evicted++
        }
    }
}
