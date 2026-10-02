package com.liuli.btchat.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * The wire format is the one thing both phones must agree on exactly, and it is
 * pure byte shuffling, so it is tested without an emulator or a radio.
 */
class WireTest {

    private fun roundTrip(env: Envelope): Envelope {
        val out = ByteArrayOutputStream()
        Wire.writeControl(out, env)
        val frame = Wire.readFrame(ByteArrayInputStream(out.toByteArray()))
        assertNotNull("frame must be readable", frame)
        assertEquals(Wire.TYPE_CONTROL, frame!!.type)
        return frame.control()
    }

    @Test
    fun `hello survives a round trip`() {
        val env = Envelope(
            from = "device-a",
            packet = HelloPacket(
                deviceId = "device-a",
                name = "琉璃测试机",
                avatarSeed = 137,
                proto = Wire.PROTO_VERSION,
                appVersion = "1.0",
                status = "在蓝牙上"
            )
        )
        val back = roundTrip(env)
        assertEquals("device-a", back.from)
        assertNull(back.to)
        val p = back.packet as HelloPacket
        assertEquals("琉璃测试机", p.name)
        assertEquals(137, p.avatarSeed)
    }

    @Test
    fun `text packet keeps unicode and emoji intact`() {
        val text = "你好，世界 🌍 — ünïcøde"
        val back = roundTrip(
            Envelope(from = "a", to = "b", packet = TextPacket("c1", "m1", text, 1_700_000_000_000L))
        )
        val p = back.packet as TextPacket
        assertEquals(text, p.text)
        assertEquals(1_700_000_000_000L, p.at)
        assertEquals("b", back.to)
    }

    @Test
    fun `all packet types are polymorphic and distinguishable`() {
        val packets = listOf<Packet>(
            HelloAckPacket("a", "A", 1, true),
            GroupInvitePacket("g1", " travellers", 3, "owner", listOf(MemberDto("m", "M", 9, "OWNER"))),
            GroupUpdatePacket("g1", "n", 3, 7L, listOf(MemberDto("m", "M", 9))),
            FileOfferPacket("t1", "m1", "c1", "IMAGE", "a.jpg", "image/jpeg", 1234L, "sha", 100, 200, 0L, null, 5L),
            FileAcceptPacket("t1", 512L),
            FileRejectPacket("t1", "no"),
            FileDonePacket("t1", true, "sha"),
            FileAbortPacket("t1", "gone"),
            ReceiptPacket("m1", "c1", "READ"),
            TypingPacket("c1", true),
            ProfilePacket("a", "A", 1, "hi"),
            PingPacket(42L),
            PongPacket(42L, "a"),
            ByePacket("bye")
        )
        packets.forEach { packet ->
            val back = roundTrip(Envelope(from = "x", packet = packet))
            assertEquals(
                "type mismatch for ${packet::class.simpleName}",
                packet::class,
                back.packet::class
            )
        }
    }

    @Test
    fun `member dto maps onto the domain model`() {
        val dto = MemberDto("dev", "名字", 12, "ADMIN")
        val m = dto.toModel()
        assertEquals("dev", m.deviceId)
        assertEquals(Role.ADMIN, m.role)
        assertEquals(dto, MemberDto.of(m))
    }

    @Test
    fun `unknown role falls back to member instead of throwing`() {
        assertEquals(Role.MEMBER, MemberDto("d", "n", 0, "SUPREME").toModel().role)
    }

    @Test
    fun `chunk frames carry the transfer id and payload verbatim`() {
        val payload = ByteArray(4096) { (it % 251).toByte() }
        val out = ByteArrayOutputStream()
        Wire.writeChunk(out, "transfer-42", seq = 7, data = payload, offset = 0, len = payload.size)

        val frame = Wire.readFrame(ByteArrayInputStream(out.toByteArray()))
        assertNotNull(frame)
        assertEquals(Wire.TYPE_CHUNK, frame!!.type)

        val chunk = Wire.decodeChunk(frame.payload)
        assertEquals("transfer-42", chunk.transferId)
        assertEquals(7, chunk.seq)
        assertEquals(payload.size, chunk.length)
        assertArrayEquals(payload, chunk.buf.copyOfRange(chunk.offset, chunk.offset + chunk.length))
    }

    @Test
    fun `several frames in a row are read back in order`() {
        val out = ByteArrayOutputStream()
        repeat(50) { i ->
            Wire.writeControl(
                out,
                Envelope(from = "a", packet = TextPacket("c", "m$i", "消息 $i", i.toLong()))
            )
        }
        val input = ByteArrayInputStream(out.toByteArray())
        repeat(50) { i ->
            val frame = Wire.readFrame(input)!!
            val p = frame.control().packet as TextPacket
            assertEquals("m$i", p.msgId)
        }
        assertNull("stream must end cleanly", Wire.readFrame(input))
    }

    @Test
    fun `a control frame mixed with chunks keeps both readable`() {
        val out = ByteArrayOutputStream()
        Wire.writeControl(out, Envelope(from = "a", packet = PingPacket(1L)))
        val blob = ByteArray(1000) { it.toByte() }
        Wire.writeChunk(out, "t", 0, blob, 0, blob.size)
        Wire.writeControl(out, Envelope(from = "a", packet = PongPacket(2L, "a")))

        val input = ByteArrayInputStream(out.toByteArray())
        assertEquals(Wire.TYPE_CONTROL, Wire.readFrame(input)!!.type)
        val chunkFrame = Wire.readFrame(input)!!
        assertEquals(Wire.TYPE_CHUNK, chunkFrame.type)
        assertArrayEquals(blob, Wire.decodeChunk(chunkFrame.payload).let {
            it.buf.copyOfRange(it.offset, it.offset + it.length)
        })
        assertEquals(Wire.TYPE_CONTROL, Wire.readFrame(input)!!.type)
    }

    @Test
    fun `a corrupt magic is rejected rather than silently mis-parsed`() {
        val bytes = byteArrayOf(0x00, 0x01, 0x02, 0x03, 1, 0, 0, 0, 0)
        val thrown = runCatching { Wire.readFrame(ByteArrayInputStream(bytes)) }.exceptionOrNull()
        assertNotNull("bad magic must not be accepted", thrown)
        assertTrue(thrown is IllegalStateException)
    }

    @Test
    fun `an oversized length is rejected before allocating`() {
        val header = ByteArrayOutputStream().apply {
            write(byteArrayOf(0x4C, 0x4C, 0x43, 0x31))
            write(Wire.TYPE_CONTROL)
            write(byteArrayOf(0x7F, 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte()))
        }.toByteArray()
        val thrown = runCatching {
            Wire.readFrame(ByteArrayInputStream(header))
        }.exceptionOrNull()
        assertNotNull(thrown)
    }
}
