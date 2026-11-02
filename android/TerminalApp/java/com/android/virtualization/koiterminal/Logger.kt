/*
 * Copyright (C) 2024 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.virtualization.koiterminal

import android.content.Context
import android.os.ParcelFileDescriptor
import android.system.virtualmachine.VirtualMachine
import android.system.virtualmachine.VirtualMachineConfig
import android.system.virtualmachine.VirtualMachineException
import android.util.Log
import androidx.annotation.WorkerThread
import java.io.BufferedOutputStream
import java.io.BufferedReader
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.LocalDateTime
import java.util.concurrent.ExecutorService
import java.util.concurrent.locks.ReentrantReadWriteLock
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Forwards VM's console output to a file on the Android side, and VM's log output to Android logd.
 */
internal object Logger {
    fun setup(context: Context, vm: VirtualMachine, executor: ExecutorService): SerialIOManager? {
        val tag = vm.name
        val dir = context.getFileStreamPath(vm.name + ".log").toPath()

        if (vm.config.debugLevel != VirtualMachineConfig.DEBUG_LEVEL_FULL) {
            Log.i(tag, "Logs are not captured. Non-debuggable VM.")
            return null
        }

        if (Files.isRegularFile(dir)) {
            Log.i(tag, "Removed legacy log file: $dir")
            Files.delete(dir)
        }
        Files.createDirectories(dir)
        deleteOldLogs(dir, 10)
        val logPath = dir.resolve(LocalDateTime.now().toString() + ".txt")
        val serialIO = SerialIOManager(vm, executor, tag)

        val log = vm.getLogOutput()
        executor.execute({
            log.use {
                try {
                    writeToLogd(it, tag)
                } catch (e: Exception) {
                    Log.w(tag, "Failed to log VM log output. VM may be shutting down", e)
                }
            }
        })
        return serialIO
    }

    // Called by ErrorActivity in another process.
    @WorkerThread
    public fun zipLogs(context: Context, logZipFilePath: Path) {
        Files.newOutputStream(logZipFilePath, StandardOpenOption.CREATE).use { fos ->
            ZipOutputStream(fos).use { zos ->
                val logDirs =
                    context.filesDir.listFiles { it.isDirectory() && it.name.endsWith(".log") }
                        ?: return
                for (dir in logDirs) {
                    val logFiles =
                        dir.listFiles { it.isFile() && it.name.endsWith(".txt") } ?: continue
                    for (file in logFiles) {
                        // To prevent absolute paths in the zip
                        val entryName = file.toRelativeString(context.filesDir)

                        zos.putNextEntry(ZipEntry(entryName))
                        Files.copy(file.toPath(), zos)
                        zos.closeEntry()
                    }
                }
            }
        }
    }

    fun deleteOldLogs(dir: Path, numLogsToKeep: Long) {
        Files.list(dir)
            .filter { Files.isRegularFile(it) }
            .sorted(
                Comparator.comparingLong { f: Path ->
                        // for some reason, type inference didn't work here!
                        Files.getLastModifiedTime(f).toMillis()
                    }
                    .reversed()
            )
            .skip(numLogsToKeep)
            .forEach {
                try {
                    Files.delete(it)
                } catch (e: IOException) {
                    // don't bother
                }
            }
    }

    @Throws(IOException::class)
    private fun writeToLogd(input: InputStream?, tag: String?) {
        val reader = BufferedReader(InputStreamReader(input))
        reader
            .useLines { lines -> lines.takeWhile { !Thread.interrupted() } }
            .forEach { Log.d(tag, it) }
    }

    private class LineBufferedOutputStream(out: OutputStream?) : BufferedOutputStream(out) {
        @Throws(IOException::class)
        override fun write(buf: ByteArray, off: Int, len: Int) {
            super.write(buf, off, len)
            (0 until len).firstOrNull { buf[off + it] == '\n'.code.toByte() }?.let { flush() }
        }
    }
}

class SerialIOManager(
    vm: VirtualMachine,
    private val executor: ExecutorService,
    private val tag: String
) {
    private val serialOutReadingStream = vm.getConsoleOutput()
    private val serialInWritingStream =
        try {
            vm.getConsoleInput()
        } catch (e: VirtualMachineException) {
            null
        }
    private val dev = BuildConfig.DEV_TOOLS
    private val pipes =
        if (dev) {
            SerialConsole.attach(serialOutReadingStream, serialInWritingStream, tag)
        } else {
            // Prod is unchanged: no ring, no extra threads, no dev API.
            SerialConsole.Pipes(
                serialOutStreamToPfd(serialOutReadingStream),
                serialInStreamToPfd(serialInWritingStream),
            )
        }

    @Volatile private var shutdownFlagged = false

    public fun getSerialInPfd() = pipes.input

    public fun getSerialOutPfd() = pipes.output

    fun flagForShutdown() {
        shutdownFlagged = true
        if (dev) SerialConsole.detach()
    }

    private fun serialInStreamToPfd(serialIn: OutputStream?): ParcelFileDescriptor? {
        if (serialIn == null) {
            Log.w(tag, "VM does not support serial console input. Ignoring input...")
            return devNullWritingPfd()
        }
        val serialIn = serialIn as FileOutputStream
        val serialInPfd = ParcelFileDescriptor.dup(serialIn.getFD())
        Log.i(tag, "serialInPfd = fd${serialInPfd.getFd()}")
        return serialInPfd
    }

    private fun devNullWritingPfd(): ParcelFileDescriptor {
        val pipe = ParcelFileDescriptor.createPipe()
        val readEnd = pipe[0]
        val writeEnd = pipe[1]
        executor.execute {
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            val readingStream = ParcelFileDescriptor.AutoCloseInputStream(readEnd)
            try {
                while (!shutdownFlagged) {
                    readingStream.read(buffer)
                }
            } catch (e: IOException) {
                Log.w(tag, "Failed to discard VM serial console input.", e)
            } finally {
                readingStream.close()
            }
        }
        return writeEnd
    }

    private fun serialOutStreamToPfd(serialOut: InputStream?): ParcelFileDescriptor? {
        if (serialOut == null) {
            Log.w(tag, "VM does not support serial console output. Ignoring output...")
            return devNullReadingPfd()
        }
        val serialOut = serialOut as FileInputStream
        return ParcelFileDescriptor.dup(serialOut.getFD())
    }

    private fun devNullReadingPfd(): ParcelFileDescriptor = ParcelFileDescriptor.createPipe()[0]

    companion object {
        const val DEFAULT_BUFFER_SIZE = 4096
    }
}

internal object NoLogger {
    fun setup(context: Context, vm: VirtualMachine, executor: ExecutorService): SerialIOManager? {
        val tag = vm.name

        if (vm.config.debugLevel != VirtualMachineConfig.DEBUG_LEVEL_FULL) {
            Log.i(tag, "Logs are not captured. Non-debuggable VM.")
            return null
        }

        val serialIO = SerialIOManager(vm, executor, tag)
        return serialIO
    }
}
