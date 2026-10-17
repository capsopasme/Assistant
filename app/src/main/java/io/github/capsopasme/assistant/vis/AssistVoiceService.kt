package io.github.capsopasme.assistant.vis

import android.content.Intent
import android.os.Bundle
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import android.speech.RecognitionService
import android.speech.SpeechRecognizer
import android.util.Log
import io.github.capsopasme.assistant.panel.PanelActivity
import io.github.capsopasme.assistant.system.AssistantSetup
import io.github.capsopasme.assistant.util.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The digital-assistant entry point. The system keeps this service bound while the app is the
 * assistant, so it lives in its own tiny process (":vis") and does nothing until invoked: no
 * assist data, no screenshots (disabled below), no work in the background.
 */
class AssistVoiceService : VoiceInteractionService() {

    private var scope: CoroutineScope? = null

    override fun onReady() {
        super.onReady()
        // never collect the screen's view tree or a screenshot when invoked
        disabledShowContext = VoiceInteractionSession.SHOW_WITH_ASSIST or VoiceInteractionSession.SHOW_WITH_SCREENSHOT
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

/** Has no window of its own: it opens the panel activity and goes away. */
class AssistSession(service: AssistSessionService) : VoiceInteractionSession(service) {

    override fun onCreate() {
        super.onCreate()
        setUiEnabled(false)
    }

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        val intent = Intent(context, PanelActivity::class.java)
            .putExtra(PanelActivity.EXTRA_SOURCE, "assistant")
        try {
            startAssistantActivity(intent)
        } catch (e: Exception) {
            Log.w("AssistSession", "startAssistantActivity failed, falling back", e)
            runCatching {
                context.startActivity(Intent(intent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }
        hide()
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
