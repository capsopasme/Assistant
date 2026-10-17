package io.github.capsopasme.assistant.vis

import android.content.Intent
import android.graphics.Color
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import androidx.core.view.WindowCompat
import io.github.capsopasme.assistant.R
import io.github.capsopasme.assistant.panel.PanelController
import io.github.capsopasme.assistant.panel.PanelHost
import io.github.capsopasme.assistant.panel.PermissionActivity
import android.view.ViewGroup
import android.os.Bundle
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import android.speech.RecognitionService
import android.speech.SpeechRecognizer
import android.util.Log
import io.github.capsopasme.assistant.system.AssistantSetup
import io.github.capsopasme.assistant.system.KeepAliveService
import io.github.capsopasme.assistant.util.Marks
import io.github.capsopasme.assistant.util.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The digital-assistant entry point. The system keeps this service bound while the app is the
 * assistant; it does nothing until invoked: no assist data, no screenshots (disabled below), no
 * work in the background. Everything runs in the app's one process, so settings are always current.
 */
class AssistVoiceService : VoiceInteractionService() {

    private var scope: CoroutineScope? = null

    override fun onReady() {
        super.onReady()
        // never collect the screen's view tree or a screenshot when invoked
        disabledShowContext = VoiceInteractionSession.SHOW_WITH_ASSIST or VoiceInteractionSession.SHOW_WITH_SCREENSHOT
        KeepAliveService.sync(this)
        // bound at boot: if HyperOS reset part of the setup (power key → 小爱), put it back
        if (Prefs.get(this).keepAssistant) {
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO).also { s ->
                s.launch {
                    delay(15_000) // let the boot settle first
                    runCatching { AssistantSetup.ensure(applicationContext) }
                        .onFailure { Log.w(TAG, "ensure failed", it) }
                }
            }
        }
    }

    override fun onShutdown() {
        scope?.cancel()
        scope = null
        super.onShutdown()
    }

    override fun onLaunchVoiceAssistFromKeyguard() {
        showSession(Bundle(), 0)
    }

    companion object {
        private const val TAG = "AssistVoiceService"
    }
}

class AssistSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession = AssistSession(this)
}

/**
 * The panel, shown in the session's own window. The system draws this window over whatever app is
 * in front, so nothing has to be started from the background: an activity launched from here was
 * held back by HyperOS until the app came to the front.
 */
class AssistSession(service: AssistSessionService) : VoiceInteractionSession(service), PanelHost {

    override val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var panel: PanelController? = null
    private var closing = false
    private var permissionPending = false

    override fun onCreateContentView(): View {
        val themed = ContextThemeWrapper(context, R.style.Theme_Assistant)
        val view = LayoutInflater.from(themed).inflate(R.layout.activity_panel, null)
        view.setBackgroundColor(0x52000000) // the dim behind the sheet
        panel = PanelController(themed, view, this)
        return view
    }

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        Marks.write(context, Marks.SHOW)
        // the window is visible now, so (re)starting the keep-alive service is allowed
        KeepAliveService.sync(context)
        window?.window?.let { w ->
            WindowCompat.setDecorFitsSystemWindows(w, false)
            w.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN)
            w.statusBarColor = Color.TRANSPARENT
            w.navigationBarColor = Color.TRANSPARENT
            w.isNavigationBarContrastEnforced = false
            w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        panel?.onShown()
    }

    override fun onHide() {
        super.onHide()
        // home pressed, screen off, another app opened…: the panel is transient
        if (!permissionPending) close()
    }

    override fun close() {
        if (closing) return
        closing = true
        panel?.dispose()
        scope.cancel()
        runCatching { finish() }
    }

    override fun onDestroy() {
        panel?.dispose()
        scope.cancel()
        super.onDestroy()
    }

    /** The permission dialog would sit under this window: step aside while it's up. */
    override suspend fun requestPermissions(permissions: Array<String>): Boolean {
        if (permissions.all { context.checkSelfPermission(it) == android.content.pm.PackageManager.PERMISSION_GRANTED }) return true
        permissionPending = true
        return try {
            scope.launch { kotlinx.coroutines.delay(150); setUiEnabled(false) }
            PermissionActivity.request(context, permissions)
        } finally {
            permissionPending = false
            if (!closing) {
                setUiEnabled(true)
                panel?.refocus()
            }
        }
    }

    override fun onHandleAssist(state: AssistState) {}
    override fun onHandleScreenshot(screenshot: android.graphics.Bitmap?) {}
}

/**
 * The assistant must declare a speech recognizer. Real recognition is left to Xiaomi's engine
 * (the setup points voice_recognition_service at it); this one only answers "unavailable".
 */
class StubRecognitionService : RecognitionService() {
    override fun onStartListening(recognizerIntent: Intent?, listener: Callback?) {
        runCatching { listener?.error(SpeechRecognizer.ERROR_CLIENT) }
    }

    override fun onCancel(listener: Callback?) {}
    override fun onStopListening(listener: Callback?) {}
}
