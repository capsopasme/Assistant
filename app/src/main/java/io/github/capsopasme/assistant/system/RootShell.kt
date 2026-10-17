package io.github.capsopasme.assistant.system

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * One `su` shell (KernelSU), started on first use and closed after a short idle time, so a burst of
 * commands costs a single root session and nothing stays running in the background.
 *
 * KernelSU never shows a prompt: until the app is allowed in the KernelSU manager, `su` fails and
 * [available] stays false.
 */
object RootShell {
    private const val TAG = "RootShell"
    private const val IDLE_CLOSE_MS = 20_000L

    data class Result(val code: Int, val out: String) {
        val ok get() = code == 0
    }

    private val mutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var process: Process? = null
    private var writer: OutputStreamWriter? = null
    private var lines: LinkedBlockingQueue<String>? = null
    private var idleJob: Job? = null

    @Volatile var lastAvailable: Boolean? = null
        private set

    /** Whether root works right now (starts the shell if needed). */
    suspend fun available(): Boolean {
        val r = run("id -u", 6_000)
        val ok = r.ok && r.out.trim() == "0"
        lastAvailable = ok
        return ok
    }

    /** Runs [cmd] (stderr merged into the output). code -1 = no root / shell died / timed out. */
    suspend fun run(cmd: String, timeoutMs: Long = 8_000): Result = withContext(Dispatchers.IO) {
        mutex.withLock {
            idleJob?.cancel()
            val res = try {
                exec(cmd, timeoutMs)
            } catch (e: Exception) {
                Log.w(TAG, "root command failed: ${e.message}")
                closeLocked()
                Result(-1, e.message.orEmpty())
            }
            scheduleIdleClose()
            res
        }
    }

    private fun ensureStarted(): Boolean {
        process?.let { p -> if (p.isAlive) return true }
        closeLocked()
        val p = try {
            ProcessBuilder("su").redirectErrorStream(true).start()
        } catch (e: Exception) {
            lastAvailable = false
            return false
        }
        val queue = LinkedBlockingQueue<String>()
        Thread({
            try {
                BufferedReader(InputStreamReader(p.inputStream, Charsets.UTF_8)).use { r ->
                    while (true) queue.put(r.readLine() ?: break)
                }
            } catch (_: Exception) {
            }
            queue.put(EOF)
        }, "su-reader").apply { isDaemon = true }.start()
        process = p
        writer = OutputStreamWriter(p.outputStream, Charsets.UTF_8)
        lines = queue
        return true
    }

    private fun exec(cmd: String, timeoutMs: Long): Result {
        if (!ensureStarted()) return Result(-1, "su 不可用")
        val w = writer!!
        val q = lines!!
        val marker = "__ASSIST_END_${System.nanoTime()}__"
        // a subshell keeps a failing or exiting command from killing the session
        w.write("( $cmd ) </dev/null 2>&1; echo \"$marker \$?\"\n")
        w.flush()
        val out = StringBuilder()
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            val left = deadline - System.currentTimeMillis()
            if (left <= 0) {
                closeLocked()
                return Result(-1, "超时")
            }
            val line = q.poll(left, TimeUnit.MILLISECONDS) ?: continue
            if (line === EOF) {
                // su exited: denied by KernelSU, or the shell died
                closeLocked()
                lastAvailable = false
                return Result(-1, out.toString().trim())
            }
            if (line.startsWith(marker)) {
                val code = line.removePrefix(marker).trim().toIntOrNull() ?: -1
                lastAvailable = true
                return Result(code, out.toString().trim())
            }
            out.append(line).append('\n')
        }
    }

    private fun scheduleIdleClose() {
        idleJob = scope.launch {
            delay(IDLE_CLOSE_MS)
            mutex.withLock { closeLocked() }
        }
    }

    private fun closeLocked() {
        runCatching { writer?.write("exit\n"); writer?.flush() }
        runCatching { writer?.close() }
        process?.let { p -> runCatching { if (!p.waitFor(300, TimeUnit.MILLISECONDS)) p.destroy() } }
        process = null
        writer = null
        lines = null
    }

    private val EOF = String(charArrayOf('\u0000'))

    /** Quotes an argument for the shell. */
    fun q(s: String): String = "'" + s.replace("'", "'\\''") + "'"
}
