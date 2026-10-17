package io.github.capsopasme.assistant.panel

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
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
import io.github.capsopasme.assistant.system.ImeSwitcher
import io.github.capsopasme.assistant.util.AppScope
import io.github.capsopasme.assistant.util.Marks
import io.github.capsopasme.assistant.util.Prefs
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
 * The assistant panel: a sheet at the bottom of the screen with a text field. Voice comes from the
 * Audio Input keyboard (switched to automatically, switched back on close). Local commands run
 * instantly; everything else goes to the selected model with the phone's tools.
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
    private val ime = ImeSwitcher(context)
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
    private var startedVoice = false
    private var suppressWatcher = false
    private var disposed = false
    private var maxScrollPx = Int.MAX_VALUE

    private val focusListener = ViewTreeObserver.OnWindowFocusChangeListener { hasFocus ->
        if (hasFocus && !startedVoice && !disposed) {
            startedVoice = true
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
        if (startedVoice) {
            input.requestFocus()
            showKeyboard()
        }
    }

    /** Releases everything; the keyboard is switched back even though the panel is going away. */
    fun dispose() {
        if (disposed) return
        disposed = true
        work?.cancel()
        autoSendJob?.cancel()
        autoCloseJob?.cancel()
        runCatching { root.viewTreeObserver.removeOnWindowFocusChangeListener(focusListener) }
        if (ime.hasPendingRestore()) {
            val app = context.applicationContext
            AppScope.launch { ImeSwitcher(app).restore() }
        }
    }

    /** After a permission dialog: back to typing. */
    fun refocus() {
        input.requestFocus()
        showKeyboard()
    }

    // --- input ----------------------------------------------------------------------------------

    private fun beginInput() {
        input.requestFocus()
        if (prefs.autoVoice && ime.voiceIme() != null) {
            host.scope.launch {
                ime.toVoice()
                updateMicState()
                showKeyboard()
            }
        } else {
            showKeyboard()
        }
    }

    private fun showKeyboard() {
        input.post {
            if (disposed) return@post
            input.requestFocus()
            context.getSystemService(InputMethodManager::class.java)?.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
            ViewCompat.getWindowInsetsController(input)?.show(WindowInsetsCompat.Type.ime())
        }
    }

    private fun toggleVoice() {
        autoCloseJob?.cancel()
        if (ime.voiceIme() == null) {
            val msg = if (ime.audioInputInstalled()) "请先在系统设置里启用「离线语音输入」输入法"
            else "没有找到「离线语音输入」（Audio Input）"
            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
            return
        }
        host.scope.launch {
            val ok = if (ime.isVoiceActive()) ime.toKeyboard() else ime.toVoice()
            if (!ok) Toast.makeText(context, "切换输入法失败：需要 root 或在设置里完成一键配置", Toast.LENGTH_SHORT).show()
            updateMicState()
            showKeyboard()
        }
    }

    private fun updateMicState() {
        val voice = ime.isVoiceActive()
        mic.setImageResource(if (voice) R.drawable.ic_keyboard else R.drawable.ic_mic)
        mic.contentDescription = if (voice) "切换到键盘" else "语音输入"
    }

    private fun onInputChanged() {
        if (suppressWatcher || disposed) return
        autoCloseJob?.cancel()
        autoSendJob?.cancel()
        val text = input.text?.toString().orEmpty()
        if (text.isBlank() || work?.isActive == true || !prefs.autoSend || !ime.isVoiceActive()) return
        // Audio Input commits a whole sentence after a pause; send once it stays unchanged a moment
        autoSendJob = host.scope.launch {
            delay(AUTO_SEND_DELAY_MS)
            submit()
        }
    }

    // --- requests -------------------------------------------------------------------------------

    private fun submit() {
        autoSendJob?.cancel()
        autoCloseJob?.cancel()
        val text = input.text?.toString()?.trim().orEmpty()
        if (text.isEmpty() || work?.isActive == true || disposed) return
        suppressWatcher = true
        input.setText("")
        suppressWatcher = false
        addBubble(text, user = true)
        work = host.scope.launch { handle(text) }
    }

    private suspend fun handle(text: String) {
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
            else -> {} // an answer or a problem: stay for follow-ups
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
        private const val AUTO_SEND_DELAY_MS = 1100L
    }
}
