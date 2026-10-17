package io.github.capsopasme.assistant.panel

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import kotlinx.coroutines.CompletableDeferred

/**
 * Invisible activity that shows the system permission dialog for the assistant window, which
 * can't request permissions itself. Normally unused: the one-tap setup grants them through root.
 */
class PermissionActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val perms = intent.getStringArrayExtra(EXTRA_PERMISSIONS)
        if (perms.isNullOrEmpty() || savedInstanceState != null) {
            if (savedInstanceState == null) deliver(false)
            return
        }
        requestPermissions(perms, 1)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        deliver(grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED })
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isFinishing) deliver(false) // dismissed some other way
    }

    private fun deliver(granted: Boolean) {
        pending?.complete(granted)
        pending = null
        if (!isFinishing) finish()
    }

    companion object {
        private const val EXTRA_PERMISSIONS = "permissions"
        @Volatile private var pending: CompletableDeferred<Boolean>? = null

        suspend fun request(context: Context, permissions: Array<String>): Boolean {
            if (permissions.all { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }) return true
            val waiter = CompletableDeferred<Boolean>()
            pending?.complete(false)
            pending = waiter
            val started = runCatching {
                context.startActivity(
                    Intent(context, PermissionActivity::class.java)
                        .putExtra(EXTRA_PERMISSIONS, permissions)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
                )
            }.isSuccess
            if (!started) { pending = null; return false }
            return waiter.await()
        }
    }
}
