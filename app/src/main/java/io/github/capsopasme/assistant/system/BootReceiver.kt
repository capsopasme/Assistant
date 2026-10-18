package io.github.capsopasme.assistant.system

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import io.github.capsopasme.assistant.util.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * HyperOS hands the assistant back to 小爱 on boot; re-apply our setup shortly after.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val app = context.applicationContext
        // boot is one of the moments a foreground service may be started from the background
        KeepAliveService.sync(app)
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                withTimeoutOrNull(25_000) {
                    // HyperOS' own assistant initialisation runs right after boot; go after it
                    if (action == Intent.ACTION_BOOT_COMPLETED) delay(8_000)
                    if (Prefs.get(app).keepAssistant) AssistantSetup.ensure(app, if (action == Intent.ACTION_BOOT_COMPLETED) "开机" else "应用更新")
                }
            } catch (e: Exception) {
                Log.w("BootReceiver", "re-apply failed", e)
            } finally {
                pending.finish()
            }
        }
    }
}
