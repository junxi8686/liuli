package com.liuli.btchat.bt

import android.content.Context
import android.net.Uri
import com.liuli.btchat.bt.call.CallController
import com.liuli.btchat.bt.call.CallMedia
import com.liuli.btchat.bt.call.CallState
import com.liuli.btchat.bt.relay.RelayEntry
import com.liuli.btchat.bt.relay.RelayStore
import com.liuli.btchat.core.Attachment
import com.liuli.btchat.core.CallFrame
import com.liuli.btchat.core.ChatStore
import com.liuli.btchat.core.Chunk
import com.liuli.btchat.core.Contact
import com.liuli.btchat.core.Conversation
import com.liuli.btchat.core.ConvKind
import com.liuli.btchat.core.Duplex
import com.liuli.btchat.core.Envelope
import com.liuli.btchat.core.HelloPacket
import com.liuli.btchat.core.ImportedMedia
import com.liuli.btchat.core.MediaVault
import com.liuli.btchat.core.Member
import com.liuli.btchat.core.MemoryDuplex
import com.liuli.btchat.core.Message
import com.liuli.btchat.core.MsgKind
import com.liuli.btchat.core.MsgState
import com.liuli.btchat.core.Peer
import com.liuli.btchat.core.Prefs
import com.liuli.btchat.core.SettingsApi
import com.liuli.btchat.core.TransferProgress
import com.liuli.btchat.core.TransferState
import com.liuli.btchat.core.Wire
import com.liuli.btchat.core.newId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.Random
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger

/**
 * 端到端自测：不依赖第二台手机。
 *
 * 用 [com.liuli.btchat.core.MemoryDuplex] 在**进程内**拉出两条全双工管道（早期版本用
 * loopback TCP socket，那在真机上会因为没有 `INTERNET` 权限而第一步就失败），把三个
 * 「节点」（各自的 [Router] + [TransferManager] + [Connection] + 内存 Store）按
 * **星型拓扑**接起来（A 在中间，B、C 挂在两边），然后跑真实业务流：
 *
 * 1. HELLO 握手、身份互换；
 * 2. 单聊文本 → 落库 → 回执 → DELIVERED → 已读；
 * 3. typing 指示；
 * 4. 1 MB 随机文件直传，逐字节 sha256 校验；
 * 5. 建群 + 群文本：B 发的包由群主 A 中继给 C（`hop + 1`，且 C 只收到一次）；
 * 6. 群文件：分片经群主扇出，B→A→C 与 A 本地各落一份，sha256 校验；
 * 7. 断线后发送落库为 FAILED。
 *
 * 走的是和真机完全相同的 [Connection] / [Router] / [TransferManager] 代码路径，
 * 唯一被替换的是「字节从蓝牙 socket 来」这一步 —— 换成 loopback TCP。
 *
 * 返回人类可读的多行报告（永不抛异常），可直接显示在调试页或断言在单元测试里。
 */
object SelfTest {

    /** 调试页入口：报告文件写到应用缓存目录。 */
    suspend fun run(ctx: Context): String = run(File(ctx.cacheDir, "liuli-selftest"))

    /**
     * 单元测试入口：不碰任何 Android API，只需要一个可写目录。
     *
     * 注意 `Svc` 全局服务**不参与**自测 —— 每个节点用的都是本文件里的内存实现，
     * 所以自测既不会污染真实聊天记录，也能在纯 JVM 环境里跑。
     */
    suspend fun run(workDir: File): String {
        val report = Report()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val root = File(workDir, "run-" + System.currentTimeMillis())
        root.mkdirs()
        try {
            runScenarios(scope, root, report)
        } catch (t: Throwable) {
            report.step("自测异常终止", false, "t: ${t.message ?: t.javaClass.simpleName}")
        } finally {
            scope.cancel()
        }
        if (report.fail == 0) runCatching { root.deleteRecursively() }
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).format(Date())
        return report.render("琉璃 · 蓝牙传输层自测报告  $stamp")
    }

    // ------------------------------------------------------------- 场景主体

    private suspend fun runScenarios(scope: CoroutineScope, root: File, report: Report) {
        val a = Node("A", "st-A", "阿离", 12, root, scope, autoAccept = true)
        val b = Node("B", "st-B", "小八", 34, root, scope, autoAccept = true)
        val c = Node("C", "st-C", "阿七", 56, root, scope, autoAccept = true)
        // 默认互为好友：连接本身不再是「加好友」，所以先显式建立好友关系，
        // 后面的聊天/文件/通话用例才走正常路径（拉黑与删好友另有专门用例）。
        a.befriend(b)
        a.befriend(c)
        b.befriend(a)
        c.befriend(a)

        val w = World(scope, root, report, a, b, c)
        // 拆成几个场景方法：单个方法体超过 JVM 的 64KB 限制会编译失败
        linkAndChatScenario(w)
        groupScenario(w)
        socialScenario(w)
        callScenario(w)
        relayScenario(scope, root, report)
        disconnectScenario(w)
    }

    /**
     * 离线消息中转（存储转发）—— 严格复演用户描述的那条链。
     *
     * 这条用例是需求的核心证明，断言写得比别处更「硬」：
     * **B、C 的消息库里必须没有那条私聊**（不是「应该有」，而是「必须没有」），
     * 一旦泄漏就是 FAILED。
     */
    private suspend fun relayScenario(scope: CoroutineScope, root: File, report: Report) {
        var t0 = now()
        val a = Node("RA", "ra-A", "阿离", 11, root, scope, autoAccept = true)
        val b = Node("RB", "ra-B", "小八", 22, root, scope, autoAccept = true)
        val c = Node("RC", "ra-C", "阿七", 33, root, scope, autoAccept = true)
        val d = Node("RD", "ra-D", "阿九", 44, root, scope, autoAccept = true)
        val group = "relay-group"
        val all = listOf(a, b, c, d)
        for (n in all) {
            n.store.saveConversation(
                Conversation(id = group, kind = ConvKind.GROUP, title = "接力群", iAmOwner = n === a)
            )
            n.store.setMembers(group, all.map { Member(it.deviceId, it.name, it.seed) })
            for (o in all) if (o !== n) n.befriend(o)
        }
        val convAD = a.router.ensureDirectConv("ra-D", "阿九", 44)
        val convDA = d.router.ensureDirectConv("ra-A", "阿离", 11)

        // ---- ① A 只和 B 相连：A 的群消息 B 直接收到，C/D 的信封交给 B 保管
        val pairAB = openPair("relay-ab")
        if (pairAB == null) {
            report.step("离线中转链路建立", false, "loopback 建不起来")
            return
        }
        a.attach(pairAB.first)
        b.attach(pairAB.second)
        val handshaked = await(8_000) { a.router.readyCount == 1 && b.router.readyCount == 1 }
        val groupText = "A 在只有 B 在线时发的群消息"
        val groupMsg = a.router.sendText(group, groupText)
        val bGotGroup = await(5_000) { b.store.messages(group).any { it.text == groupText } }
        val cachedAtB = await(5_000) { b.relay.hasPendingFor("ra-C") && b.relay.hasPendingFor("ra-D") }
        report.step(
            "① 群消息：B 直收，C/D 的信封交 B 保管",
            handshaked && bGotGroup && cachedAtB,
            "B 收到群消息=$bGotGroup；B 缓存里有 C/D 的信封=$cachedAtB；${b.relay.statsLine()}",
            now() - t0
        )

        // ---- ② A 掉线、C 上线连 B → C 收到 A 那条原始消息
        t0 = now()
        a.router.closeAll("A 掉线")
        await(5_000) { b.router.readyCount == 0 }
        val pairBC = openPair("relay-bc")
        if (pairBC == null) return
        b.attach(pairBC.first)
        c.attach(pairBC.second)
        val cGotGroup = await(10_000) { c.store.messages(group).any { it.text == groupText } }
        val cStamp = c.store.messages(group).firstOrNull { it.text == groupText }?.sentAt
        val originalStamp = a.store.message(groupMsg?.id.orEmpty())?.sentAt
        report.step(
            "② A 掉线、C 上线连 B → C 收到 A 那条原始群消息",
            cGotGroup && cStamp != null && cStamp == originalStamp,
            "C 收到=$cGotGroup；时间戳用原始发送时间=${cStamp == originalStamp}",
            now() - t0
        )

        // ---- ③ A→D 私聊，A 当时连的是 B：B 只是搬运工
        t0 = now()
        val pairAB2 = openPair("relay-ab2")
        if (pairAB2 == null) return
        a.attach(pairAB2.first)
        b.attach(pairAB2.second)
        await(8_000) { a.router.readyCount >= 1 && b.router.readyCount >= 1 }
        val secretText = "这是 A 和 D 的私聊，中间人不许看见"
        val secretMsg = a.router.sendText(convAD.id, secretText)
        val bCachedSecret = await(5_000) { b.relay.hasPendingFor("ra-D") }
        // ★ 核心断言：B 的消息库里必须没有这条
        val bLeaked = b.store.messages(convDA.id).any { it.text == secretText } ||
            b.store.searchMessages(secretText, 10).isNotEmpty()
        report.step(
            "③ 私聊 A→D 经 B 保管：B 缓存有、消息库里必须没有",
            bCachedSecret && !bLeaked,
            "B 缓存里有=$bCachedSecret；**B 消息库泄漏=$bLeaked**（必须 false）；" +
                "A 侧状态=${a.store.message(secretMsg?.id.orEmpty())?.state}",
            now() - t0
        )
        if (bLeaked) return

        // ---- ④ B 与 C 相连：C 也缓存，同样不显示
        t0 = now()
        val cCachedSecret = c.relay.hasPendingFor("ra-D")
        val cLeaked = c.store.searchMessages(secretText, 10).isNotEmpty()
        report.step(
            "④ C 从 B 处接到这条私聊：只缓存、消息库里同样没有",
            cCachedSecret && !cLeaked,
            "C 缓存里有=$cCachedSecret；**C 消息库泄漏=$cLeaked**（必须 false）",
            now() - t0
        )
        if (cLeaked) return

        // ---- ⑤ C 连上 D：D 收到 A 的原始消息（D 从未连过 A）
        t0 = now()
        val pairCD = openPair("relay-cd")
        if (pairCD == null) return
        c.attach(pairCD.first)
        d.attach(pairCD.second)
        val dGotSecret = await(10_000) { d.store.messages(convDA.id).any { it.text == secretText } }
        val dSender = d.store.messages(convDA.id).firstOrNull { it.text == secretText }?.senderId
        val neverConnected = !a.router.isOnline("ra-D") && !d.router.isOnline("ra-A")
        val purgedAtC = await(5_000) { !c.relay.hasPendingFor("ra-D") }
        report.step(
            "⑤ C 连上 D → D 收到原始消息（D 与 A 从未直连），中间人投递后清缓存",
            dGotSecret && dSender == "ra-A" && neverConnected && purgedAtC,
            "D 收到=$dGotSecret（senderId=$dSender）；A/D 从未直连=$neverConnected；C 投递后清缓存=$purgedAtC",
            now() - t0
        )

        // ---- ⑥ 环回：A 重新连上 C，同一条消息不重复投递、不无限转发
        t0 = now()
        val beforeA = a.store.messages(group).size + a.store.messages(convAD.id).size
        val pairAC = openPair("relay-ac")
        if (pairAC != null) {
            a.attach(pairAC.first)
            c.attach(pairAC.second)
            await(8_000) { a.router.readyCount >= 1 }
            delay(1_200)
        }
        val afterA = a.store.messages(group).size + a.store.messages(convAD.id).size
        val dDuplicates = d.store.messages(convDA.id).count { it.text == secretText }
        val cStillClean = c.store.searchMessages(secretText, 10).isEmpty()
        report.step(
            "⑥ 环回：A 重新连上 C，不重复投递、不无限转发",
            afterA == beforeA && dDuplicates == 1 && cStillClean,
            "A 侧消息数 $beforeA → $afterA（应不变）；D 侧该消息份数=$dDuplicates（应=1）；C 仍未入库=$cStillClean",
            now() - t0
        )

        // ---- ⑥.5 媒体的「等待发送方上线」+ 手动取消
        // A 现在连着 C（D 连不上 A）。A 发一张图给 D：信封能到 D，但字节的持有者 A
        // 与 D 之间没有链路 → D 应如实停在「等待发送方上线」，而不是假装在下载。
        t0 = now()
        val picFile = File(root, "relay-pic.bin").apply { writeBytes(ByteArray(48 * 1024) { 0x2B }) }
        val picMedia = ImportedMedia(
            filePath = picFile.absolutePath, thumbPath = null, thumbB64 = null,
            name = picFile.name, mime = "application/octet-stream", size = picFile.length(),
            sha256 = sha256Of(picFile)
        )
        val picMsg = a.transfers.send(convAD.id, picMedia, MsgKind.FILE)
        val picTid = picMsg?.attachment?.transferId.orEmpty()
        val dWaiting = await(8_000) {
            d.transferFlow.value.any { it.transferId == picTid && it.state == TransferState.WAITING_SENDER }
        }
        val dWaitNote = d.transferFlow.value.firstOrNull { it.transferId == picTid }?.waitNote
        val dNoBytes = d.store.message(picMsg?.id.orEmpty())?.attachment?.localPath == null
        // 自动下载 ≠ 不能取消：等待中的任务照样可以手动取消
        d.transfers.cancel(picTid)
        val cancelled = await(5_000) {
            d.transferFlow.value.any { it.transferId == picTid && it.state == TransferState.CANCELLED }
        }
        report.step(
            "⑧ 媒体信封先到、字节没人持有 → WAITING_SENDER；手动取消仍可用",
            dWaiting && dNoBytes && cancelled,
            "D 处于等待发送方上线=$dWaiting（waitNote=「$dWaitNote」）；D 没拿到字节=$dNoBytes；手动取消=$cancelled",
            now() - t0
        )

        // ---- ⑦ TTL 与上限：超时淘汰、超限淘汰、确认后删除，都有计数
        t0 = now()
        var fakeNow = 1_000_000L
        val cache = RelayStore(maxEntries = 5, maxBytes = 1024, ttlMs = 60_000L, now = { fakeNow })
        fun mkEntry(id: String, bytes: Int = 10, expiresAt: Long = fakeNow + 60_000L) = RelayEntry(
            msgId = id, originId = "x", convId = "c", groupId = null, hop = 1,
            expiresAt = expiresAt, payload = "p".repeat(bytes), bytes = bytes,
            storedAt = fakeNow, pending = mutableSetOf("dest-$id")
        )
        repeat(8) { cache.put(mkEntry("m$it")) }
        val capped = cache.size() == 5 && cache.evicted == 3L
        cache.confirm("m7", "dest-m7")
        val confirmed = cache.size() == 4 && cache.delivered == 1L
        fakeNow += 120_000L
        val swept = cache.sweep() == 4 && cache.size() == 0
        report.step(
            "⑦ 中继缓存：超限淘汰 + TTL 过期 + 投递确认删除",
            capped && confirmed && swept,
            "8 条入 5 条上限 → 实际=${cache.size()} 淘汰=${cache.evicted}；确认后删除=$confirmed；" +
                "TTL 过期清理=$swept（清理 ${cache.expired} 条）；${cache.statsLine()}",
            now() - t0
        )

        for (n in all) n.router.closeAll("relay 场景结束")
        report.note("离线中转 A：${a.relay.statsLine()}")
        report.note("离线中转 B：${b.relay.statsLine()}")
    }

    /** 跨场景共享的状态（节点、链路、会话 id）。 */
    private class World(
        val scope: CoroutineScope,
        val root: File,
        val report: Report,
        val a: Node,
        val b: Node,
        val c: Node
    ) {
        lateinit var connAbB: Connection
        lateinit var connAcC: Connection
        lateinit var convAb: Conversation
        lateinit var convBa: Conversation
        lateinit var group: Conversation
        var bigFileMsgId: String = ""
    }

    /** 链路 + 单聊基础：握手、文本、回执、typing、已读、1MB 文件直传。 */
    private suspend fun linkAndChatScenario(w: World) {
        val a = w.a
        val b = w.b
        val c = w.c
        val scope = w.scope
        val root = w.root
        val report = w.report
        var t0 = now()
        val pairAb = openPair("ab")
        val pairAc = openPair("ac")
        report.step("建立 loopback 链路", pairAb != null && pairAc != null, "A↔B / A↔C 两条 TCP 管道", now() - t0)
        if (pairAb == null || pairAc == null) return

        val connAbA = a.attach(pairAb.first)
        val connAbB = b.attach(pairAb.second)
        val connAcA = a.attach(pairAc.first)
        val connAcC = c.attach(pairAc.second)

        // ---- 2. HELLO 握手
        t0 = now()
        val handshaked = await(8_000) {
            connAbA.isReady && connAbB.isReady && connAcA.isReady && connAcC.isReady &&
                a.router.readyCount == 2 && b.router.readyCount == 1 && c.router.readyCount == 1
        }
        val identityOk = handshaked && connAbB.remoteId == "st-A" && connAbA.remoteId == "st-B" &&
            connAcC.remoteId == "st-A" && connAbA.remoteName == "小八"
        report.step(
            "HELLO / HELLO_ACK 握手",
            identityOk,
            "A↔B: ${connAbB.remoteId}(${connAbB.remoteName}) / A↔C: ${connAcC.remoteId}(${connAcC.remoteName})",
            now() - t0
        )
        if (!identityOk) return

        // ---- 3. 单聊文本
        val convAb = a.router.ensureDirectConv("st-B", "小八", 34)
        val convBa = b.router.ensureDirectConv("st-A", "阿离", 12)
        val text1 = "你好，琉璃自测 #${System.currentTimeMillis() % 1000}"
        t0 = now()
        val sent = a.router.sendText(convAb.id, text1)
        val gotAtB = await(8_000) { b.store.messages(convBa.id).any { it.text == text1 } }
        val storedAtB = b.store.messages(convBa.id).firstOrNull { it.text == text1 }
        report.step(
            "单聊文本 A→B 并落库",
            gotAtB && storedAtB?.senderId == "st-A" && storedAtB.outgoing == false,
            "msgId=${sent?.id?.take(8)}, B 侧会话=${convBa.id.take(8)}, senderId=${storedAtB?.senderId}",
            now() - t0
        )

        // ---- 4. 送达回执
        t0 = now()
        val delivered = await(8_000) { a.store.message(sent?.id.orEmpty())?.state == MsgState.DELIVERED }
        report.step(
            "RECEIPT 回执 → 发送方 DELIVERED",
            delivered,
            "A 侧状态=${a.store.message(sent?.id.orEmpty())?.state}",
            now() - t0
        )

        // ---- 5. typing 指示
        t0 = now()
        a.router.setTyping(convAb.id, true)
        val typingOn = await(4_000) { b.typing.value.contains(convBa.id) }
        a.router.setTyping(convAb.id, false)
        val typingOff = await(4_000) { !b.typing.value.contains(convBa.id) }
        report.step("TYPING 指示开关", typingOn && typingOff, "B 侧 typing 集合 = ${b.typing.value}", now() - t0)

        // ---- 6. 已读回执
        t0 = now()
        b.router.markRead(convBa.id)
        val read = await(8_000) { a.store.message(sent?.id.orEmpty())?.state == MsgState.READ }
        report.step(
            "READ 回执 → 发送方 READ",
            read,
            "readBy=${a.store.message(sent?.id.orEmpty())?.readBy}",
            now() - t0
        )

        // ---- 7. 1 MB 文件直传
        t0 = now()
        val big = makeRandomFile(File(root, "direct-1mb.bin"), 1024 * 1024, seed = 20261002)
        val bigSha = sha256Of(big)
        val media = ImportedMedia(
            filePath = big.absolutePath, thumbPath = null, thumbB64 = null,
            name = big.name, mime = "application/octet-stream", size = big.length(), sha256 = bigSha
        )
        val fileMsg = a.transfers.send(convAb.id, media, MsgKind.FILE)
        val transferId = fileMsg?.attachment?.transferId.orEmpty()
        val senderOutcome = awaitTerminal(a, transferId, 70_000)
        val receiverOutcome = awaitTerminal(b, transferId, 20_000)
        val received = b.store.message(fileMsg?.id.orEmpty())?.attachment?.localPath?.let { File(it) }
        val receivedSize = received?.length() ?: 0L
        val recvSha = received?.takeIf { it.exists() }?.let { sha256Of(it) }
        val directFileOk = senderOutcome?.state == TransferState.DONE &&
            receiverOutcome?.state == TransferState.DONE &&
            recvSha == bigSha && receivedSize == big.length()
        report.step(
            "1 MB 文件 A→B（sha256 校验）",
            directFileOk,
            "A端=${describe(senderOutcome)}  B端=${describe(receiverOutcome)}\n" +
                "        sha ${recvSha?.take(16)}… / ${bigSha.take(16)}…  size=$receivedSize\n" +
                "        B 事件: ${b.events.takeLast(2).joinToString(" | ")}",
            now() - t0
        )

        // ---- 8. 建群 + 邀请
        w.connAbB = connAbB
        w.connAcC = connAcC
        w.convAb = convAb
        w.convBa = convBa
        w.bigFileMsgId = fileMsg?.id.orEmpty()
    }

    /** 群聊：建群、群文本/群文件经群主中继、撤回与双方删除同步、语音自动接收。 */
    private suspend fun groupScenario(w: World) {
        val a = w.a
        val b = w.b
        val c = w.c
        val root = w.root
        val report = w.report
        val convAb = w.convAb
        val convBa = w.convBa
        var t0 = now()
        t0 = now()
        val group = a.router.createGroup(
            "自测群",
            7,
            listOf(Contact("st-B", "小八", avatarSeed = 34), Contact("st-C", "阿七", avatarSeed = 56))
        )
        val invited = await(8_000) {
            b.store.conversation(group?.id.orEmpty()) != null && c.store.conversation(group?.id.orEmpty()) != null
        }
        val membersOk = b.store.members(group?.id.orEmpty()).size == 3 &&
            b.store.conversation(group?.id.orEmpty())?.iAmOwner == false
        report.step(
            "GROUP_INVITE → B/C 建群",
            invited && membersOk,
            "groupId=${group?.id?.take(8)}, B 成员数=${b.store.members(group?.id.orEmpty()).size}, iAmOwner=${b.store.conversation(group?.id.orEmpty())?.iAmOwner}",
            now() - t0
        )
        if (group == null || !invited) return

        // ---- 9. 群文本（经群主中继）
        t0 = now()
        val groupText = "群消息：经群主中继 #${System.currentTimeMillis() % 1000}"
        b.router.sendText(group.id, groupText)
        val atC = await(10_000) { c.store.messages(group.id).any { it.text == groupText } }
        val atA = await(3_000) { a.store.messages(group.id).any { it.text == groupText } }
        delay(500) // 再等一会儿，确认没有重复投递
        val dupCount = c.store.messages(group.id).count { it.text == groupText }
        report.step(
            "群文本 B→（A 中继）→C",
            atC && atA && dupCount == 1,
            "C 收到=$atC，A 收到=$atA，C 侧重复数=$dupCount",
            now() - t0
        )

        // ---- 10. 群文件（分片经群主扇出）
        t0 = now()
        val groupBin = makeRandomFile(File(root, "group-256k.bin"), 256 * 1024, seed = 777)
        val groupSha = sha256Of(groupBin)
        val groupMedia = ImportedMedia(
            filePath = groupBin.absolutePath, thumbPath = null, thumbB64 = null,
            name = groupBin.name, mime = "application/octet-stream", size = groupBin.length(), sha256 = groupSha
        )
        val groupMsg = b.transfers.send(group.id, groupMedia, MsgKind.FILE)
        val groupTid = groupMsg?.attachment?.transferId.orEmpty()
        val groupSenderOutcome = awaitTerminal(b, groupTid, 70_000)
        val cOutcome = awaitTerminal(c, groupTid, 20_000)
        val aOutcome = awaitTerminal(a, groupTid, 20_000)
        val cFile = c.store.message(groupMsg?.id.orEmpty())?.attachment?.localPath?.let { File(it) }
        val aFile = a.store.message(groupMsg?.id.orEmpty())?.attachment?.localPath?.let { File(it) }
        val cSha = cFile?.takeIf { it.exists() }?.let { sha256Of(it) }
        val aSha = aFile?.takeIf { it.exists() }?.let { sha256Of(it) }
        report.step(
            "群文件 B→（A 中继扇出）→C",
            groupSenderOutcome?.state == TransferState.DONE &&
                cOutcome?.state == TransferState.DONE && aOutcome?.state == TransferState.DONE &&
                cSha == groupSha && aSha == groupSha,
            "B端=${describe(groupSenderOutcome)}  A端=${describe(aOutcome)}  C端=${describe(cOutcome)}\n" +
                "        C sha=${cSha?.take(12)}… A sha=${aSha?.take(12)}… 期望=${groupSha.take(12)}…\n" +
                "        C 事件: ${c.events.takeLast(2).joinToString(" | ")}",
            now() - t0
        )

        // ---- 10b. 撤回要同步到对端（真机 bug：以前只在本机生效）
        t0 = now()
        val recallText = "这条马上会被撤回 #${System.currentTimeMillis() % 1000}"
        val recallMsg = a.router.sendText(convAb.id, recallText)
        val arrived = await(5_000) { b.store.messages(convBa.id).any { it.text == recallText } }
        a.router.recall(recallMsg?.id.orEmpty())
        val recalledAtA = await(3_000) { a.store.message(recallMsg?.id.orEmpty())?.recalled == true }
        val recalledAtB = await(5_000) { b.store.message(recallMsg?.id.orEmpty())?.recalled == true }
        report.step(
            "撤回同步到对端（双方都变 [已撤回]）",
            arrived && recalledAtA && recalledAtB,
            "B 的该条 recalled=${b.store.message(recallMsg?.id.orEmpty())?.recalled}, " +
                "B 侧预览=${b.store.message(recallMsg?.id.orEmpty())?.preview}",
            now() - t0
        )

        // ---- 10c. 双方删除要同步到对端，并且**删掉对端的附件文件**
        t0 = now()
        val victimId = w.bigFileMsgId
        val victimPath = b.store.message(victimId)?.attachment?.localPath
        val existedBefore = victimPath != null && File(victimPath).exists()
        a.router.deleteForEveryone(victimId)
        val goneAtB = await(5_000) { b.store.message(victimId) == null }
        val fileGone = victimPath != null && !File(victimPath).exists()
        report.step(
            "双方删除同步到对端（消息 + 附件文件一起消失）",
            existedBefore && goneAtB && fileGone,
            "删除前文件存在=$existedBefore；对端消息已删=$goneAtB；对端文件已删=$fileGone",
            now() - t0
        )

        // ---- 10d. 媒体**强制自动接收**：语音与普通文件都直接开始，不再看开关
        t0 = now()
        b.settings.edit { it.copy(autoAcceptMedia = false) }
        val voiceFile = File(root, "voice-3s.m4a").apply {
            writeBytes(ByteArray(12 * 1024) { i -> ((i * 7) % 251).toByte() })
        }
        val voiceMedia = ImportedMedia(
            filePath = voiceFile.absolutePath, thumbPath = null, thumbB64 = null,
            name = voiceFile.name, mime = "audio/mp4", size = voiceFile.length(),
            durationMs = 3_000, sha256 = sha256Of(voiceFile)
        )
        val voiceMsg = a.transfers.send(convAb.id, voiceMedia, MsgKind.VOICE)
        val voiceTid = voiceMsg?.attachment?.transferId.orEmpty()
        val voiceOutcome = awaitTerminal(b, voiceTid, 20_000)
        val voiceAutoOk = voiceOutcome?.state == TransferState.DONE

        // 开关关着也照样自动下：图片/视频/文件的自动接收同样是强制的
        val binFile = File(root, "switch-check.bin").apply { writeBytes(ByteArray(8 * 1024) { 0x5A }) }
        val binMedia = ImportedMedia(
            filePath = binFile.absolutePath, thumbPath = null, thumbB64 = null,
            name = binFile.name, mime = "application/octet-stream", size = binFile.length(),
            sha256 = sha256Of(binFile)
        )
        val binMsg = a.transfers.send(convAb.id, binMedia, MsgKind.FILE)
        val binTid = binMsg?.attachment?.transferId.orEmpty()
        val binOutcome = awaitTerminal(b, binTid, 20_000)
        val binAutoOk = binOutcome?.state == TransferState.DONE
        b.settings.edit { it.copy(autoAcceptMedia = true) }
        report.step(
            "媒体强制自动接收（关掉开关也照下）",
            voiceAutoOk && binAutoOk,
            "autoAcceptMedia=false 时：语音=${describe(voiceOutcome)}、普通文件=${describe(binOutcome)}（都应 DONE）",
            now() - t0
        )

        // ---- 10e. 拉黑：对方发来的一律丢弃，并且回拒收回执
        w.group = group
    }

    /** 社交规则：拉黑拦截、删好友需重新认证、好友申请→同意、自动连接。 */
    private suspend fun socialScenario(w: World) {
        val a = w.a
        val b = w.b
        val scope = w.scope
        val report = w.report
        val convAb = w.convAb
        val convBa = w.convBa
        var t0 = now()
        t0 = now()
        b.router.blockList = { setOf("st-A") }
        val blockedText = "这条会被拉黑拦截 #${System.currentTimeMillis() % 1000}"
        val blockedMsg = a.router.sendText(convAb.id, blockedText)
        delay(700)
        val notStoredAtB = b.store.message(blockedMsg?.id.orEmpty()) == null &&
            b.store.messages(convBa.id).none { it.text == blockedText }
        val senderSawReject = await(3_000) { a.store.message(blockedMsg?.id.orEmpty())?.state == MsgState.FAILED }
        // 用户要求「我仍然能发给对方」：被拉黑的是**接收方向**，不影响我发出去的能力
        b.router.blockList = { emptySet() }
        val afterUnblock = a.router.sendText(convAb.id, "解除拉黑后的消息")
        val unblockedArrived = await(3_000) { b.store.message(afterUnblock?.id.orEmpty()) != null }
        report.step(
            "拉黑：对方消息被丢弃 + 发送方收到拒收回执",
            notStoredAtB && senderSawReject && unblockedArrived,
            "B 未入库=$notStoredAtB；A 侧状态=${a.store.message(blockedMsg?.id.orEmpty())?.state}（应 FAILED）；" +
                "解除后消息可达=$unblockedArrived",
            now() - t0
        )

        // ---- 10f. 删除好友后必须重新认证：消息变好友申请 → 同意 → 双方写入联系人
        t0 = now()
        a.store.deleteContact("st-B")
        b.store.deleteContact("st-A")
        val notFriends = !a.router.isFriend("st-B") && !b.router.isFriend("st-A")
        val requested = a.router.requestFriendship("st-B", "我是阿离，加个好友")
        val pendingAtB = await(3_000) { b.router.friendRequests.value.any { it.deviceId == "st-A" } }
        val reqShown = b.router.friendRequests.value.firstOrNull { it.deviceId == "st-A" }
        b.router.acceptFriend("st-A")
        val bothFriends = await(3_000) {
            a.store.contact("st-B") != null && b.store.contact("st-A") != null
        }
        val pendingCleared = b.router.friendRequests.value.none { it.deviceId == "st-A" }
        // 成为好友后消息恢复直达
        val afterFriend = a.router.sendText(convAb.id, "成为好友后的消息")
        val deliveredNow = await(3_000) { b.store.message(afterFriend?.id.orEmpty()) != null }
        report.step(
            "删好友 → 好友申请 → 同意 → 双方写入联系人",
            notFriends && requested && pendingAtB && bothFriends && pendingCleared && deliveredNow,
            "双方互为非好友=$notFriends；申请送达=$pendingAtB（附言=「${reqShown?.hello}」）；" +
                "同意后 A 有 B=${a.store.contact("st-B") != null}、B 有 A=${b.store.contact("st-A") != null}；" +
                "申请已清空=$pendingCleared；成为好友后消息直达=$deliveredNow",
            now() - t0
        )

        // ---- 10g. 非好友发消息不会进会话，只会变成好友申请
        t0 = now()
        b.store.deleteContact("st-A")
        val strangerText = "陌生人消息 #${System.currentTimeMillis() % 1000}"
        val strangerMsg = a.router.sendText(convAb.id, strangerText)
        delay(700)
        val becameRequest = b.router.friendRequests.value.any { it.deviceId == "st-A" }
        val noChatMessage = b.store.message(strangerMsg?.id.orEmpty()) == null &&
            b.store.messages(convBa.id).none { it.text == strangerText }
        b.router.acceptFriend("st-A")
        await(3_000) { b.store.contact("st-A") != null }
        report.step(
            "非好友的私聊消息只变成好友申请，不进会话",
            becameRequest && noChatMessage,
            "B 端出现申请=$becameRequest；B 未写成聊天消息=$noChatMessage",
            now() - t0
        )

        // ---- 10h. 自动连接：已配对好友出现时发起连接，带冷却与失败退避
        t0 = now()
        val dialed = ArrayList<String>()
        var alreadyUp = false
        var allowConnect = true
        val connector = AutoConnector(
            scope = scope,
            peers = { listOf(Peer(address = "AA:BB:CC:DD:EE:01", deviceId = "", name = "小八")) },
            isConnected = { alreadyUp },
            connect = { p -> dialed.add(p.address); allowConnect },
            busy = { false },
            intervalMs = { 15_000L }
        )
        val first = connector.tickOnce(now = 1_000_000L)          // 到期 → 连接
        val cooling = connector.tickOnce(now = 1_005_000L)        // 冷却中 → 不连
        alreadyUp = true
        val connectedSkip = connector.tickOnce(now = 1_100_000L)  // 已连上 → 不连
        alreadyUp = false
        allowConnect = false
        connector.tickOnce(now = 1_200_000L)                      // 拨号失败 → 记退避
        val dialsAfterFail = dialed.size
        val backoffBlocks = connector.tickOnce(now = 1_205_000L)  // 退避期内 → 不重试
        val dialsStillBackoff = dialed.size
        connector.tickOnce(now = 1_216_000L)                      // 退避到期 → 再试一次
        val dialsAfterRetry = dialed.size
        connector.stop()
        report.step(
            "自动连接：好友设备出现即连，冷却/退避/已连不重复",
            first == 1 && cooling == 0 && connectedSkip == 0 &&
                backoffBlocks == 0 && dialsAfterFail == 2 &&
                dialsStillBackoff == 2 && dialsAfterRetry == 3,
            "首轮发起=$first，冷却期内=$cooling，已连接=$connectedSkip，退避期内=$backoffBlocks；" +
                "失败后拨号累计=$dialsAfterFail，退避中仍=$dialsStillBackoff，退避到期后=$dialsAfterRetry",
            now() - t0
        )

        // ---- 11. 一对一语音通话：发起 → 振铃 → 接听 → 双向 50 帧 → 静音 → 挂断
    }

    /** 通话：一对一全流程、通话记录、群语音经群主扇出、写通道白盒用例、诊断区。 */
    private suspend fun callScenario(w: World) {
        val a = w.a
        val b = w.b
        val c = w.c
        val scope = w.scope
        val report = w.report
        val convAb = w.convAb
        val convBa = w.convBa
        val group = w.group
        var t0 = now()
        t0 = now()
        val callStarted = a.calls.startCall(convAb.id, video = false)
        val ringingAtB = await(5_000) { b.callState.value.phase == CallState.Phase.RINGING }
        val inviteOk = callStarted && ringingAtB &&
            a.callState.value.phase == CallState.Phase.OUTGOING &&
            b.callState.value.peerId == "st-A" &&
            b.callState.value.callId == a.callState.value.callId
        report.step(
            "通话信令：发起 → 对端振铃",
            inviteOk,
            "A=${a.callState.value.phase}, B=${b.callState.value.phase}, B 看到呼叫者=${b.callState.value.peerName}, " +
                "callId 一致=${b.callState.value.callId == a.callState.value.callId}, mediaReady=${a.callState.value.mediaReady}",
            now() - t0
        )
        if (!inviteOk) return

        t0 = now()
        b.calls.accept()
        val bothActive = await(5_000) {
            a.callState.value.phase == CallState.Phase.ACTIVE && b.callState.value.phase == CallState.Phase.ACTIVE
        }
        report.step(
            "接听 → 双方 ACTIVE 且媒体层启动",
            bothActive && a.callMedia.started && b.callMedia.started,
            "A=${a.callState.value.phase}, B=${b.callState.value.phase}, media.start A/B=${a.callMedia.started}/${b.callMedia.started}",
            now() - t0
        )

        t0 = now()
        val emitA = scope.launch { a.callMedia.emitFrames(50, gapMs = 5) }
        val emitB = scope.launch { b.callMedia.emitFrames(50, gapMs = 5) }
        emitA.join()
        emitB.join()
        val framesArrived = await(5_000) { a.calls.framesReceived >= 50 && b.calls.framesReceived >= 50 }
        // 媒体层复用同一块采集缓冲：如果控制器没有拷贝，收到的内容会错位
        val audioOk = framesArrived &&
            a.callMedia.remoteFrames == 50 && b.callMedia.remoteFrames == 50 &&
            a.callMedia.contentMismatch == 0 && b.callMedia.contentMismatch == 0
        report.step(
            "双向各 50 帧音频（顺带验证采集缓冲复用安全）",
            audioOk,
            "A 发/收=${a.calls.framesSent}/${a.calls.framesReceived}, B 发/收=${b.calls.framesSent}/${b.calls.framesReceived}; " +
                "媒体层收到 A=${a.callMedia.remoteFrames} B=${b.callMedia.remoteFrames}; 内容错位=${a.callMedia.contentMismatch + b.callMedia.contentMismatch}; " +
                "高优先队列丢弃=${a.conns.sumOf { it.callFramesDropped }}/${b.conns.sumOf { it.callFramesDropped }}",
            now() - t0
        )

        t0 = now()
        a.calls.setMuted(true)
        val muteSignaled = await(5_000) { b.callState.value.mutedPeers.contains("st-A") }
        val framesBeforeMute = b.calls.framesReceived
        a.callMedia.emitFrames(5, gapMs = 5) // 静音后媒体层按约定不再出帧
        delay(300)
        val muteOk = muteSignaled && a.callMedia.muted == true &&
            b.calls.framesReceived == framesBeforeMute
        report.step(
            "静音：本地停发 + 对端看到已静音",
            muteOk,
            "A.muted=${a.callState.value.muted}, B 的 mutedPeers=${b.callState.value.mutedPeers}, 静音后新增帧=${b.calls.framesReceived - framesBeforeMute}",
            now() - t0
        )

        t0 = now()
        a.calls.hangUp()
        val callEnded = await(5_000) {
            a.callState.value.phase == CallState.Phase.ENDED && b.callState.value.phase == CallState.Phase.ENDED
        }
        report.step(
            "挂断 → 双方 ENDED 且媒体停止",
            callEnded && a.callMedia.stopped && b.callMedia.stopped,
            "A=${a.callState.value.phase}(${a.callState.value.error ?: "-"}), B=${b.callState.value.phase}(${b.callState.value.error ?: "-"}), " +
                "media.stop A/B=${a.callMedia.stopped}/${b.callMedia.stopped}",
            now() - t0
        )

        // ---- 11b. 通话记录：双方各自留一条 SYSTEM 记录
        t0 = now()
        val aRecord = a.store.messages(convAb.id).lastOrNull { it.kind == MsgKind.SYSTEM && it.text.contains("通话") }
        val bRecord = b.store.messages(convBa.id).lastOrNull { it.kind == MsgKind.SYSTEM && it.text.contains("通话") }
        val recordShape = Regex("^(语音|视频)通话(（多人）)? (\\d{2}:\\d{2}|未接通|已拒绝|已取消)$")
        val recordOk = aRecord != null && bRecord != null &&
            recordShape.matches(aRecord.text) && recordShape.matches(bRecord.text)
        report.step(
            "通话记录：双方各写一条 SYSTEM 消息",
            recordOk,
            "A 侧=「${aRecord?.text}」，B 侧=「${bRecord?.text}」",
            now() - t0
        )
        a.calls.reset()
        b.calls.reset()

        // ---- 12. 群语音：B 发起 → 群主 A 扇出邀请 → C 接听 → 音频经 A 中转
        t0 = now()
        val groupCallStarted = b.calls.startCall(group.id, video = false)
        val ringAtA = await(5_000) { a.callState.value.phase == CallState.Phase.RINGING }
        val ringAtC = await(5_000) { c.callState.value.phase == CallState.Phase.RINGING }
        val groupRings = groupCallStarted && ringAtA && ringAtC
        report.step(
            "群语音：群主与其他成员同时振铃",
            groupRings,
            "startCall=$groupCallStarted, A=${a.callState.value.phase}(${a.callState.value.error ?: "-"}), " +
                "C=${c.callState.value.phase}(${c.callState.value.error ?: "-"}), callId 一致=${a.callState.value.callId == c.callState.value.callId}, " +
                "呼叫者显示=${c.callState.value.peerName}\n" +
                "        A 事件: ${a.events.takeLast(3).joinToString(" | ")}",
            now() - t0
        )
        if (!groupRings) return

        t0 = now()
        c.calls.accept()
        val cActive = await(5_000) { c.callState.value.phase == CallState.Phase.ACTIVE }
        val bActive = await(5_000) { b.callState.value.phase == CallState.Phase.ACTIVE }
        report.step(
            "成员接听 → 呼叫方 ACTIVE，群主未接听仍只当中继",
            cActive && bActive && a.callState.value.phase == CallState.Phase.RINGING,
            "B=${b.callState.value.phase}, C=${c.callState.value.phase}, A=${a.callState.value.phase}",
            now() - t0
        )

        // 计数器是累计值（上一通电话也在里面），这里取基线算增量
        val aRecvBase = a.calls.framesReceived
        val aFwdBase = a.calls.framesForwarded
        val aMediaBase = a.callMedia.remoteFrames
        val cRecvBase = c.calls.framesReceived
        val cMediaBase = c.callMedia.remoteFrames

        t0 = now()
        b.callMedia.emitFrames(20, gapMs = 5)
        val relayed = await(5_000) { c.calls.framesReceived - cRecvBase >= 20 && a.calls.framesForwarded - aFwdBase >= 20 }
        val relayOk = relayed &&
            c.callMedia.remoteFrames - cMediaBase == 20 &&
            a.calls.framesReceived - aRecvBase >= 20 &&
            // 群主自己还没接听，只当中继、不播放
            a.callMedia.remoteFrames - aMediaBase == 0
        report.step(
            "音频由群主扇出：B → A（中继）→ C",
            relayOk,
            "A 收=+${a.calls.framesReceived - aRecvBase} 转发=+${a.calls.framesForwarded - aFwdBase}, " +
                "C 收=+${c.calls.framesReceived - cRecvBase} 媒体层=+${c.callMedia.remoteFrames - cMediaBase}, " +
                "A 未接听未播放=+${a.callMedia.remoteFrames - aMediaBase}",
            now() - t0
        )

        t0 = now()
        val bRecvBase = b.calls.framesReceived
        val cRecvBase2 = c.calls.framesReceived
        a.calls.accept()
        val aActive = await(5_000) { a.callState.value.phase == CallState.Phase.ACTIVE }
        a.callMedia.emitFrames(10, gapMs = 5)
        val upAndDown = await(5_000) {
            b.calls.framesReceived - bRecvBase >= 10 && c.calls.framesReceived - cRecvBase2 >= 10
        }
        report.step(
            "群主加入后双向音频（A 直发所有成员）",
            aActive && upAndDown,
            "A=${a.callState.value.phase}; B 收=+${b.calls.framesReceived - bRecvBase}, C 收=+${c.calls.framesReceived - cRecvBase2}",
            now() - t0
        )

        t0 = now()
        b.calls.hangUp()
        delay(600)
        val phaseAfterB = "${a.callState.value.phase}/${c.callState.value.phase}"
        val othersStay = a.callState.value.phase == CallState.Phase.ACTIVE &&
            c.callState.value.phase == CallState.Phase.ACTIVE
        a.calls.hangUp()
        val allEnded = await(5_000) {
            a.callState.value.phase == CallState.Phase.ENDED && c.callState.value.phase == CallState.Phase.ENDED
        }
        report.step(
            "群通话：一人挂断只是退出，其他人继续",
            othersStay && allEnded,
            "B 退出后 A/C=$phaseAfterB（应仍 ACTIVE）；群主挂断后全部结束=$allEnded",
            now() - t0
        )
        a.calls.reset()
        b.calls.reset()
        c.calls.reset()

        // ---- 13. 高优先写通道：通话帧抢占 + 队列满丢旧保新
        priorityChannelTest(scope, report)

        // ---- 诊断区：真机调优要看的就是这几个数（不计入通过/失败）
        report.note("通话链路（高优先队列是语音的唯一出口，丢弃数=真机上语音被挤掉的帧）")
        for (node in listOf(a, b, c)) {
            for (conn in node.conns) {
                report.note(
                    "  ${node.tag}→${conn.remoteId.ifBlank { conn.duplex.label }}: " +
                        "rtt=${conn.rttMs.value}ms, 排队=${conn.pendingCallFrames}, " +
                        "累计丢弃=${conn.callFramesDropped}, 普通队列积压=${conn.pendingWrites}"
                )
            }
        }
        report.note(
            "  A  帧 发/收/转发=${a.calls.framesSent}/${a.calls.framesReceived}/${a.calls.framesForwarded}, " +
                "mediaReady=${a.callState.value.mediaReady}, mediaError=${a.callState.value.mediaError ?: "无"}"
        )
        report.note(
            "  B  帧 发/收/转发=${b.calls.framesSent}/${b.calls.framesReceived}/${b.calls.framesForwarded}, " +
                "mediaReady=${b.callState.value.mediaReady}, mediaError=${b.callState.value.mediaError ?: "无"}"
        )
        report.note(
            "  C  帧 发/收/转发=${c.calls.framesSent}/${c.calls.framesReceived}/${c.calls.framesForwarded}, " +
                "mediaReady=${c.callState.value.mediaReady}, mediaError=${c.callState.value.mediaError ?: "无"}"
        )

        // ---- 14. 断线后发送 → FAILED（可重发）
    }

    /** 收尾：断开全部链路，验证对端感知 EOF、无链路发送落库为 FAILED。 */
    private suspend fun disconnectScenario(w: World) {
        val a = w.a
        val b = w.b
        val c = w.c
        val report = w.report
        val connAbB = w.connAbB
        val connAcC = w.connAcC
        val convBa = w.convBa
        var t0 = now()
        t0 = now()
        a.router.closeAll("自测：主动断开")
        val closed = await(8_000) {
            connAbB.isClosed && connAcC.isClosed && b.router.readyCount == 0 && c.router.readyCount == 0
        }
        report.step("断开链路（对端感知 EOF）", closed, "B 链路数=${b.router.readyCount}, C 链路数=${c.router.readyCount}", now() - t0)

        t0 = now()
        val failMsg = b.router.sendText(convBa.id, "断线后的消息")
        val failed = await(3_000) { b.store.message(failMsg?.id.orEmpty())?.state == MsgState.FAILED }
        report.step(
            "无链路发送 → FAILED（可 resend）",
            failed,
            "状态=${b.store.message(failMsg?.id.orEmpty())?.state}",
            now() - t0
        )
    }

    // ------------------------------------------------------------- 工具类

    private class Report {
        private val rows = ArrayList<String>()
        private val notes = ArrayList<String>()
        var pass = 0
            private set
        var fail = 0
            private set

        val total: Int get() = pass + fail

        fun step(name: String, ok: Boolean, detail: String, ms: Long = 0) {
            if (ok) pass++ else fail++
            rows.add(
                (if (ok) "[通过] " else "[失败] ") + name +
                    (if (detail.isBlank()) "" else "\n        " + detail) +
                    (if (ms > 0) "   (${ms}ms)" else "")
            )
        }

        /** 附加诊断行，不计入通过/失败（真机调优用）。 */
        fun note(line: String) {
            notes.add(line)
        }

        fun render(header: String): String = buildString {
            appendLine(header)
            appendLine("=".repeat(60))
            rows.forEach { appendLine(it) }
            if (notes.isNotEmpty()) {
                appendLine("-".repeat(60))
                appendLine("诊断信息（不计入通过/失败）")
                notes.forEach { appendLine(it) }
            }
            appendLine("=".repeat(60))
            appendLine(
                if (fail == 0) "结果: 全部通过 ($pass/$total)  SELFTEST OK"
                else "结果: $fail 项失败，$pass 项通过（共 $total 项）  SELFTEST FAILED"
            )
        }
    }

    /** 一个自测节点：内存 Store + 内存 Settings + 文件 Vault + Router + TransferManager。 */
    private class Node(
        val tag: String,
        val deviceId: String,
        val name: String,
        val seed: Int,
        root: File,
        private val scope: CoroutineScope,
        autoAccept: Boolean
    ) {
        val store = MemStore()
        val settings = MemSettings(
            Prefs(myDeviceId = deviceId, myName = name, myAvatarSeed = seed, autoAcceptMedia = autoAccept)
        )
        val vault = FileVault(File(root, tag))
        val typing = MutableStateFlow<Set<String>>(emptySet())
        val transferFlow = MutableStateFlow<List<TransferProgress>>(emptyList())
        val router = Router(store, settings, vault, scope, typing)
        val transfers = TransferManager(store, settings, vault, router, scope, transferFlow)
        val callState = MutableStateFlow(CallState())
        val calls = CallController(store, settings, router, scope, callState)

        /** 离心中转：本节点替别人保管的信封都在这里。 */
        val relay = com.liuli.btchat.bt.relay.RelayService(router, settings, scope)

        /** 假媒体层：不碰麦克风/摄像头，只按接口约定收发帧。 */
        val callMedia = TestCallMedia()
        val conns = CopyOnWriteArrayList<Connection>()

        /** Router/TransferManager 抛上来的事件，失败时写进报告便于定位。 */
        val events = CopyOnWriteArrayList<String>()

        init {
            router.transfers = transfers
            router.relay = relay
            router.onNotice = { msg -> events.add(msg) }
            calls.media = callMedia
        }

        /** 把另一个节点写进本机联系人表（= 互加好友）。 */
        fun befriend(other: Node) {
            store.saveContact(
                Contact(
                    deviceId = other.deviceId,
                    name = other.name,
                    avatarSeed = other.seed,
                    address = "",
                    lastSeen = System.currentTimeMillis()
                )
            )
        }

        fun attach(duplex: Duplex, secure: Boolean = true): Connection {
            val conn = Connection(
                duplex = duplex,
                helloFactory = {
                    HelloPacket(deviceId, name, seed, Wire.PROTO_VERSION, "selftest", "自测中")
                },
                sink = router,
                scope = scope,
                address = "",
                secure = secure,
                incoming = false
            )
            conns.add(conn)
            router.register(conn)
            conn.start()
            return conn
        }
    }

    /** 内存版 [ChatStore]，行为与 `data/Store` 对齐（unread / lastPreview / revision）。 */
    private class MemStore : ChatStore {
        private val rev = MutableStateFlow(0L)
        override val revision: StateFlow<Long> = rev
        private val convs = LinkedHashMap<String, Conversation>()
        private val msgs = LinkedHashMap<String, Message>()
        private val contacts = LinkedHashMap<String, Contact>()
        private val members = LinkedHashMap<String, MutableList<Member>>()

        private fun bump() {
            rev.value += 1
        }

        override fun conversations(): List<Conversation> =
            convs.values.sortedWith(compareByDescending<Conversation> { it.pinned }.thenByDescending { it.lastMessageAt })

        override fun conversation(id: String): Conversation? = convs[id]
        override fun saveConversation(c: Conversation) {
            convs[c.id] = c
            bump()
        }

        override fun deleteConversation(id: String) {
            convs.remove(id)
            msgs.values.removeAll { it.convId == id }
            bump()
        }

        override fun clearHistory(convId: String) {
            msgs.values.removeAll { it.convId == convId }
            convs[convId]?.let { convs[convId] = it.copy(lastPreview = "", unread = 0) }
            bump()
        }

        override fun setDraft(convId: String, draft: String) {
            convs[convId]?.let { convs[convId] = it.copy(draft = draft) }
            bump()
        }

        override fun totalUnread(): Int = convs.values.sumOf { it.unread }

        override fun messages(convId: String, limit: Int): List<Message> =
            msgs.values.filter { it.convId == convId }.sortedBy { it.sentAt }.takeLast(limit)

        override fun latestMessage(convId: String): Message? =
            msgs.values.filter { it.convId == convId }.maxByOrNull { it.sentAt }

        override fun message(id: String): Message? = msgs[id]

        override fun saveMessage(m: Message) {
            msgs[m.id] = m
            convs[m.convId]?.let { c ->
                convs[m.convId] = c.copy(
                    lastMessageAt = maxOf(c.lastMessageAt, m.sentAt),
                    lastPreview = m.preview,
                    unread = if (!m.outgoing && m.kind != MsgKind.SYSTEM) c.unread + 1 else c.unread
                )
            }
            bump()
        }

        override fun deleteMessage(id: String) {
            msgs.remove(id)
            bump()
        }

        override fun setMessageState(id: String, state: MsgState) {
            msgs[id]?.let { msgs[id] = it.copy(state = state) }
            bump()
        }

        override fun setMessageProgress(
            id: String,
            transferred: Long,
            progress: Float,
            state: TransferState,
            localPath: String?,
            thumbPath: String?
        ) {
            msgs[id]?.let { m ->
                val att = m.attachment ?: return@let
                msgs[id] = m.copy(
                    attachment = att.copy(
                        transferred = transferred,
                        progress = progress,
                        state = state,
                        localPath = localPath ?: att.localPath,
                        thumbPath = thumbPath ?: att.thumbPath
                    )
                )
            }
            bump()
        }

        override fun markConversationRead(convId: String) {
            convs[convId]?.let { convs[convId] = it.copy(unread = 0) }
            bump()
        }

        override fun searchMessages(query: String, limit: Int): List<Message> =
            msgs.values.filter { it.text.contains(query, ignoreCase = true) }.sortedByDescending { it.sentAt }.take(limit)

        override fun recallMessage(id: String) {
            msgs[id]?.let { m ->
                msgs[id] = m.copy(recalled = true, text = "", attachment = null, quote = null)
            }
            bump()
        }

        override fun setStarred(id: String, starred: Boolean) {
            msgs[id]?.let { msgs[id] = it.copy(starred = starred) }
            bump()
        }

        override fun starredMessages(limit: Int): List<Message> =
            msgs.values.filter { it.starred }.sortedByDescending { it.sentAt }.take(limit)

        override fun starredCount(): Int = msgs.values.count { it.starred }

        override fun contacts(): List<Contact> = contacts.values.sortedBy { it.display }
        override fun contact(deviceId: String): Contact? = contacts[deviceId]
        override fun saveContact(c: Contact) {
            contacts[c.deviceId] = c
            bump()
        }

        override fun deleteContact(deviceId: String) {
            contacts.remove(deviceId)
            bump()
        }

        override fun members(groupId: String): List<Member> = members[groupId].orEmpty()
        override fun setMembers(groupId: String, members: List<Member>) {
            this.members[groupId] = members.toMutableList()
            convs[groupId]?.let { convs[groupId] = it.copy(memberCount = members.size) }
            bump()
        }

        override fun addMember(groupId: String, m: Member) {
            val list = members.getOrPut(groupId) { mutableListOf() }
            list.removeAll { it.deviceId == m.deviceId }
            list.add(m)
            convs[groupId]?.let { convs[groupId] = it.copy(memberCount = list.size) }
            bump()
        }

        override fun removeMember(groupId: String, memberId: String) {
            members[groupId]?.removeAll { it.deviceId == memberId }
            convs[groupId]?.let { convs[groupId] = it.copy(memberCount = members[groupId]?.size ?: 0) }
            bump()
        }

        override fun groupIds(): List<String> = convs.values.filter { it.kind == ConvKind.GROUP }.map { it.id }
    }

    private class MemSettings(initial: Prefs) : SettingsApi {
        private val state = MutableStateFlow(initial)
        override val flow: StateFlow<Prefs> = state
        override fun current(): Prefs = state.value
        override fun edit(block: (Prefs) -> Prefs) {
            state.value = block(state.value)
        }

        override fun ensureIdentity(): Prefs {
            val cur = state.value
            if (cur.myDeviceId.isNotBlank() && cur.myName.isNotBlank()) return cur
            val next = cur.copy(
                myDeviceId = cur.myDeviceId.ifBlank { newId() },
                myName = cur.myName.ifBlank { "我" }
            )
            state.value = next
            return next
        }
    }

    /** 纯文件版 [MediaVault]：不依赖 Android，自测可以在 JVM 上跑。 */
    private class FileVault(private val root: File) : MediaVault {
        init {
            root.mkdirs()
        }

        override suspend fun importImage(uri: Uri): ImportedMedia? = null
        override suspend fun importVideo(uri: Uri): ImportedMedia? = null
        override suspend fun importAny(uri: Uri): ImportedMedia? = null
        override suspend fun thumbnail(att: Attachment): File? =
            att.thumbPath?.let { File(it) }?.takeIf { it.exists() }

        override fun fileFor(att: Attachment): File? = att.localPath?.let { File(it) }?.takeIf { it.exists() }

        override fun storeIncomingThumb(transferId: String, b64: String?): String? {
            if (b64.isNullOrBlank()) return null
            return runCatching {
                val f = File(root, "$transferId.thumb")
                f.writeBytes(java.util.Base64.getDecoder().decode(b64))
                f.absolutePath
            }.getOrNull()
        }

        override fun newIncomingFile(transferId: String, fileName: String): File {
            val safe = fileName.replace(Regex("[^A-Za-z0-9._-]"), "_").takeLast(60)
            return File(root, "${transferId}__$safe")
        }

        override fun exportToGallery(file: File, mime: String): Boolean = true

        override fun shareUri(file: File): Uri = Uri.EMPTY

        override fun deleteTransferFiles(transferId: String) {
            root.listFiles { f -> f.name.startsWith("${transferId}__") }?.forEach { it.delete() }
            File(root, "$transferId.thumb").delete()
        }

        override fun deleteAttachmentFiles(att: Attachment) {
            runCatching { att.localPath?.let { File(it).delete() } }
            runCatching { att.thumbPath?.let { File(it).delete() } }
            runCatching { File(root, "${att.transferId}.thumb").delete() }
            deleteTransferFiles(att.transferId)
        }
    }

    // ------------------------------------------------------------- 小工具

    /**
     * 假通话媒体层：不碰麦克风/摄像头，只按 [CallMedia] 约定收发。
     *
     * 刻意**复用同一块采集缓冲**并把帧序号写进缓冲内容 —— 如果控制器忘了拷贝，
     * 对端收到的内容就会错位（[contentMismatch] 会涨），这条自测就能抓到。
     */
    private class TestCallMedia : CallMedia {
        override var onFrame: ((kind: Int, data: ByteArray, len: Int) -> Unit)? = null

        @Volatile
        var started = false
            private set

        @Volatile
        var stopped = false
            private set

        @Volatile
        var muted: Boolean? = null
            private set

        @Volatile
        var cameraOn: Boolean? = null
            private set

        @Volatile
        var switchCount = 0
            private set

        @Volatile
        var remoteFrames = 0
            private set

        @Volatile
        var remoteBytes = 0L
            private set

        /** 收到的帧内容与预期序号不符的次数（>0 说明发送侧没做缓冲拷贝）。 */
        @Volatile
        var contentMismatch = 0
            private set

        private var expectedByte = -1

        override val lastError: String? = null

        @Volatile
        override var active: Boolean = false

        override fun start(callId: String, video: Boolean) {
            started = true
            stopped = false
            active = true
            expectedByte = -1
        }

        override fun stop() {
            stopped = true
            active = false
            onFrame = null
        }

        override fun setMuted(muted: Boolean) {
            this.muted = muted
        }

        override fun setCamera(on: Boolean) {
            cameraOn = on
        }

        override fun switchCamera() {
            switchCount++
        }

        override fun onRemoteFrame(kind: Int, data: ByteArray, offset: Int, len: Int) {
            remoteFrames++
            remoteBytes += len
            if (len > 0) {
                val v = data[offset].toInt() and 0xFF
                if (expectedByte >= 0 && v != expectedByte) contentMismatch++
                expectedByte = (v + 1) and 0xFF
            }
        }

        /**
         * 模拟采集：连发 [n] 帧，每帧间隔 [gapMs]。
         * 静音后实现方按约定停发，这里如实模拟。
         */
        suspend fun emitFrames(n: Int, gapMs: Long = 5L) {
            if (muted == true) return
            val cb = onFrame ?: return
            val buf = ByteArray(320)
            for (i in 1..n) {
                java.util.Arrays.fill(buf, (i and 0xFF).toByte())
                cb(Wire.CALL_AUDIO, buf, buf.size)
                if (gapMs > 0) delay(gapMs)
            }
        }
    }

    /**
     * 可控闸门 [Duplex]：第一次写出时会卡住，直到 [release]。
     *
     * 用来白盒验证写队列的优先级：写线程会停在「已取走一帧但还没写完」的状态，
     * 此时再投递通话帧，就能确定地观察到它插到了后面所有普通任务之前。
     * 输入侧是永不产数据的管道，读线程会安静地阻塞着（不干扰写侧）。
     */
    private class GatedDuplex(override val label: String) : Duplex {
        private val gate = CountDownLatch(1)
        private val captured = ByteArrayOutputStream()
        private val writtenBytes = AtomicInteger(0)
        private val pipeOut = PipedOutputStream()

        override val input: InputStream = PipedInputStream(pipeOut, 64 * 1024)

        override val output: OutputStream = object : OutputStream() {
            override fun write(b: Int) {
                gate.await()
                synchronized(captured) { captured.write(b) }
                writtenBytes.incrementAndGet()
            }

            override fun write(b: ByteArray, off: Int, len: Int) {
                gate.await()
                synchronized(captured) { captured.write(b, off, len) }
                writtenBytes.addAndGet(len)
            }

            override fun flush() = Unit
        }

        fun release() = gate.countDown()

        val written: Int get() = writtenBytes.get()

        fun snapshot(): ByteArray = synchronized(captured) { captured.toByteArray() }

        override fun close() {
            release()
            runCatching { pipeOut.close() }
            runCatching { input.close() }
        }
    }

    /**
     * 写通道白盒用例：
     * 1. 普通队列里压 200 个任务时，通话帧仍然抢先写出去；
     * 2. 高优先队列超过 [BtConstants.CALL_QUEUE_CAPACITY] 时丢**最旧**的，保最新的。
     */
    private suspend fun priorityChannelTest(scope: CoroutineScope, report: Report) {
        val t0 = now()
        val noop = object : Connection.Sink {
            override fun onEnvelope(conn: Connection, env: Envelope) = Unit
            override fun onChunk(conn: Connection, chunk: Chunk) = Unit
            override fun onCallFrame(conn: Connection, frame: CallFrame) = Unit
            override fun onReady(conn: Connection) = Unit
            override fun onClosed(conn: Connection, reason: String) = Unit
        }
        val normalMarker = ByteArray(8) { 0xCD.toByte() }
        val callMarker = ByteArray(8) { 0xAB.toByte() }

        // --- 1. 抢占
        val gated = GatedDuplex("priority-preempt")
        val conn = Connection(
            duplex = gated,
            helloFactory = { HelloPacket("st-P1", "P1", 1, Wire.PROTO_VERSION, "selftest", "") },
            sink = noop,
            scope = scope,
            address = "",
            secure = true,
            incoming = false
        )
        conn.start()
        delay(200) // 让写线程取走 HELLO 并卡在闸门上
        repeat(200) {
            conn.tryEnqueue(Connection.WriteTask("normal") { out ->
                out.write(normalMarker, 0, normalMarker.size)
                out.flush()
            })
        }
        val callQueued = conn.tryEnqueueCall { out ->
            out.write(callMarker, 0, callMarker.size)
            out.flush()
        }
        gated.release()
        await(5_000) { conn.pendingWrites == 0 && conn.pendingCallFrames == 0 }
        delay(50)
        val bytes = gated.snapshot()
        val callAt = firstRun(bytes, 0xAB.toByte(), 4)
        val normalAt = firstRun(bytes, 0xCD.toByte(), 4)
        val preemptOk = callQueued && callAt >= 0 && normalAt > callAt
        conn.close("priority test done")

        // --- 2. 丢旧保新
        val gated2 = GatedDuplex("priority-drop")
        val conn2 = Connection(
            duplex = gated2,
            helloFactory = { HelloPacket("st-P2", "P2", 2, Wire.PROTO_VERSION, "selftest", "") },
            sink = noop,
            scope = scope,
            address = "",
            secure = true,
            incoming = false
        )
        conn2.start()
        delay(200)
        repeat(40) { i ->
            val index = (i + 1).toByte()
            conn2.tryEnqueueCall { out ->
                out.write(callMarker, 0, callMarker.size)
                out.write(index.toInt())
                out.flush()
            }
        }
        val pendingBefore = conn2.pendingCallFrames
        val dropsBefore = conn2.callFramesDropped
        gated2.release()
        await(5_000) { conn2.pendingCallFrames == 0 }
        delay(50)
        val kept = extractedCallIndexes(gated2.snapshot(), callMarker[0])
        val dropOk = pendingBefore == BtConstants.CALL_QUEUE_CAPACITY &&
            dropsBefore == 8L &&
            kept.size == BtConstants.CALL_QUEUE_CAPACITY &&
            kept.first() == 9 && kept.last() == 40
        conn2.close("drop test done")

        report.step(
            "通话帧抢占普通写队列 + 队列满丢旧保新",
            preemptOk && dropOk,
            "抢占：通话帧位置=$callAt 早于普通帧位置=$normalAt（此前已排 200 个普通任务）\n" +
                "        丢弃：drop=$dropsBefore，队列长度=$pendingBefore，保留序号=${kept.firstOrNull()}..${kept.lastOrNull()}",
            now() - t0
        )
    }

    /** 第一段连续 [run] 个 [marker] 字节的位置。 */
    private fun firstRun(bytes: ByteArray, marker: Byte, run: Int): Int {
        var count = 0
        for (i in bytes.indices) {
            if (bytes[i] == marker) {
                count++
                if (count >= run) return i - run + 1
            } else {
                count = 0
            }
        }
        return -1
    }

    /** 取出每个「marker×8 + 序号」记录里的序号。 */
    private fun extractedCallIndexes(bytes: ByteArray, marker: Byte): List<Int> {
        val out = ArrayList<Int>()
        var i = 0
        while (i < bytes.size) {
            if (bytes[i] == marker) {
                var run = 0
                while (i + run < bytes.size && bytes[i + run] == marker) run++
                if (run >= 8) {
                    val idxPos = i + run
                    if (idxPos < bytes.size) out.add(bytes[idxPos].toInt() and 0xFF)
                    i = idxPos + 1
                    continue
                }
            }
            i++
        }
        return out
    }

    /**
     * Two ends of one full-duplex link, simulating the two endpoints of an
     * RFCOMM connection.
     *
     * This used to open a loopback TCP socket pair. That works on the desktop
     * JVM harness and **fails on a real phone**: Android requires the
     * `INTERNET` permission for any socket, even one bound to `127.0.0.1`, and
     * 琉璃 deliberately does not declare it — the whole point of the app is
     * that nothing goes over a network. An in-process pipe exercises exactly
     * the same `Connection` / `Router` / `TransferManager` code with no
     * permission and no sockets.
     */
    private suspend fun openPair(tag: String): Pair<Duplex, Duplex>? = withContext(Dispatchers.IO) {
        runCatching {
            MemoryDuplex.pair("loop-$tag-left", "loop-$tag-right")
        }.getOrNull()
    }

    private fun makeRandomFile(file: File, size: Int, seed: Long): File {
        val bytes = ByteArray(size)
        Random(seed).nextBytes(bytes)
        file.writeBytes(bytes)
        return file
    }

    private fun sha256Of(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private suspend fun await(timeoutMs: Long, block: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (block()) return true
            delay(20)
        }
        return block()
    }

    /** 等到某条传输进入终态（DONE/FAILED/CANCELLED/REJECTED），返回它的最终进度快照。 */
    private suspend fun awaitTerminal(node: Node, transferId: String, timeoutMs: Long): TransferProgress? {
        await(timeoutMs) {
            node.transferFlow.value.any { it.transferId == transferId && it.state.isTerminal() }
        }
        return node.transferFlow.value.firstOrNull { it.transferId == transferId }
    }

    private fun TransferState.isTerminal(): Boolean =
        this == TransferState.DONE || this == TransferState.FAILED ||
            this == TransferState.CANCELLED || this == TransferState.REJECTED

    private fun describe(p: TransferProgress?): String =
        if (p == null) "无记录" else "${p.state}${p.error?.let { "($it)" } ?: ""} ${p.done}/${p.total}"

    private fun now(): Long = System.currentTimeMillis()
}
