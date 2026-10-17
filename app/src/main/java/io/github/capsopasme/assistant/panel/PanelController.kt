package io.github.capsopasme.assistant.panel

import android.content.Context
import android.content.Intent
import android.Manifest
import android.content.res.ColorStateList
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.animation.DecelerateInterpolator
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.google.android.material.color.MaterialColors
import io.github.capsopasme.assistant.R
import io.github.capsopasme.assistant.action.ActionExecutor
import io.github.capsopasme.assistant.action.ActionResult
import io.github.capsopasme.assistant.action.AppIndex
import io.github.capsopasme.assistant.action.PermissionGate
import io.github.capsopasme.assistant.llm.Agent
import io.github.capsopasme.assistant.llm.LlmException
import io.github.capsopasme.assistant.parse.LocalParser
import io.github.capsopasme.assistant.settings.SettingsActivity
import io.github.capsopasme.assistant.system.KeepAliveService
import io.github.capsopasme.assistant.util.Marks
import io.github.capsopasme.assistant.util.Prefs
import io.github.capsopasme.assistant.voice.VoiceInput
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Calendar

/** What the panel needs from whatever shows it: the assistant session window, or an activity. */
interface PanelHost {
    val scope: CoroutineScope
    /** Closes the panel. */
    fun close()
    /** Asks the user for runtime permissions. */
    suspend fun requestPermissions(permissions: Array<String>): Boolean
}

/**
 * The assistant panel: a sheet at the bottom of the screen with a text field and a microphone
 * button. Speech is recognized by Audio Input's offline recognizer ([VoiceInput]) with no keyboard
 * on screen; tapping the text field brings up the normal keyboard for typing instead. Local
 * commands run instantly; everything else goes to the selected model with the phone's tools.
 *
 * Normally shown in the assistant session's own window, which the system draws over any app (no
 * activity start, so no background-start restrictions); [PanelActivity] hosts it otherwise.
 */
class PanelController(
    private val context: Context,
    val root: View,
    private val host: PanelHost,
) {
    private val prefs = Prefs.get(context)
    private val executor = ActionExecutor(context, PermissionGate { host.requestPermissions(it) })
    private val agent = Agent(context, executor)

    private val card: View = root.findViewById(R.id.card)
    private val scroll: ScrollView = root.findViewById(R.id.scroll)
    private val messages: LinearLayout = root.findViewById(R.id.messages)
    private val statusRow: View = root.findViewById(R.id.statusRow)
    private val status: TextView = root.findViewById(R.id.status)
    private val input: EditText = root.findViewById(R.id.input)
    private val mic: ImageButton = root.findViewById(R.id.mic)
    private val title: TextView = root.findViewById(R.id.title)

    private var work: Job? = null
    private var autoSendJob: Job? = null
    private var autoCloseJob: Job? = null
    private var startedInput = false
    private var suppressWatcher = false
    private var disposed = false
    private var maxScrollPx = Int.MAX_VALUE

    /** text recognized in the current utterance, shown in the field with the live partial after it */
    private var heard = ""
    private var retained = true
    private var lastRequestByVoice = false
    private var keyboardMode = false
    private var focusRequest: AudioFocusRequest? = null
    private val partialColor by lazy { MaterialColors.getColor(input, com.google.android.material.R.attr.colorOnSurfaceVariant) }

    private val focusListener = ViewTreeObserver.OnWindowFocusChangeListener { hasFocus ->
        if (hasFocus && !startedInput && !disposed) {
            startedInput = true
            beginInput()
        }
    }

    init {
        root.setOnClickListener { host.close() }
        card.setOnClickListener { /* swallow taps on the sheet itself */ }
        root.findViewById<View>(R.id.settings).setOnClickListener {
            runCatching {
                context.startActivity(Intent(context, SettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            host.close()
        }
        root.findViewById<View>(R.id.send).setOnClickListener { submit() }
        mic.setOnClickListener { toggleVoice() }
        // tapping the text field means typing: stop listening, bring up the keyboard
        input.showSoftInputOnFocus = false
        input.setOnClickListener { if (!keyboardMode) switchToKeyboard() }
        input.setOnEditorActionListener { _, actionId, event ->
            val enter = event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN
            if (actionId == EditorInfo.IME_ACTION_SEND || actionId == EditorInfo.IME_ACTION_DONE || enter) {
                submit(); true
            } else false
        }
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) = onInputChanged()
        })
        input.setHorizontallyScrolling(false)
        input.maxLines = 4
        // multi-line display, but the keyboard's action key sends
        input.setRawInputType(InputType.TYPE_CLASS_TEXT)

        // icons tinted here: the session window inflates without AppCompat's view inflater
        mic.imageTintList = ColorStateList.valueOf(MaterialColors.getColor(mic, com.google.android.material.R.attr.colorOnSecondaryContainer))
        root.findViewById<ImageButton>(R.id.send).let {
            it.imageTintList = ColorStateList.valueOf(MaterialColors.getColor(it, com.google.android.material.R.attr.colorOnPrimary))
        }
        root.findViewById<ImageButton>(R.id.settings).let {
            it.imageTintList = ColorStateList.valueOf(MaterialColors.getColor(it, com.google.android.material.R.attr.colorOnSurfaceVariant))
        }

        // keyboard and navigation bar: the sheet sits right above whichever is taller
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bottom = maxOf(
                insets.getInsets(WindowInsetsCompat.Type.ime()).bottom,
                insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom,
            )
            val top = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            card.updatePadding(bottom = dp(12) + bottom)
            // keep the conversation within the space above the keyboard
            maxScrollPx = ((root.height.takeIf { it > 0 } ?: context.resources.displayMetrics.heightPixels) - bottom - top - dp(140))
                .coerceAtLeast(dp(120))
            applyScrollMax()
            insets
        }
        messages.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> applyScrollMax() }
        root.viewTreeObserver.addOnWindowFocusChangeListener(focusListener)

        updateTitle()
        updateMicState()
        // connect to the recognizer early, so the model is (or gets) loaded before anyone speaks
        if (VoiceInput.availability(context) == VoiceInput.Availability.OK) {
            VoiceInput.retain(context)
            VoiceInput.preload()
        } else retained = false
        // build the app list in the background so "打开…" is instant
        host.scope.launch(Dispatchers.Default) { runCatching { AppIndex.apps(context.applicationContext) } }
    }

    /** Called every time the panel is (re)shown. */
    fun onShown() {
        Marks.write(context, Marks.PANEL)
        autoCloseJob?.cancel()
        updateTitle()
        card.translationY = dp(48).toFloat()
        card.alpha = 0f
        card.animate().translationY(0f).alpha(1f).setDuration(200).setInterpolator(DecelerateInterpolator(1.6f)).start()
        if (startedInput) beginInput()
    }

    /** Releases everything; the keyboard is switched back even though the panel is going away. */
    fun dispose() {
        if (disposed) return
        disposed = true
        work?.cancel()
        autoSendJob?.cancel()
        autoCloseJob?.cancel()
        runCatching { root.viewTreeObserver.removeOnWindowFocusChangeListener(focusListener) }
        VoiceInput.cancel()
        listeningEnded()
        abandonAudioFocus()
        // without a resident model, keep the recognizer around briefly for a quick follow-up
        if (retained) VoiceInput.release(if (prefs.voiceResident) 0 else 60_000)
        retained = false
    }

    /** After a permission dialog: back to typing. */
    fun refocus() {
        input.requestFocus()
        if (keyboardMode) showKeyboard()
    }

    // --- input ----------------------------------------------------------------------------------

    private fun voiceUsable() = VoiceInput.availability(context) == VoiceInput.Availability.OK

    /** On open: listen right away (no keyboard), or show the keyboard. */
    private fun beginInput() {
        input.requestFocus()
        if (prefs.autoVoice && voiceUsable() && !keyboardMode) startListening() else switchToKeyboard()
    }

    private fun switchToKeyboard() {
        if (VoiceInput.isListening) VoiceInput.stop()
        keyboardMode = true
        input.showSoftInputOnFocus = true
        showKeyboard()
        updateMicState()
    }

    private fun showKeyboard() {
        input.post {
            if (disposed) return@post
            input.requestFocus()
            context.getSystemService(InputMethodManager::class.java)?.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
            ViewCompat.getWindowInsetsController(input)?.show(WindowInsetsCompat.Type.ime())
        }
    }

    private fun hideKeyboard() {
        ViewCompat.getWindowInsetsController(input)?.hide(WindowInsetsCompat.Type.ime())
        context.getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(input.windowToken, 0)
    }

    private fun toggleVoice() {
        autoCloseJob?.cancel()
        if (VoiceInput.isListening) {
            // done talking: the sentence in progress is still recognized
            VoiceInput.stop()
            return
        }
        when (VoiceInput.availability(context)) {
            VoiceInput.Availability.NOT_INSTALLED -> {
                Toast.makeText(context, "需要安装「离线语音输入」（Audio Input）", Toast.LENGTH_SHORT).show(); return
            }
            VoiceInput.Availability.TOO_OLD -> {
                Toast.makeText(context, "请把「离线语音输入」更新到最新版", Toast.LENGTH_SHORT).show(); return
            }
            VoiceInput.Availability.OK -> {}
        }
        keyboardMode = false
        input.showSoftInputOnFocus = false
        hideKeyboard()
        startListening()
    }

    private fun startListening() {
        if (disposed || work?.isActive == true) return
        host.scope.launch {
            if (!VoiceInput.hasMicPermission(context) && !host.requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO))) {
                setStatus(null)
                addBubble("没有麦克风权限，无法语音输入（可在助手里点一键配置授予）", error = true)
                switchToKeyboard()
                return@launch
            }
            if (disposed) return@launch
            if (!retained) { VoiceInput.retain(context); retained = true }
            heard = ""
            requestAudioFocus()
            KeepAliveService.setMicrophone(true)
            setStatus("正在连接语音识别…")
            val ok = VoiceInput.start(context, voiceListener, idleStopMs = IDLE_STOP_MS)
            if (ok) updateMicState() else listeningEnded()
        }
    }

    private val voiceListener = object : VoiceInput.Listener {
        override fun onLoading() = setStatus("正在加载语音模型…")
        override fun onListening() = setStatus("正在听…")

        override fun onLevel(level: Float) {
            val scale = 1f + 0.22f * level
            mic.animate().scaleX(scale).scaleY(scale).setDuration(90).start()
        }

        override fun onPartial(text: String) {
            autoSendJob?.cancel()
            autoCloseJob?.cancel()
            showHeard(text)
        }

        override fun onFinal(text: String) {
            heard += text
            showHeard("")
            // a pause after a whole sentence: send it unless more speech follows
            if (prefs.autoSend && !keyboardMode) {
                autoSendJob?.cancel()
                autoSendJob = host.scope.launch {
                    delay(AUTO_SEND_DELAY_MS)
                    submit()
                }
            }
        }

        override fun onDone(autoStopped: Boolean) {
            listeningEnded()
            setStatus(null)
            if (prefs.autoSend && !keyboardMode && heard.isNotBlank() && work?.isActive != true) submit()
            else if (autoStopped && input.text.isNullOrBlank() && messages.childCount == 0) host.close()
        }

        override fun onError(message: String) {
            listeningEnded()
            setStatus(null)
            addBubble("语音识别：$message", error = true)
        }
    }

    /** Recognized text plus the live partial (dimmed) in the field. */
    private fun showHeard(partial: String) {
        suppressWatcher = true
        val sb = SpannableStringBuilder(heard)
        if (partial.isNotEmpty()) {
            val start = sb.length
            sb.append(partial)
            sb.setSpan(ForegroundColorSpan(partialColor), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        input.setText(sb)
        input.setSelection(input.length())
        suppressWatcher = false
    }

    private fun listeningEnded() {
        mic.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
        KeepAliveService.setMicrophone(false)
        updateMicState()
    }

    private fun requestAudioFocus() {
        if (focusRequest != null) return
        val am = context.getSystemService(AudioManager::class.java) ?: return
        // music pauses while listening (better recognition) and resumes when the request is done
        val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
            )
            .setOnAudioFocusChangeListener { }
            .build()
        if (am.requestAudioFocus(req) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) focusRequest = req
    }

    private fun abandonAudioFocus() {
        val req = focusRequest ?: return
        focusRequest = null
        context.getSystemService(AudioManager::class.java)?.abandonAudioFocusRequest(req)
    }

    private fun updateMicState() {
        val listening = VoiceInput.isListening
        mic.setImageResource(if (listening) R.drawable.ic_stop else R.drawable.ic_mic)
        mic.contentDescription = if (listening) "停止听写" else "语音输入"
    }

    private fun onInputChanged() {
        if (suppressWatcher || disposed) return
        // the user is typing / editing: don't close or send on their behalf
        autoCloseJob?.cancel()
        autoSendJob?.cancel()
    }

    // --- requests -------------------------------------------------------------------------------

    private fun submit() {
        autoSendJob?.cancel()
        autoCloseJob?.cancel()
        val text = input.text?.toString()?.trim().orEmpty()
        if (text.isEmpty() || work?.isActive == true || disposed) return
        lastRequestByVoice = VoiceInput.isListening || heard.isNotEmpty()
        if (VoiceInput.isListening) VoiceInput.cancel()
        listeningEnded()
        heard = ""
        suppressWatcher = true
        input.setText("")
        suppressWatcher = false
        addBubble(text, user = true)
        work = host.scope.launch { handle(text) }
    }

    private suspend fun handle(text: String) {
        try {
            handleRequest(text)
        } finally {
            // after the action: a "暂停" sent while we held focus keeps the music paused
            abandonAudioFocus()
        }
    }

    private suspend fun handleRequest(text: String) {
        val now = Calendar.getInstance().let { it.get(Calendar.HOUR_OF_DAY) * 60 + it.get(Calendar.MINUTE) }
        val local = LocalParser.parse(text, now)
        if (local != null) {
            setStatus("正在执行…")
            val result = executor.execute(local)
            setStatus(null)
            if (!result.fallbackToModel) {
                finishWith(listOf(result), null)
                return
            }
        }
        askModel(text)
    }

    private suspend fun askModel(text: String) {
        val provider = prefs.provider
        if (prefs.apiKey(provider).isBlank()) {
            addBubble("这句需要大模型来处理，但还没有填写 ${provider.label} 的 API Key。点右上角 ⚙ 去设置。", error = true)
            return
        }
        if (!online()) {
            addBubble("没有网络连接。", error = true)
            return
        }
        setStatus("思考中…")
        try {
            val outcome = agent.run(text) { step -> root.post { setStatus("正在$step…") } }
            setStatus(null)
            finishWith(outcome.results, outcome.text)
        } catch (e: CancellationException) {
            throw e
        } catch (e: LlmException) {
            setStatus(null)
            addBubble(e.message ?: "请求失败", error = true)
        } catch (e: Exception) {
            setStatus(null)
            addBubble("出错了：${e.message ?: e.javaClass.simpleName}", error = true)
        }
    }

    /** Shows what happened and decides whether the panel stays open. */
    private fun finishWith(results: List<ActionResult>, answer: String?) {
        val leftApp = results.any { it.leftApp }
        val allOk = results.isNotEmpty() && results.all { it.ok }
        if (answer != null) addBubble(answer)
        else for (r in results) addBubble(r.message, error = !r.ok)
        when {
            // another app is in front now: the panel's job is done
            leftApp && allOk -> host.close()
            // something was switched / set in place: show the result briefly, then get out of the way
            allOk && answer == null -> scheduleClose(1600)
            // an answer or a problem: stay, and listen for a follow-up if the request was spoken
            // (the recognizer ends the session by itself after a few silent seconds)
            else -> if (lastRequestByVoice && !keyboardMode && prefs.autoVoice) startListening()
        }
    }

    private fun scheduleClose(ms: Long) {
        autoCloseJob?.cancel()
        autoCloseJob = host.scope.launch {
            delay(ms)
            if (input.text.isNullOrBlank()) host.close()
        }
    }

    private fun online(): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return true
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    // --- views ----------------------------------------------------------------------------------

    private fun updateTitle() {
        val p = prefs.provider
        title.text = "助手 · ${p.label} ${prefs.model(p)}"
    }

    private fun setStatus(text: String?) {
        statusRow.visibility = if (text == null) View.GONE else View.VISIBLE
        status.text = text.orEmpty()
    }

    private fun addBubble(text: String, user: Boolean = false, error: Boolean = false) {
        if (disposed) return
        scroll.visibility = View.VISIBLE
        val tv = TextView(context).apply {
            this.text = text
            setTextSize(TypedValue.COMPLEX_UNIT_SP, if (user) 15f else 16f)
            setLineSpacing(0f, 1.15f)
            val color = when {
                error -> MaterialColors.getColor(this, androidx.appcompat.R.attr.colorError)
                user -> MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnPrimaryContainer)
                else -> MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurface)
            }
            setTextColor(color)
            if (user) {
                setBackgroundResource(R.drawable.bubble_user)
                setPadding(dp(14), dp(8), dp(14), dp(8))
            } else {
                setTextIsSelectable(true)
                setPadding(dp(2), dp(6), dp(2), dp(6))
            }
        }
        val lp = LinearLayout.LayoutParams(
            if (user) ViewGroup.LayoutParams.WRAP_CONTENT else ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply {
            gravity = if (user) Gravity.END else Gravity.START
            topMargin = dp(6)
            if (user) marginStart = dp(48)
        }
        messages.addView(tv, lp)
        // keep at most a dozen bubbles; the panel is for the current exchange, not a chat log
        while (messages.childCount > 12) messages.removeViewAt(0)
        messages.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun applyScrollMax() {
        val want = if (messages.height > maxScrollPx) maxScrollPx else ViewGroup.LayoutParams.WRAP_CONTENT
        val lp = scroll.layoutParams
        if (lp.height != want) {
            lp.height = want
            scroll.post { scroll.layoutParams = lp }
        }
    }

    private fun dp(v: Int) = (v * context.resources.displayMetrics.density).toInt()

    companion object {
        private const val AUTO_SEND_DELAY_MS = 900L
        private const val IDLE_STOP_MS = 8_000
    }
}
