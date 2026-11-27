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
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Pauses players that ignore the assistant's audio focus, and resumes exactly those afterwards.
 *
 * Most players (Gramophone, Bilibili…) pause on a transient focus loss and resume when the focus
 * comes back; nothing else is needed for them. Some don't: 红果短剧 receives LOSS_TRANSIENT and keeps
 * playing, and the panel (a voice-interaction window) doesn't pause the activity under it either.
 * The video's sound then reaches the microphone and gets recognized as commands.
 *
 * So when something was playing as the assistant took focus, a root helper ([io.github.capsopasme
 * .assistant.root.MediaTool]) is started right away (its ~0.4 s start-up overlaps the moment
 * well-behaved players need to pause themselves), finds the apps still playing and pauses their
 * own media sessions. Until it reports, the panel holds auto-send; if it paused anything, what was
 * heard meanwhile is the video's sound, not the user, and is dropped.
 * Nothing runs when nothing was playing, and nothing stays running.
 */
object ForeignPlayback {
    private const val TAG = "ForeignPlayback"
    private const val TOOL = "io.github.capsopasme.assistant.root.MediaTool"
    const val MARK = "foreign_pause"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private var pauseJob: Job? = null
    /** what we paused / muted and owe a resume: MediaTool's tokens (s:PKG, p:PIID, m:PIID) */
    private val paused = LinkedHashSet<String>()

    /** whether anything is playing (call before taking focus: well-behaved players stop right after) */
    fun musicActive(context: Context): Boolean =
        context.getSystemService(AudioManager::class.java)?.isMusicActive == true

    /**
     * Called right after the assistant took audio focus, if [musicActive] was true just before.
     * [onDone] runs on the main thread with whether an app had to be paused (always called).
     */
    fun onFocusTaken(context: Context, onDone: (pausedAny: Boolean) -> Unit) {
        val app = context.applicationContext
        pauseJob?.cancel()
        pauseJob = scope.launch {
            var any = false
            try {
                if (RootShell.lastAvailable != false) mutex.withLock {
                    val out = tool(app, "pause", Process.myUid().toString())
                    val tokens = out.filter { it.first == "resume" }.map { it.second }
                    if (isActive) paused.addAll(tokens)
                    else undoMutes(app, tokens) // forgotten meanwhile: owe no resume, but never leave a player muted
                    any = out.any { it.first == "paused" }
                    val summary = out.filter { it.first != "resume" }.joinToString("；") { "${it.first}=${it.second}" }
                    Log.i(TAG, "pause: $summary")
                    if (summary.isNotEmpty()) Marks.writeText(app, MARK, summary)
                }
            } finally {
                val result = any
                withContext(Dispatchers.Main + kotlinx.coroutines.NonCancellable) { onDone(result) }
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
                val tokens = paused.toList()
                paused.clear()
                tool(app, "resume", *tokens.toTypedArray())
            }
        }
    }

    /**
     * The user took over playback or left the app under the panel: don't start it again on our own.
     * A player we only muted is always unmuted (a muted player would stay silent for good), and a
     * paused one is resumed when [resume] (the user said "继续播放": its own session may ignore that,
     * as 红果's does, so the players are started directly).
     */
    fun takeOver(context: Context, resume: Boolean) {
        val app = context.applicationContext
        val pending = pauseJob
        pending?.cancel()
        scope.launch {
            pending?.join()
            mutex.withLock {
                val tokens = paused.toList()
                paused.clear()
                if (resume) { if (tokens.isNotEmpty()) tool(app, "resume", *tokens.toTypedArray()) }
                else undoMutes(app, tokens)
            }
        }
    }

    private suspend fun undoMutes(app: Context, tokens: List<String>) {
        val mutes = tokens.filter { it.startsWith("m:") }
        if (mutes.isNotEmpty()) tool(app, "resume", *mutes.toTypedArray())
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
