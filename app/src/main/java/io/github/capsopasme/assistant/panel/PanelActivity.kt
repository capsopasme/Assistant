package io.github.capsopasme.assistant.panel

import android.app.KeyguardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import io.github.capsopasme.assistant.R
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope

/**
 * The panel as an activity: for "打开助手" in the settings and for apps sending the ASSIST intent.
 * The power key uses the assistant session's own window instead (see AssistSession).
 */
class PanelActivity : AppCompatActivity(), PanelHost {

    private lateinit var panel: PanelController
    private var requestingPermission = false
    private var waitingForUnlock = false

    private var permissionWaiter: CompletableDeferred<Boolean>? = null
    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        requestingPermission = false
        permissionWaiter?.complete(res.values.all { it })
        permissionWaiter = null
    }

    override val scope: CoroutineScope get() = lifecycleScope

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setShowWhenLocked(true)
        setContentView(R.layout.activity_panel)
        panel = PanelController(this, findViewById(R.id.root), this)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = close()
        })

        // invoked on the lock screen: ask for the unlock first; give up if the user doesn't
        val km = getSystemService(KeyguardManager::class.java)
        if (km?.isKeyguardLocked == true) {
            waitingForUnlock = true
            km.requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
                override fun onDismissSucceeded() { waitingForUnlock = false }
                override fun onDismissCancelled() { waitingForUnlock = false; close() }
                override fun onDismissError() { waitingForUnlock = false; close() }
            })
        }
        panel.onShown()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        panel.onShown()
    }

    override fun onStop() {
        super.onStop()
        // the bouncer showing over us, or a permission dialog: not a reason to close
        if (isChangingConfigurations || requestingPermission || waitingForUnlock) return
        close()
    }

    override fun onDestroy() {
        super.onDestroy()
        permissionWaiter?.complete(false)
        panel.dispose()
    }

    override fun close() {
        if (isFinishing) return
        panel.dispose()
        finish()
    }

    override suspend fun requestPermissions(permissions: Array<String>): Boolean {
        if (permissions.all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }) return true
        val waiter = CompletableDeferred<Boolean>()
        permissionWaiter?.complete(false)
        permissionWaiter = waiter
        requestingPermission = true
        permissionLauncher.launch(permissions)
        return waiter.await()
    }

    companion object {
        const val EXTRA_SOURCE = "source"
    }
}
