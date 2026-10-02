package com.liuli.btchat.bt

import java.util.UUID

/**
 * 蓝牙传输层的全部常量。
 *
 * 「琉璃」同时注册两条 RFCOMM 服务记录：
 *
 * * [UUID_SECURE] 走 `listenUsingRfcommWithServiceRecord`，链路需要系统配对
 *   （认证 + 加密），两台已配对的手机会优先用它；
 * * [UUID_INSECURE] 走 `listenUsingInsecureRfcommWithServiceRecord`，不弹配对框，
 *   用于「面对面快传」这类不需要认证的场景。
 *
 * 客户端连接时先试安全通道，失败再回落不安全通道（见 [BtTransport.open]），
 * 所以任意两台装了本应用的手机都能互通，无论它们是否配对过。
 */
object BtConstants {

    /** 安全通道服务记录 UUID（需要配对）。 */
    val UUID_SECURE: UUID = UUID.fromString("6f1d2a3b-9c4e-4a77-8f21-5b6c7d8e9f01")

    /** 不安全通道服务记录 UUID（无需配对）。 */
    val UUID_INSECURE: UUID = UUID.fromString("a4b8c1d2-3e5f-4a90-b7c6-1d2e3f405162")

    const val SERVICE_NAME_SECURE = "LiuliChat"
    const val SERVICE_NAME_INSECURE = "LiuliChatInsecure"

    /** 写进 HELLO 包、用于对端日志与兼容性判断的应用版本号。 */
    const val APP_VERSION = "1.0"

    /** 心跳间隔：静默链路每 15 秒发一个 PING。 */
    const val HEARTBEAT_INTERVAL_MS = 15_000L

    /** 超过这么久没收到任何帧就判定对端掉线。 */
    const val LINK_TIMEOUT_MS = 45_000L

    /** HELLO 发出后等待握手上限。 */
    const val HANDSHAKE_TIMEOUT_MS = 20_000L

    /** 单次 RFCOMM 建链超时。 */
    const val CONNECT_TIMEOUT_MS = 25_000L

    /** 写队列容量（帧数）。16 KB 分片下约 4 MB 未落盘缓冲。 */
    const val WRITE_QUEUE_CAPACITY = 256

    /** 群聊中继最大跳数；收到 hop > 1 的广播包直接丢弃，防止环路。 */
    const val RELAY_MAX_HOP = 1

    /** 文件发送中「等待对方接受」的上限。 */
    const val OFFER_TIMEOUT_MS = 60_000L

    /** 等待对端 FILE_DONE 回执的上限（对端在算 sha256，可能稍慢）。 */
    const val DONE_ACK_TIMEOUT_MS = 30_000L

    /** 传输结束（完成/失败/取消）后，进度项在列表里保留多久。 */
    const val TRANSFER_LINGER_MS = 12_000L

    /** 传输超时扫描周期。 */
    const val TRANSFER_SWEEP_MS = 5_000L

    /** 多久没有任何分片进展就判定这条传输卡死（群聊里晚加入/对端掉线的兜底）。 */
    const val TRANSFER_IDLE_TIMEOUT_MS = 30_000L

    /**
     * 链路可用性门限：实测写入吞吐低于这个值就先不下大文件。
     *
     * 8 KB/s 大约是 RFCOMM 实际可用带宽（100–200 KB/s）的二十分之一；
     * 低于它就说明链路已经被挤爆或信号很差，硬下只会「爬十分钟然后失败」。
     */
    const val MIN_USABLE_BPS = 8_000L

    /** 普通写队列积压超过这个数，就认为链路堵住了。 */
    const val LINK_BACKLOG_LIMIT = 64

    /** 还没有实测样本时，这个大小以内的文件可以直接开始下（缩略图/语音/小图）。 */
    const val OPTIMISTIC_START_BYTES = 2L * 1024 * 1024

    /** 通话中（[inCall] 为真）只放行这个大小以内的自动下载：缩略图、语音、小图。 */
    const val CALL_HARD_GATE_BYTES = 200L * 1024

    /** 预估耗时超过这个秒数就不开始下载（爬十分钟再失败最伤体验）。 */
    const val MAX_TRANSFER_ETA_SECONDS = 90L

    /** 「等待中」的任务多久重试一次。 */
    const val WAIT_RETRY_MS = 15_000L

    /** 会话内 typing 指示的存活时间，超时会自动熄灭。 */
    const val TYPING_TTL_MS = 6_000L

    /** 去重表容量（消息 id 级别的 LRU）。 */
    const val SEEN_CAPACITY = 512

    /** 进度上报节流间隔，避免每 16 KB 都写一次数据库。 */
    const val PROGRESS_THROTTLE_MS = 120L

    /** 拉黑名单缓存时间：入站每个包都要判一次，不能每次都去读 SharedPreferences。 */
    const val BLOCK_CACHE_TTL_MS = 1_000L

    // ------------------------------------------------------------ 自动连接

    /** 自动连接轮询间隔（亮屏）。 */
    const val AUTO_CONNECT_INTERVAL_MS = 15_000L

    /** 自动连接轮询间隔（息屏，省电）。 */
    const val AUTO_CONNECT_IDLE_INTERVAL_MS = 30_000L

    // ------------------------------------------------------------ 离线中转

    /**
     * 中继缓存条数上限。
     *
     * 这是一个 epidemic（传染病式）转发网络：同一条消息会在多台设备上被复制保管。
     * 没有上限，用户手机的存储会被**别人的**聊天记录吃光。300 条足够日常使用，
     * 极端积压时按「最旧」淘汰。
     */
    const val RELAY_MAX_ENTRIES = 300

    /** 中继缓存字节上限（payload 合计）。 */
    const val RELAY_MAX_BYTES = 4L * 1024 * 1024

    /** 信封存活时间：48 小时还没投到就丢弃。 */
    const val RELAY_TTL_MS = 48L * 60 * 60 * 1000

    /**
     * 一封**存储转发**信封最多中转几跳，超过直接丢
     * （配合 msgId 去重，杜绝 A→B→C→A 无限转）。
     *
     * 注意与 [RELAY_MAX_HOP] 区分：那个是「群聊实时扇出」的跳数上限。
     */
    const val RELAY_FORWARD_MAX_HOP = 4

    /** 过期扫描周期。 */
    const val RELAY_SWEEP_MS = 10 * 60 * 1000L

    /** 中继去重表容量（msgId 级别的 LRU）。 */
    const val RELAY_SEEN_CAPACITY = 2000

    // ------------------------------------------------------------------ 通话

    /**
     * 实时通话媒体帧的高优先队列容量。
     *
     * 语音帧只有「还新鲜」时才有意义：排队 200ms 的语音不如丢掉。所以这条队列满了
     * 以后丢最旧的、保最新的，绝不阻塞采集线程。
     */
    const val CALL_QUEUE_CAPACITY = 32

    /** 写线程在普通队列上等待的最长时间，决定语音帧的最坏排队延迟（约一帧）。 */
    const val CALL_WRITE_POLL_MS = 20L

    /** 呼出后无人接听的自动挂断时间。 */
    const val CALL_RING_TIMEOUT_MS = 45_000L

    /** 收到来电后一直不接的自动挂断时间。 */
    const val CALL_INCOMING_TIMEOUT_MS = 60_000L

    /** 通话期间检查链路是否还在的周期。 */
    const val CALL_WATCHDOG_MS = 2_000L

    /**
     * 单次通话人数上限（含自己）。
     *
     * RFCOMM 单链路带宽有限，群语音靠群主 O(N) 扇出，人越多每路越挤；
     * 4 人（3 路音频）在蓝牙上是可用上限，再多会明显卡顿。
     */
    const val MAX_CALL_PARTICIPANTS = 4

    /** 前台服务常量。 */
    const val NOTIFICATION_CHANNEL_ID = "liuli_link"
    const val NOTIFICATION_CHANNEL_NAME = "蓝牙连接"
    const val NOTIFICATION_ID = 42110
}
