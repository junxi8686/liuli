package com.liuli.btchat.bt.call

/**
 * 实时通话的本地媒体层。
 *
 * [CallController] 只依赖这个接口，绝不直接碰 AudioRecord / Camera / AudioTrack ——
 * 真机实现由 media-pipeline 交付（task-15），在 `Engine.callMedia` 上装配。
 *
 * **约定**
 * * [start] 之后实现方应当开始采集，并把每一帧通过 [onFrame] 抛给控制器；
 *   控制器负责打包成 `Wire.TYPE_CALL` 帧发出去。
 * * [onRemoteFrame] 由控制器在对端帧到达时调用，实现方负责播放 / 渲染。
 *   `data` 从 `offset` 开始的 `len` 字节属于这一帧，回调返回后即可复用。
 * * [onFrame] 传出的是**采集缓冲**：控制器会在回调内立刻拷贝，实现方可以复用同一块缓冲。
 * * [stop] 必须能重复调用，并且要停掉所有采集/播放线程；挂断后不允许再有 [onFrame]。
 *
 * **没有装配时**（`media == null`）控制器会把通话降级为「信令可用、但没有声音」，
 * 并通过 `CallState.mediaReady = false` 如实告诉 UI —— 不崩，也不假装有声音。
 */
interface CallMedia {

    /** 开始一次通话的采集与播放。[video] 为 true 时同时开摄像头。 */
    fun start(callId: String, video: Boolean)

    /** 结束采集与播放。幂等。 */
    fun stop()

    /** 静音：本地仍然采集但不再产生音频帧，或采集后丢弃（实现自定）。 */
    fun setMuted(muted: Boolean)

    /** 开关摄像头。语音通话里应当无效。 */
    fun setCamera(on: Boolean)

    /** 前后摄像头翻转。 */
    fun switchCamera()

    /**
     * 本地采集帧出口，由 [CallController] 在 `start()` 前赋值。
     *
     * 参数：`kind` = `Wire.CALL_AUDIO` / `Wire.CALL_VIDEO`，`data` 有效区间为 `[0, len)`。
     */
    var onFrame: ((kind: Int, data: ByteArray, len: Int) -> Unit)?

    /** 对端来的帧，交给实现方播放 / 渲染。 */
    fun onRemoteFrame(kind: Int, data: ByteArray, offset: Int, len: Int)

    /**
     * 本地媒体最近一次失败的中文原因（例如「没有麦克风权限」）；一切正常时为 null。
     *
     * 控制器会把它透传到 `CallState.mediaError`，让 UI 能如实提示「通话已接通但听不到声音」。
     */
    val lastError: String?

    /** 采集/播放是否正在运行。挂断后应为 false。 */
    val active: Boolean
}

/**
 * 空实现：什么都没装配时使用。
 *
 * 只是让 `media` 不为 null 的兜底选择，正常路径应当保持 `media == null`，
 * 这样 `CallState.mediaReady` 才会如实为 false。
 */
object NoOpCallMedia : CallMedia {
    override var onFrame: ((kind: Int, data: ByteArray, len: Int) -> Unit)? = null
    override fun start(callId: String, video: Boolean) = Unit
    override fun stop() = Unit
    override fun setMuted(muted: Boolean) = Unit
    override fun setCamera(on: Boolean) = Unit
    override fun switchCamera() = Unit
    override fun onRemoteFrame(kind: Int, data: ByteArray, offset: Int, len: Int) = Unit
    override val lastError: String? = "未装配通话媒体层"
    override val active: Boolean = false
}
