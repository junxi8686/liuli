package com.liuli.btchat.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

/**
 * [MemoryDuplex] exists because the on-device self-test used to dial a loopback
 * TCP socket, which Android refuses without the `INTERNET` permission — a
 * permission 琉璃 deliberately does not declare. On the desktop JVM harness the
 * socket worked, so the failure only ever showed up on a real phone.
 *
 * These tests pin the behaviour the self-test now depends on. They need no
 * Android runtime, which is the whole point: the fix itself is provable here.
 */
class MemoryDuplexTest {

    /** Everything written on one end must come out, in order, on the other. */
    @Test
    fun `full duplex carries both directions`() {
        val (a, b) = MemoryDuplex.pair("a", "b")

        val payload = ByteArray(64 * 1024).also { Random(7).nextBytes(it) }
        val back = ByteArray(32 * 1024).also { Random(9).nextBytes(it) }

        a.output.write(payload)
        a.output.flush()
        b.output.write(back)
        b.output.flush()

        assertArrayEquals(payload, b.input.readExactly(payload.size))
        assertArrayEquals(back, a.input.readExactly(back.size))

        a.close()
        b.close()
    }

    /**
     * A mebibyte survives the pipe with a reader and a writer running at the
     * same time — which is how the self-test actually uses it (`Connection`
     * runs its read and write loops on separate IO coroutines).
     *
     * The first version of this test wrote all 1 MiB first and only then read,
     * on one thread. That cannot work with any bounded pipe: it filled the
     * 512 KiB buffer plus the 64 KiB buffered stream and blocked forever inside
     * `PipedInputStream.awaitSpace`, hanging the whole `testDebugUnitTest` task
     * with no output. The test was asserting a property a pipe is not supposed
     * to have, so the test was wrong, not the pipe.
     */
    @Test
    fun `a mebibyte flows through a concurrent reader and writer`() {
        val (a, b) = MemoryDuplex.pair()

        val block = ByteArray(16 * 1024).also { Random(11).nextBytes(it) }
        val expected = ByteArrayOutputStream64()
        repeat(64) { expected.write(block) }
        val expectedBytes = expected.toByteArray()

        val received = java.io.ByteArrayOutputStream(64 * 16 * 1024)
        val reader = Thread {
            val buf = ByteArray(8 * 1024)
            var total = 0
            while (total < expectedBytes.size) {
                val n = b.input.read(buf, 0, minOf(buf.size, expectedBytes.size - total))
                if (n <= 0) break
                received.write(buf, 0, n)
                total += n
            }
        }

        reader.start()
        repeat(64) {
            a.output.write(block)
            // Flushing every block is what a real sender does once its bounded
            // queue hands a chunk to the socket.
            a.output.flush()
        }
        reader.join(10_000)

        assertFalse("reader thread did not finish — the pipe deadlocked", reader.isAlive)
        assertArrayEquals(expectedBytes, received.toByteArray())

        a.close()
        b.close()
    }

    /** Closing one end must look like end-of-stream to the other, not a hang. */
    @Test
    fun `closing one end ends the other's read`() {
        val (a, b) = MemoryDuplex.pair()

        a.output.write(byteArrayOf(1, 2, 3))
        a.output.flush()
        a.close()

        // A pipe is FIFO, so these come back in the order they went in. The
        // first version of this test asserted 3, 2, 1 and therefore failed on
        // its first line every single run — a permanently red test proving
        // nothing.
        assertEquals(1, b.input.read())
        assertEquals(2, b.input.read())
        assertEquals(3, b.input.read())
        // -1 is end-of-stream; anything else here means the peer's close did not
        // propagate and a reader thread would spin forever on a dead link.
        assertEquals(-1, b.input.read())

        b.close()
    }

    @Test
    fun `close is idempotent`() {
        val (a, b) = MemoryDuplex.pair()
        a.close()
        a.close()
        b.close()
        b.close()
        assertTrue(a.label.isNotEmpty())
        assertFalse(a.label == b.label)
    }

    private fun java.io.InputStream.readExactly(n: Int): ByteArray {
        val out = ByteArray(n)
        var off = 0
        while (off < n) {
            val read = read(out, off, n - off)
            if (read <= 0) break
            off += read
        }
        assertEquals("stream ended after $off of $n bytes", n, off)
        return out
    }

    /** Local stand-in so the test does not depend on `java.io` naming habits. */
    private class ByteArrayOutputStream64 {
        private val buf = java.io.ByteArrayOutputStream(64 * 16 * 1024)
        fun write(b: ByteArray) = buf.write(b, 0, b.size)
        fun toByteArray(): ByteArray = buf.toByteArray()
    }
}
