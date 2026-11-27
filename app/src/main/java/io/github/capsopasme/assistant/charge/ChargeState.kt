package io.github.capsopasme.assistant.charge

import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The last known state of the ChgCtl daemon, as pushed by its broadcast (on every change of mode,
 * bypass, plug state, …; never per percent) or read through the CLI. Kept in memory only: the
 * daemon is the source of truth, a stale value is corrected by the next push or read.
 */
object ChargeState {

    data class Snapshot(
        val proto: Int,
        val running: Boolean,
        val mode: String,
        val bypass: Boolean,
        val plugged: Boolean,
        val auto: String,
        val autoOwned: Boolean,
        val override: Boolean,
        /** the daemon's idea of the screen: true / false / null = unknown or not reported */
        val screen: Boolean?,
        val warn: String,
        val icon: Boolean,
        val iconOk: Boolean,
    ) {
        companion object {
            /** The daemon's status file / broadcast: key=value lines. */
            fun parse(text: String): Snapshot {
                val m = HashMap<String, String>()
                for (line in text.lineSequence()) {
                    val i = line.indexOf('=')
                    if (i > 0) m[line.substring(0, i).trim()] = line.substring(i + 1).trim()
                }
                fun b(k: String) = m[k] == "1"
                val mode = m["mode"].orEmpty().ifEmpty { "stopped" }
                return Snapshot(
                    proto = m["proto"]?.toIntOrNull() ?: 0,
                    running = mode != "stopped",
                    mode = mode,
                    bypass = b("bypass"),
                    plugged = b("plugged"),
                    auto = m["auto"].orEmpty().ifEmpty { "off" },
                    autoOwned = b("auto_owned"),
                    override = b("override"),
                    screen = when (m["screen"]) { "1" -> true; "0" -> false; else -> null },
                    warn = m["warn"].orEmpty(),
                    icon = b("icon"),
                    iconOk = b("icon_ok"),
                )
            }
        }
    }

    private val state = MutableStateFlow<Snapshot?>(null)
    val flow: StateFlow<Snapshot?> = state.asStateFlow()
    val current: Snapshot? get() = state.value

    @Volatile var updatedAt = 0L
        private set

    /** Milliseconds since the last update (Long.MAX_VALUE if never). */
    val age: Long get() = if (updatedAt == 0L) Long.MAX_VALUE else SystemClock.elapsedRealtime() - updatedAt

    fun update(s: Snapshot) {
        updatedAt = SystemClock.elapsedRealtime()
        state.value = s
    }

    /** From a full CLI read. */
    fun fromStatus(st: ChgCtl.Status) {
        update(st.toSnapshot())
    }

    /** Optimistic change while a command is on its way; the daemon's push confirms or corrects it. */
    fun assumeBypass(on: Boolean) {
        val s = state.value ?: return
        state.value = s.copy(bypass = on)
    }
}
