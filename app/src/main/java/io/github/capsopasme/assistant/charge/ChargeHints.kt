package io.github.capsopasme.assistant.charge

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.PowerManager
import android.util.Log
import io.github.capsopasme.assistant.system.RootShell
import io.github.capsopasme.assistant.util.AppScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Tells the ChgCtl daemon whether the screen is on, for the "bypass while the screen is on" rule.
 * The daemon cannot see the screen cheaply; this app gets ACTION_SCREEN_ON/OFF for free.
 *
 * Sent only when it matters: the auto rule is "screen", the phone is plugged in, and the daemon's
 * idea of the screen differs from the real one. So unplugged screen toggles cost nothing, and a
 * repeat of the same state is never sent. Each hint is one short root command.
 */
object ChargeHints {
    private const val TAG = "ChargeHints"
    private var job: Job? = null

    fun onScreen(ctx: Context) = sync(ctx, 0)

    /** Just plugged in: the daemon's settle time is a few seconds, no hurry. */
    fun onPower(ctx: Context) = sync(ctx, 1_500)

    /** A state push arrived: correct the daemon if it holds a stale screen state. */
    fun onState(ctx: Context, s: ChargeState.Snapshot) {
        if (s.auto == "screen" && s.plugged && s.running && s.screen != interactive(ctx)) sync(ctx, 300)
    }

    /** At start-up: ask the daemon for a fresh push (only when the module is there and root works). */
    fun requestState() {
        if (RootShell.lastAvailable == false) return
        AppScope.launch(Dispatchers.IO) {
            runCatching { ChgCtl.send(ChgCtl.Cmd.STATUS) }
        }
    }

    private fun sync(ctx: Context, delayMs: Long) {
        val app = ctx.applicationContext
        job?.cancel()
        job = AppScope.launch(Dispatchers.IO) {
            if (delayMs > 0) delay(delayMs)
            val s = ChargeState.current ?: return@launch
            if (!s.supportsHints()) return@launch
            if (!s.plugged && !batteryPlugged(app)) return@launch
            val on = interactive(app)
            if (s.screen == on) return@launch
            val ok = runCatching { ChgCtl.send(if (on) ChgCtl.Cmd.SCREEN_ON else ChgCtl.Cmd.SCREEN_OFF) }.getOrDefault(false)
            Log.d(TAG, "screen $on → daemon: $ok")
        }
    }

    private fun ChargeState.Snapshot.supportsHints() = running && proto >= ChgCtl.PROTO && auto == "screen"

    private fun interactive(ctx: Context): Boolean =
        ctx.getSystemService(PowerManager::class.java)?.isInteractive ?: true

    private fun batteryPlugged(ctx: Context): Boolean {
        val i: Intent? = runCatching { ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) }.getOrNull()
        return (i?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
    }
}
