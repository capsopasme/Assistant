package io.github.capsopasme.assistant.util

import android.content.Context
import java.io.File

/**
 * Timestamps telling whether a power-key press reached the assistant and whether the panel was
 * then really shown (diagnostics on the settings screen).
 */
object Marks {
    const val SHOW = "show"       // the system asked the assistant to show (power key)
    const val PANEL = "panel"     // the panel was actually shown
    const val REPAIR = "repair"   // the setup was found reset and re-applied

    private fun file(context: Context, name: String) = File(context.applicationContext.noBackupFilesDir, "mark_$name")

    fun write(context: Context, name: String, time: Long = System.currentTimeMillis()) {
        runCatching { file(context, name).writeText(time.toString()) }
    }

    /** A short note next to the timestamp (e.g. what a repair fixed). */
    fun writeText(context: Context, name: String, text: String) {
        runCatching { file(context, "${name}_note").writeText(text) }
    }

    fun readText(context: Context, name: String): String =
        runCatching { file(context, "${name}_note").readText() }.getOrDefault("")

    fun read(context: Context, name: String): Long =
        runCatching { file(context, name).readText().trim().toLong() }.getOrDefault(0L)
}
