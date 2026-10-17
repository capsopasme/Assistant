package io.github.capsopasme.assistant.vis

import android.content.Intent
import android.content.res.Configuration
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.os.Bundle
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import android.speech.RecognitionService
import android.speech.SpeechRecognizer
import android.util.Log
import io.github.capsopasme.assistant.panel.PanelActivity
import io.github.capsopasme.assistant.system.AssistantSetup
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

/**
 * Opens the panel activity and goes away. Its own window stays hidden, except when the panel
 * doesn't come up: HyperOS blocks activity starts from the background unless the app is allowed
 * "后台弹出界面", and then this says so instead of the long press doing nothing.
 */
class AssistSession(service: AssistSessionService) : VoiceInteractionSession(service) {

    private val handler = Handler(Looper.getMainLooper())
    private var shownAt = 0L
    private var checks = 0

    override fun onCreate() {
        super.onCreate()
        setUiEnabled(false)
    }

    override fun onCreateContentView(): View = blockedView()

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        handler.removeCallbacksAndMessages(null)
        setUiEnabled(false)
        shownAt = System.currentTimeMillis()
        Marks.write(context, Marks.SHOW, shownAt)
        val intent = Intent(context, PanelActivity::class.java)
            .putExtra(PanelActivity.EXTRA_SOURCE, "assistant")
        try {
            startAssistantActivity(intent)
        } catch (e: Exception) {
            Log.w(TAG, "startAssistantActivity failed, falling back", e)
            runCatching { context.startActivity(Intent(intent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }
        checks = 0
        handler.postDelayed(::checkPanel, CHECK_INTERVAL_MS)
    }

    /** Hides as soon as the panel reports in; shows the explanation if it never does. */
    private fun checkPanel() {
        if (Marks.read(context, Marks.PANEL) >= shownAt) {
            hide()
            return
        }
        if (++checks * CHECK_INTERVAL_MS < PANEL_TIMEOUT_MS) {
            handler.postDelayed(::checkPanel, CHECK_INTERVAL_MS)
            return
        }
        Log.w(TAG, "panel did not start: blocked by the system?")
        Marks.write(context, Marks.BLOCKED, shownAt)
        setUiEnabled(true)
        handler.postDelayed({ hide() }, 10_000)
    }

    override fun onHide() {
        handler.removeCallbacksAndMessages(null)
        setUiEnabled(false)
        super.onHide()
    }

    private fun dp(v: Int) = (v * context.resources.displayMetrics.density).toInt()

    private fun blockedView(): View {
        val night = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        val fg = if (night) 0xFFE6E7EB.toInt() else 0xFF1B1C1F.toInt()
        val fg2 = if (night) 0xFFA3A6AE.toInt() else 0xFF5D6068.toInt()
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(20), dp(22), dp(18))
            background = GradientDrawable().apply {
                cornerRadius = dp(26).toFloat()
                setColor(if (night) 0xFF23252B.toInt() else 0xFFF4F5F8.toInt())
            }
            addView(TextView(context).apply {
                text = "助手被系统拦截了"
                setTextColor(fg)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
                setTypeface(typeface, Typeface.BOLD)
            })
            addView(TextView(context).apply {
                text = "HyperOS 没有允许「助手」从后台打开界面。\n\n" +
                    "有 root：打开「助手」重新点一次「一键配置」。\n" +
                    "或手动：设置 → 应用设置 → 应用管理 → 助手 → 其他权限，把「后台弹出界面」和「锁屏显示」设为允许。"
                setTextColor(fg)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                setLineSpacing(0f, 1.2f)
                setPadding(0, dp(10), 0, dp(10))
            })
            addView(TextView(context).apply {
                text = "轻触任意处关闭"
                setTextColor(fg2)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            })
        }
        return FrameLayout(context).apply {
            setBackgroundColor(0x55000000)
            isClickable = true
            setOnClickListener { hide() }
            addView(card, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM,
            ).apply { setMargins(dp(12), 0, dp(12), dp(56)) })
        }
    }

    override fun onHandleAssist(state: AssistState) {}
    override fun onHandleScreenshot(screenshot: android.graphics.Bitmap?) {}

    companion object {
        private const val TAG = "AssistSession"
        private const val CHECK_INTERVAL_MS = 250L
        private const val PANEL_TIMEOUT_MS = 3_000L
    }
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
