package io.github.capsopasme.assistant.util

import android.content.Context
import java.io.File

/**
 * Timestamps shared between the assistant process (":vis") and the panel process, so it can be
 * told whether a power-key press reached the assistant and whether the panel then really opened.
 * Plain files: SharedPreferences are not reliable across processes.
 */
object Marks {
    const val SHOW = "show"       // the system asked the assistant to show (power key)
    const val PANEL = "panel"     // the panel activity was created / re-launched
    const val BLOCKED = "blocked" // the panel did not come up after a show

    private fun file(context: Context, name: String) = File(context.applicationContext.noBackupFilesDir, "mark_$name")

    fun write(context: Context, name: String, time: Long = System.currentTimeMillis()) {
        runCatching { file(context, name).writeText(time.toString()) }
    }

    fun read(context: Context, name: String): Long =
        runCatching { file(context, name).readText().trim().toLong() }.getOrDefault(0L)
}
