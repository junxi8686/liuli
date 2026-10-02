package com.liuli.btchat.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream

/**
 * Wire protocol for the Bluetooth link.
 *
 * Every byte that crosses the socket is one *frame*:
 *
 * ```
 *   magic   4 bytes   0x4C 0x4C 0x43 0x31   ("LLC1")
 *   type    1 byte    1 = control (JSON), 2 = chunk (binary), 3 = ping, 4 = bye
 *   length  4 bytes   big-endian payload length
 *   payload length bytes
 * ```
 *
 * Control payloads are UTF-8 JSON of an [Envelope]. Chunk payloads are
 * `[u16 idLen][transferId][u32 seq][raw bytes]` and never go through JSON, so a
 * 16 KB media slice costs the same as the raw file reads it came from.
 */
object Wire {

    const val PROTO_VERSION = 1
    const val CHUNK_SIZE = 16 * 1024
    const val MAX_FRAME = 12 * 1024 * 1024
    const val DEFAULT_PORT = 0

    const val TYPE_CONTROL = 1
    const val TYPE_CHUNK = 2
    const val TYPE_PING = 3
    const val TYPE_BYE = 4

    /**
     * Real-time call media.
     *
     * A separate frame type from [TYPE_CHUNK] on purpose: call audio is worthless
     * if it queues behind a 5 MB photo, so the transport gives this type its own
     * priority lane instead of sharing the file-transfer queue.
     *
     * Payload: `[u8 kind][u16 callIdLen][callId][data]` with kind 1 = audio,
     * 2 = video.
     */
    const val TYPE_CALL = 5

    const val CALL_AUDIO = 1
    const val CALL_VIDEO = 2

    private val MAGIC = byteArrayOf(0x4C, 0x4C, 0x43, 0x31)

    val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        isLenient = true
        classDiscriminator = "k"
    }

    fun encode(env: Envelope): ByteArray = json.encodeToString(EnvSer, env).toByteArray(Charsets.UTF_8)

    fun decode(bytes: ByteArray): Envelope = json.decodeFromString(EnvSer, String(bytes, Charsets.UTF_8))

    // ---------------------------------------------------------------- framing

    fun writeFrame(out: OutputStream, type: Int, payload: ByteArray) {
        val d = DataOutputStream(out)
        d.write(MAGIC)
        d.writeByte(type)
        d.writeInt(payload.size)
        d.write(payload)
        d.flush()
    }

    fun writeControl(out: OutputStream, env: Envelope) {
        writeFrame(out, TYPE_CONTROL, encode(env))
    }

    fun writeChunk(out: OutputStream, transferId: String, seq: Int, data: ByteArray, offset: Int, len: Int) {
        val id = transferId.toByteArray(Charsets.UTF_8)
        val d = DataOutputStream(out)
        d.write(MAGIC)
        d.writeByte(TYPE_CHUNK)
        d.writeInt(2 + id.size + 4 + len)
        d.writeShort(id.size)
        d.write(id)
        d.writeInt(seq)
        d.write(data, offset, len)
        d.flush()
    }

    /** Reads exactly one frame, or returns null at a clean end of stream. */
    fun readFrame(input: InputStream): Frame? {
        val d = DataInputStream(input)
        val magic = ByteArray(4)
        try {
            d.readFully(magic)
        } catch (_: EOFException) {
            return null
        }
        if (magic[0] != MAGIC[0] || magic[1] != MAGIC[1] || magic[2] != MAGIC[2] || magic[3] != MAGIC[3]) {
            throw IllegalStateException("bad frame magic: ${magic.joinToString(" ") { "%02X".format(it) }}")
        }
        val type = d.readUnsignedByte()
        val len = d.readInt()
        if (len < 0 || len > MAX_FRAME) throw IllegalStateException("frame too large: $len")
        val payload = ByteArray(len)
        d.readFully(payload)
        return Frame(type, payload)
    }

    fun decodeChunk(payload: ByteArray): Chunk {
        // Unpaired insecure RFCOMM sockets mean anyone can connect and send a
        // frame, and `readFrame` only rejects `len < 0 || len > MAX_FRAME` — a
        // zero-length or truncated CHUNK used to walk straight off the end of
        // the array. Rejecting it here turns a crash into a dropped frame.
        require(payload.size >= 6) { "CHUNK frame too short: ${payload.size}" }
        var p = 0
        val idLen = ((payload[p].toInt() and 0xFF) shl 8) or (payload[p + 1].toInt() and 0xFF)
        p += 2
        require(idLen >= 0 && p + idLen + 4 <= payload.size) { "CHUNK idLen $idLen overruns ${payload.size}" }
        val id = String(payload, p, idLen, Charsets.UTF_8)
        p += idLen
        val seq = ((payload[p].toInt() and 0xFF) shl 24) or
            ((payload[p + 1].toInt() and 0xFF) shl 16) or
            ((payload[p + 2].toInt() and 0xFF) shl 8) or
            (payload[p + 3].toInt() and 0xFF)
        p += 4
        return Chunk(id, seq, payload, p, payload.size - p)
    }

    // ------------------------------------------------------- call media frames

    /** `[u8 kind][u16 callIdLen][callId][data]` */
    fun writeCall(
        out: OutputStream,
        kind: Int,
        callId: String,
        data: ByteArray,
        offset: Int,
        len: Int
    ) {
        val id = callId.toByteArray(Charsets.UTF_8)
        val d = DataOutputStream(out)
        d.write(MAGIC)
        d.writeByte(TYPE_CALL)
        d.writeInt(1 + 2 + id.size + len)
        d.writeByte(kind)
        d.writeShort(id.size)
        d.write(id)
        d.write(data, offset, len)
        d.flush()
    }

    fun decodeCall(payload: ByteArray): CallFrame {
        require(payload.size >= 3) { "CALL frame too short: ${payload.size}" }
        val kind = payload[0].toInt() and 0xFF
        val idLen = ((payload[1].toInt() and 0xFF) shl 8) or (payload[2].toInt() and 0xFF)
        require(3 + idLen <= payload.size) { "CALL idLen $idLen overruns ${payload.size}" }
        val id = String(payload, 3, idLen, Charsets.UTF_8)
        val start = 3 + idLen
        return CallFrame(kind, id, payload, start, payload.size - start)
    }

    fun streams(rawIn: InputStream, rawOut: OutputStream): Pair<InputStream, OutputStream> =
        BufferedInputStream(rawIn, 64 * 1024) to BufferedOutputStream(rawOut, 64 * 1024)
}

data class Frame(val type: Int, val payload: ByteArray) {
    fun control(): Envelope = Wire.decode(payload)

    override fun equals(other: Any?): Boolean =
        other is Frame && type == other.type && payload.contentEquals(other.payload)

    override fun hashCode(): Int = 31 * type + payload.contentHashCode()
}

data class Chunk(
    val transferId: String,
    val seq: Int,
    val buf: ByteArray,
    val offset: Int,
    val length: Int
)

/** One slice of live call media. */
data class CallFrame(
    val kind: Int,
    val callId: String,
    val buf: ByteArray,
    val offset: Int,
    val length: Int
)

// --------------------------------------------------------------------- packets

@Serializable
data class MemberDto(
    val deviceId: String,
    val name: String,
    val avatarSeed: Int,
    val role: String = "MEMBER",
    val muted: Boolean = false
) {
    fun toModel(): Member = Member(
        deviceId = deviceId,
        name = name,
        avatarSeed = avatarSeed,
        role = runCatching { Role.valueOf(role) }.getOrDefault(Role.MEMBER),
        muted = muted
    )

    companion object {
        fun of(m: Member) = MemberDto(m.deviceId, m.name, m.avatarSeed, m.role.name, m.muted)
    }
}

@Serializable
sealed interface Packet

@Serializable
@SerialName("hello")
data class HelloPacket(
    val deviceId: String,
    val name: String,
    val avatarSeed: Int = 0,
    val proto: Int = Wire.PROTO_VERSION,
    val appVersion: String = "",
    val status: String = ""
) : Packet

@Serializable
@SerialName("helloAck")
data class HelloAckPacket(
    val deviceId: String,
    val name: String,
    val avatarSeed: Int = 0,
    val accepted: Boolean = true,
    val reason: String = ""
) : Packet

@Serializable
data class QuoteDto(
    val msgId: String,
    val senderName: String,
    val preview: String
) {
    fun toModel(): Quote = Quote(msgId, senderName, preview)

    companion object {
        fun of(q: Quote) = QuoteDto(q.msgId, q.senderName, q.preview)
    }
}

@Serializable
@SerialName("text")
data class TextPacket(
    val convId: String,
    val msgId: String,
    val text: String,
    val at: Long,
    /** Present when this message is a reply to another one. */
    val quote: QuoteDto? = null
) : Packet

@Serializable
@SerialName("recall")
data class RecallPacket(
    val convId: String,
    val msgId: String,
    val at: Long = 0L
) : Packet

/** "Delete for everyone": the peer should drop the message too. */
@Serializable
@SerialName("deleteMsg")
data class DeleteMessagePacket(
    val convId: String,
    val msgId: String
) : Packet

@Serializable
@SerialName("groupInvite")
data class GroupInvitePacket(
    val groupId: String,
    val name: String,
    val avatarSeed: Int = 0,
    val ownerId: String,
    val members: List<MemberDto> = emptyList()
) : Packet

@Serializable
@SerialName("groupUpdate")
data class GroupUpdatePacket(
    val groupId: String,
    val name: String,
    val avatarSeed: Int = 0,
    val revision: Long = 0,
    val members: List<MemberDto> = emptyList(),
    /**
     * Group settings ride along with the member list because they are the same
     * broadcast. Everything is nullable and means "this update does not change
     * it", so an old or partial update can never silently wipe the announcement
     * or re-enable 全员禁言.
     */
    val announcement: String? = null,
    val announcementBy: String = "",
    val announcementAt: Long = 0L,
    val muteAll: Boolean? = null,
    /**
     * The sender's 群昵称, carried on every group update so a member who was
     * offline sees the new name together with the message it belongs to,
     * instead of the message first and the rename a round-trip later.
     */
    val fromNickname: String = ""
) : Packet

@Serializable
@SerialName("fileOffer")
data class FileOfferPacket(
    val transferId: String,
    val msgId: String,
    val convId: String,
    val kind: String,
    val fileName: String,
    val mime: String,
    val size: Long,
    val sha256: String = "",
    val width: Int = 0,
    val height: Int = 0,
    val durationMs: Long = 0,
    val thumbB64: String? = null,
    val at: Long
) : Packet

@Serializable
@SerialName("fileAccept")
data class FileAcceptPacket(
    val transferId: String,
    val fromOffset: Long = 0
) : Packet

@Serializable
@SerialName("fileReject")
data class FileRejectPacket(
    val transferId: String,
    val reason: String = ""
) : Packet

@Serializable
@SerialName("fileDone")
data class FileDonePacket(
    val transferId: String,
    val ok: Boolean = true,
    val sha256: String = ""
) : Packet

@Serializable
@SerialName("fileAbort")
data class FileAbortPacket(
    val transferId: String,
    val reason: String = ""
) : Packet

@Serializable
@SerialName("receipt")
data class ReceiptPacket(
    val msgId: String,
    val convId: String,
    val state: String = "DELIVERED"
) : Packet

@Serializable
@SerialName("typing")
data class TypingPacket(
    val convId: String,
    val on: Boolean
) : Packet

@Serializable
@SerialName("profile")
data class ProfilePacket(
    val deviceId: String,
    val name: String,
    val avatarSeed: Int,
    val status: String = ""
) : Packet

@Serializable
@SerialName("ping")
data class PingPacket(val at: Long) : Packet

@Serializable
@SerialName("pong")
data class PongPacket(val at: Long, val from: String = "") : Packet

@Serializable
@SerialName("bye")
data class ByePacket(val reason: String = "") : Packet

// ----------------------------------------------------------- friend requests

/**
 * Adding someone is a mutual act.
 *
 * Until this existed, two phones that had paired once could talk forever, and
 * deleting a friend changed nothing — the other side simply kept sending. A
 * message from a non-contact now becomes a request that has to be accepted,
 * which is also what makes 「删除好友」 mean something.
 *
 * None of these carry a conversation id on purpose: a friendship is between two
 * devices, not between two chats.
 */
@Serializable
@SerialName("friendRequest")
data class FriendRequestPacket(
    val fromId: String,
    val fromName: String = "",
    val avatarSeed: Int = 0,
    val hello: String = ""
) : Packet

@Serializable
@SerialName("friendAccept")
data class FriendAcceptPacket(
    /** The accepter's own deviceId. */
    val fromId: String,
    /** Carried so the requester can write a real contact row, not a bare id. */
    val name: String = "",
    val avatarSeed: Int = 0
) : Packet

@Serializable
@SerialName("friendReject")
data class FriendRejectPacket(
    val fromId: String,
    val reason: String = ""
) : Packet

// ------------------------------------------------------- store-and-forward

/**
 * Someone else's message, carried by a device that is not the recipient.
 *
 * 琉璃 has no server, so a message addressed to a phone that is not reachable
 * right now has to be handed to whoever *is* reachable and kept moving. An
 * intermediary stores these and forwards them on every connection it makes,
 * which is how A→B→C→D works when D has never met A.
 *
 * **Privacy, stated plainly:** the intermediary holds the payload as plain
 * JSON and can technically read it — there is no end-to-end key exchange in
 * this protocol. What the implementation guarantees is that an envelope is
 * never rendered, never written into the message store, and deleted once the
 * recipient acknowledges it. Saying "it's private" would be a lie.
 */
@Serializable
@SerialName("relayEnvelope")
data class RelayEnvelopePacket(
    /** Globally unique — reuses the original message id, for dedupe and acks. */
    val msgId: String,
    /**
     * Final recipient deviceId. One envelope carries one recipient: a group
     * message becomes one envelope per member so each can be acknowledged and
     * evicted independently.
     */
    val destId: String,
    /** Original sender deviceId; becomes `senderId` when the recipient stores it. */
    val originId: String,
    val convId: String,
    /** Set for group messages, null for a direct chat. */
    val groupId: String? = null,
    /** Relays so far; past the limit the envelope is dropped. */
    val hop: Int = 1,
    /**
     * Absolute expiry (epoch ms). Absolute rather than "remaining TTL" because
     * two phones' clocks can disagree, and an absolute deadline degrades
     * predictably instead of accumulating error at every hop.
     */
    val expiresAt: Long = 0L,
    /** The original envelope, `Wire.encode`d, so no second codec is needed. */
    val payload: String = ""
) : Packet

/** Recipient confirming it stored these ids; intermediaries evict their copies. */
@Serializable
@SerialName("relayAck")
data class RelayAckPacket(
    val msgIds: List<String> = emptyList()
) : Packet

// ------------------------------------------------------------------ calling

@Serializable
@SerialName("callInvite")
data class CallInvitePacket(
    val callId: String,
    val convId: String,
    /** true = 视频通话, false = 语音通话 */
    val video: Boolean,
    val callerId: String,
    val callerName: String,
    /** Every participant except the caller; a group call is a star on the owner. */
    val members: List<String> = emptyList()
) : Packet

@Serializable
@SerialName("callAccept")
data class CallAcceptPacket(
    val callId: String,
    val video: Boolean = false
) : Packet

@Serializable
@SerialName("callReject")
data class CallRejectPacket(
    val callId: String,
    val reason: String = ""
) : Packet

@Serializable
@SerialName("callEnd")
data class CallEndPacket(
    val callId: String,
    val reason: String = ""
) : Packet

/** Mute / camera state, so the other side can show the right indicator. */
@Serializable
@SerialName("callState")
data class CallStatePacket(
    val callId: String,
    val muted: Boolean = false,
    val cameraOn: Boolean = true
) : Packet

/**
 * Routing header. [to] == null means "everyone in [groupId]" — the group owner
 * re-broadcasts those to the other members, which is what makes a group work
 * over a star of RFCOMM links.
 */
@Serializable
data class Envelope(
    val from: String,
    val to: String? = null,
    val groupId: String? = null,
    val hop: Int = 0,
    val packet: Packet
)

private val EnvSer = kotlinx.serialization.serializer<Envelope>()
