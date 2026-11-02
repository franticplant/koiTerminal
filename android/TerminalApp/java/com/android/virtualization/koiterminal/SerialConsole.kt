package com.android.virtualization.koiterminal

import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import android.util.Log
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * A neutral tap on the VM serial port.
 *
 * One thread drains the VM's console output into a 1 MiB byte ring and into a pipe for the UI. The
 * ring is what the dev API reads, so the UI and the API never compete for the one serial reader.
 * Input goes through a second pipe, so the UI and the API can both write and a lock keeps the API's
 * writes whole.
 *
 * The ring deliberately survives the VM stopping, until the next start, so the last output before a
 * crash is still readable.
 */
internal object SerialConsole {
    private const val CAPACITY = 1024 * 1024

    data class Pipes(val output: ParcelFileDescriptor?, val input: ParcelFileDescriptor?)

    data class Read(
        val data: ByteArray,
        val cursor: Long,
        val gap: Boolean,
        val start: Long,
        val end: Long,
    )

    data class Info(val capacity: Int, val start: Long, val end: Long, val attached: Boolean)

    private val lock = Object()
    private val writeLock = Object()
    private val ring = ByteArray(CAPACITY)
    private var start = 0L
    private var end = 0L

    @Volatile private var attached = false
    @Volatile private var shutdown = true
    @Volatile private var generation = 0L

    private var outputThread: Thread? = null
    private var inputThread: Thread? = null
    private var uiOutput: ParcelFileDescriptor? = null
    private var uiInput: ParcelFileDescriptor? = null
    private var toGuest: OutputStream? = null

    /** Starts a tap for one VM start; the previous pipes and ring are discarded. */
    @Synchronized
    fun attach(output: InputStream?, input: OutputStream?, tag: String): Pipes {
        detach()
        synchronized(lock) {
            start = 0
            end = 0
            lock.notifyAll()
        }
        attached = true
        shutdown = false
        val gen = ++generation

        if (output == null) {
            uiOutput = devNullRead()
            Log.w(tag, "VM has no serial console output")
        } else {
            val pipe = ParcelFileDescriptor.createPipe()
            val read = pipe[0]
            val write = pipe[1]
            try {
                // The UI may not be reading; never let a full UI pipe stall the VM drain.
                Os.fcntlInt(write.fileDescriptor, OsConstants.F_SETFL, OsConstants.O_NONBLOCK)
            } catch (e: Exception) {
                Log.w(tag, "cannot make the console tap non-blocking", e)
            }
            uiOutput = read
            val sink = ParcelFileDescriptor.AutoCloseOutputStream(write)
            outputThread =
                Thread(
                        { drainOutput(output, sink, tag, gen) },
                        "koi-console-out",
                    )
                    .apply {
                        isDaemon = true
                        start()
                    }
        }

        if (input == null) {
            uiInput = devNullWrite()
            Log.w(tag, "VM has no serial console input")
        } else {
            val pipe = ParcelFileDescriptor.createPipe()
            val ui = ParcelFileDescriptor.dup(pipe[1].fileDescriptor)
            val internal = ParcelFileDescriptor.dup(pipe[1].fileDescriptor)
            pipe[1].close()
            uiInput = ui
            toGuest = ParcelFileDescriptor.AutoCloseOutputStream(internal)
            inputThread =
                Thread(
                        {
                            drainInput(
                                ParcelFileDescriptor.AutoCloseInputStream(pipe[0]),
                                input,
                                tag,
                                gen,
                            )
                        },
                        "koi-console-in",
                    )
                    .apply {
                        isDaemon = true
                        start()
                    }
        }
        return Pipes(uiOutput, uiInput)
    }

    /** Closes the pipes but keeps the ring. */
    @Synchronized
    fun detach() {
        shutdown = true
        generation++
        outputThread?.interrupt()
        inputThread?.interrupt()
        outputThread = null
        inputThread = null
        runCatching { uiOutput?.close() }
        runCatching { uiInput?.close() }
        runCatching { toGuest?.close() }
        uiOutput = null
        uiInput = null
        toGuest = null
        attached = false
    }

    fun write(bytes: ByteArray): Boolean {
        val out = toGuest ?: return false
        return try {
            synchronized(writeLock) {
                out.write(bytes)
                out.flush()
            }
            true
        } catch (e: IOException) {
            false
        }
    }

    /**
     * Reads bytes after [after], or the newest [max] bytes when [after] is null. With [waitMs]
     * greater than zero, waits until there is something new or the deadline passes.
     */
    fun read(after: Long?, max: Int, waitMs: Long): Read {
        val deadline = System.currentTimeMillis() + waitMs
        synchronized(lock) {
            var result = readLocked(after, max)
            while (
                result.data.isEmpty() &&
                    !result.gap &&
                    waitMs > 0
            ) {
                val left = deadline - System.currentTimeMillis()
                if (left <= 0) break
                try {
                    lock.wait(left)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                }
                result = readLocked(after, max)
            }
            return result
        }
    }

    fun info(): Info = synchronized(lock) { Info(CAPACITY, start, end, attached) }

    /** The last [n] bytes, for a stopped VM's exit record. */
    fun tail(n: Int): ByteArray = synchronized(lock) {
        val count = minOf(n.toLong(), end - start).toInt().coerceAtLeast(0)
        copy(end - count, count)
    }

    private fun readLocked(after: Long?, max: Int): Read {
        if (max <= 0) return Read(ByteArray(0), end, false, start, end)
        if (after == null) {
            val count = minOf(max.toLong(), end - start).toInt().coerceAtLeast(0)
            val from = end - count
            return Read(copy(from, count), end, false, start, end)
        }
        val gap = after < start
        val from = maxOf(after, start)
        if (from >= end) return Read(ByteArray(0), end, gap, start, end)
        val count = minOf(max.toLong(), end - from).toInt()
        return Read(copy(from, count), from + count, gap, start, end)
    }

    private fun copy(from: Long, count: Int): ByteArray {
        if (count <= 0) return ByteArray(0)
        val out = ByteArray(count)
        val offset = (from % CAPACITY).toInt()
        val first = minOf(count, CAPACITY - offset)
        System.arraycopy(ring, offset, out, 0, first)
        if (first < count) System.arraycopy(ring, 0, out, first, count - first)
        return out
    }

    private fun append(bytes: ByteArray, offset: Int, count: Int) {
        if (count <= 0) return
        synchronized(lock) {
            if (count >= CAPACITY) {
                System.arraycopy(bytes, offset + count - CAPACITY, ring, 0, CAPACITY)
                end += count
                start = end - CAPACITY
            } else {
                val at = (end % CAPACITY).toInt()
                val first = minOf(count, CAPACITY - at)
                System.arraycopy(bytes, offset, ring, at, first)
                if (first < count) System.arraycopy(bytes, offset + first, ring, 0, count - first)
                end += count
                if (end - start > CAPACITY) start = end - CAPACITY
            }
            lock.notifyAll()
        }
    }

    private fun drainOutput(
        output: InputStream,
        sink: OutputStream,
        tag: String,
        gen: Long,
    ) {
        val buffer = ByteArray(4096)
        try {
            while (!shutdown && generation == gen) {
                val n = output.read(buffer)
                if (shutdown || generation != gen) break
                if (n < 0) break
                if (n == 0) continue
                append(buffer, 0, n)
                try {
                    sink.write(buffer, 0, n)
                } catch (e: IOException) {
                    // The UI pipe is full or closed. The ring still has the bytes.
                }
            }
        } catch (e: IOException) {
            if (!shutdown) Log.w(tag, "serial output ended: ${e.message}")
        } finally {
            runCatching { sink.close() }
        }
    }

    private fun drainInput(
        from: InputStream,
        toGuest: OutputStream,
        tag: String,
        gen: Long,
    ) {
        val buffer = ByteArray(4096)
        try {
            while (!shutdown && generation == gen) {
                val n = from.read(buffer)
                if (shutdown || generation != gen) break
                if (n < 0) break
                if (n == 0) continue
                synchronized(writeLock) {
                    toGuest.write(buffer, 0, n)
                    toGuest.flush()
                }
            }
        } catch (e: IOException) {
            if (!shutdown) Log.w(tag, "serial input ended: ${e.message}")
        } finally {
            runCatching { from.close() }
        }
    }

    private fun devNullRead(): ParcelFileDescriptor {
        val pipe = ParcelFileDescriptor.createPipe()
        runCatching { pipe[1].close() }
        return pipe[0]
    }

    private fun devNullWrite(): ParcelFileDescriptor {
        val pipe = ParcelFileDescriptor.createPipe()
        val read = pipe[0]
        val gen = generation
        Thread(
                {
                    ParcelFileDescriptor.AutoCloseInputStream(read).use { input ->
                        val buffer = ByteArray(4096)
                        try {
                            while (!shutdown && generation == gen) input.read(buffer)
                        } catch (e: IOException) {
                            // expected on detach
                        }
                    }
                },
                "koi-console-null",
            )
            .apply {
                isDaemon = true
                start()
            }
        return pipe[1]
    }
}
