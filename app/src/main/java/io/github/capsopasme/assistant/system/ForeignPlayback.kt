package io.github.capsopasme.assistant.system

import android.content.Context
import android.media.AudioManager
import android.os.Process
import android.util.Log
import io.github.capsopasme.assistant.util.Marks
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Pauses players that ignore the assistant's audio focus, and resumes exactly those afterwards.
 *
 * Most players (Gramophone, Bilibili…) pause on a transient focus loss and resume when the focus
 * comes back; nothing else is needed for them. Some don't: 红果短剧 receives LOSS_TRANSIENT and keeps
 * playing, and the panel (a voice-interaction window) doesn't pause the activity under it either.
 * The video's sound then reaches the microphone and gets recognized as commands.
 *
 * So a moment after taking focus, if music is still playing, a root helper ([io.github.capsopasme
 * .assistant.root.MediaTool]) finds the apps still playing and pauses their own media sessions.
 * Nothing runs when nothing is playing (one AudioManager call), and nothing stays running.
 */
object ForeignPlayback {
    private const val TAG = "ForeignPlayback"
    private const val TOOL = "io.github.capsopasme.assistant.root.MediaTool"
    /** time for players that do handle focus to pause by themselves */
    private const val SETTLE_MS = 300L
    const val MARK = "foreign_pause"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private var pauseJob: Job? = null
    /** packages we paused and owe a resume */
    private val paused = LinkedHashSet<String>()

    /** called right after the assistant took audio focus */
    fun onFocusTaken(context: Context) {
        val app = context.applicationContext
        pauseJob?.cancel()
        pauseJob = scope.launch {
            delay(SETTLE_MS)
            val am = app.getSystemService(AudioManager::class.java) ?: return@launch
            if (!am.isMusicActive) return@launch
            if (RootShell.lastAvailable == false) return@launch
            mutex.withLock {
                val out = tool(app, "pause", Process.myUid().toString())
                val got = out.filter { it.first == "paused" }.map { it.second.substringBefore(' ') }
                paused.addAll(got)
                val summary = out.joinToString("；") { "${it.first}=${it.second}" }
                Log.i(TAG, "pause: $summary")
                if (summary.isNotEmpty()) Marks.writeText(app, MARK, summary)
            }
        }
    }

    /** called when the assistant gives the focus back: resume what we paused */
    fun onFocusReleased(context: Context) {
        val app = context.applicationContext
        val pending = pauseJob
        scope.launch {
            pending?.join() // a pause still on its way must land first, or the resume would miss it
            mutex.withLock {
                if (paused.isEmpty()) return@withLock
                val pkgs = paused.toList()
                paused.clear()
                tool(app, "play", *pkgs.toTypedArray())
            }
        }
    }

    /** the user gave a media command ("暂停", "继续播放"…): it's theirs now, don't resume on our own */
    fun forget() {
        pauseJob?.cancel()
        scope.launch { mutex.withLock { paused.clear() } }
    }

    private suspend fun tool(ctx: Context, vararg args: String): List<Pair<String, String>> {
        val apk = ctx.applicationInfo.sourceDir
        val cmd = "CLASSPATH=${RootShell.q(apk)} /system/bin/app_process /system/bin $TOOL " +
            args.joinToString(" ") { RootShell.q(it) }
        val r = RootShell.run(cmd, 10_000)
        return r.out.lineSequence().mapNotNull { line ->
            val i = line.indexOf('=')
            if (i > 0) line.substring(0, i).trim() to line.substring(i + 1).trim() else null
        }.toList()
    }
}
