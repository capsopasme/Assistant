package io.github.capsopasme.assistant.charge

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.service.quicksettings.TileService
import android.util.Log

/**
 * Receives the ChgCtl daemon's state pushes:
 *   cmd activity broadcast -n io.github.capsopasme.assistant/.charge.ChargeStateReceiver
 *       -a io.github.capsopasme.assistant.action.CHGCTL_STATE --es status "key=value lines"
 * Guarded by a signature permission, so other apps cannot fake a state (root passes every
 * permission check). Only updates the in-memory state and asks the tile to redraw.
 */
class ChargeStateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        val text = intent.getStringExtra("status") ?: return
        val snap = ChargeState.Snapshot.parse(text)
        ChargeState.update(snap)
        ChargeHints.onState(context.applicationContext, snap)
        runCatching {
            TileService.requestListeningState(context, ComponentName(context, BypassTileService::class.java))
        }.onFailure { Log.w("ChargeState", "tile refresh: ${it.message}") }
    }

    companion object {
        const val ACTION = "io.github.capsopasme.assistant.action.CHGCTL_STATE"
    }
}
