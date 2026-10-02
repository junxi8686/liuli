package com.liuli.btchat.core

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream

/**
 * One bidirectional byte pipe. Bluetooth RFCOMM, a loopback socket in the
 * self-test and anything else that can move bytes implement this, which is why
 * the whole chat engine can be exercised without a second phone.
 */
interface Duplex {
    val label: String
    val input: InputStream
    val output: OutputStream
    fun close()
}

class SocketDuplex(
    override val label: String,
    rawIn: InputStream,
    rawOut: OutputStream,
    private val onClose: () -> Unit
) : Duplex {

    override val input: InputStream = BufferedInputStream(rawIn, 64 * 1024)
    override val output: OutputStream = BufferedOutputStream(rawOut, 64 * 1024)

    @Volatile
    private var closed = false

    override fun close() {
        if (closed) return
        closed = true
        runCatching { input.close() }
        runCatching { output.close() }
        runCatching { onClose() }
    }
}

/**
 * An in-process pair of pipes, for the on-device self-test.
 *
 * This exists because of a permission rule that is easy to miss: Android
 * requires `INTERNET` to open **any** TCP socket, including one on `localhost`.
 * The self-test used to dial `127.0.0.1` and therefore failed on the very first
 * step on a real phone — while passing on the desktop JVM harness, where no
 * permission is involved. Adding `INTERNET` would have been the one-line fix,
 * but it makes the installer claim this app "can access the internet", which is
 * exactly what 琉璃 promises never to do.
 *
 * Two of these make a full-duplex link with no sockets at all:
 *
 * ```
 * val (a, b) = MemoryDuplex.pair()
 * ```
 */
class MemoryDuplex private constructor(
    override val label: String,
    rawIn: InputStream,
    rawOut: OutputStream
) : Duplex {

    override val input: InputStream = BufferedInputStream(rawIn, 64 * 1024)
    override val output: OutputStream = BufferedOutputStream(rawOut, 64 * 1024)

    @Volatile
    private var closed = false

    override fun close() {
        if (closed) return
        closed = true
        runCatching { output.flush() }
        runCatching { input.close() }
        runCatching { output.close() }
    }

    companion object {
        /**
         * Two ends of one full-duplex link. Anything written to `first.output`
         * arrives at `second.input`, and vice versa.
         *
         * Closing is one-directional on purpose: `close()` on one end shuts its
         * own streams, which is what the reader on the other side observes as
         * end-of-stream — the same way a dropped RFCOMM socket behaves.
         */
        fun pair(
            labelFirst: String = "A",
            labelSecond: String = "B"
        ): Pair<MemoryDuplex, MemoryDuplex> {
            val firstToSecond = PipedOutputStream()
            val secondIn = PipedInputStream(firstToSecond, PIPE_CAPACITY)
            val secondToFirst = PipedOutputStream()
            val firstIn = PipedInputStream(secondToFirst, PIPE_CAPACITY)

            val first = MemoryDuplex(labelFirst, firstIn, firstToSecond)
            val second = MemoryDuplex(labelSecond, secondIn, secondToFirst)
            return first to second
        }

        /**
         * Big enough that a 16 KB media chunk plus its header never blocks a
         * writer that the reader has not caught up with yet — a small pipe
         * would deadlock the self-test's bulk-transfer step rather than fail it
         * honestly.
         */
        private const val PIPE_CAPACITY = 512 * 1024
    }
}
