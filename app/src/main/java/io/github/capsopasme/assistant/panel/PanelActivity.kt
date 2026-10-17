package io.github.capsopasme.assistant.panel

import android.app.KeyguardManager
import android.content.Intent
import android.content.res.Configuration
import android.text.InputType
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Calendar

/**
 * The assistant panel: a sheet at the bottom of the screen with a text field. Voice comes from the
 * Audio Input keyboard (switched to automatically, switched back on close). Local commands run
 * instantly; everything else goes to the selected model with the phone's tools.
 *
 * The panel is transient: leaving it (home, another app opening, screen off) closes it, and
 * nothing of it keeps running afterwards.
 */
class PanelActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private lateinit var ime: ImeSwitcher
    private lateinit var executor: ActionExecutor
    private lateinit var agent: Agent

    private lateinit var card: View
    private lateinit var scroll: ScrollView
    private lateinit var messages: LinearLayout
    private lateinit var statusRow: View
    private lateinit var status: TextView
    private lateinit var input: EditText
    private lateinit var mic: ImageButton
    private lateinit var title: TextView

    private var work: Job? = null
    private var autoSendJob: Job? = null
    private var autoCloseJob: Job? = null
    private var imeShownOnce = false
    private var requestingPermission = false
    private var waitingForUnlock = false
    private var suppressWatcher = false

    private var permissionWaiter: CompletableDeferred<Boolean>? = null
    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        requestingPermission = false
        permissionWaiter?.complete(res.values.all { it })
        permissionWaiter = null
    }

    private val gate = PermissionGate { perms ->
        if (perms.all { ContextCompat.checkSelfPermission(this, it) == android.content.pm.PackageManager.PERMISSION_GRANTED }) {
            true
        } else {
            val waiter = CompletableDeferred<Boolean>()
            permissionWaiter?.complete(false)
            permissionWaiter = waiter
            requestingPermission = true
            permissionLauncher.launch(perms)
            waiter.await()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Marks.write(this, Marks.PANEL) // tells the assistant session that the panel came up
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setShowWhenLocked(true)
        setContentView(R.layout.activity_panel)

        prefs = Prefs.get(this)
        ime = ImeSwitcher(this)
        executor = ActionExecutor(this, gate)
        agent = Agent(this, executor)

        val root = findViewById<View>(R.id.root)
        card = findViewById(R.id.card)
        scroll = findViewById(R.id.scroll)
        messages = findViewById(R.id.messages)
        statusRow = findViewById(R.id.statusRow)
        status = findViewById(R.id.status)
        input = findViewById(R.id.input)
        mic = findViewById(R.id.mic)
        title = findViewById(R.id.title)

        root.setOnClickListener { close() }
        findViewById<View>(R.id.settings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            close()
        }
        findViewById<View>(R.id.send).setOnClickListener { submit() }
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

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = close()
        })

        // keyboard and navigation bar: the sheet sits right above whichever is taller
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bottom = maxOf(
                insets.getInsets(WindowInsetsCompat.Type.ime()).bottom,
                insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom,
            )
            val top = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            card.updatePadding(bottom = dp(12) + bottom)
            // keep the conversation within the space above the keyboard
            maxScrollPx = ((root.height.takeIf { it > 0 } ?: resources.displayMetrics.heightPixels) - bottom - top - dp(140))
                .coerceAtLeast(dp(120))
            applyScrollMax()
            insets
        }
        messages.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> applyScrollMax() }
        input.setHorizontallyScrolling(false)
        input.maxLines = 4
        // multi-line display, but the keyboard's action key sends
        input.setRawInputType(InputType.TYPE_CLASS_TEXT)

        updateTitle()
        updateMicState()
        // build the app list in the background so "打开…" is instant
        lifecycleScope.launch(Dispatchers.Default) { runCatching { AppIndex.apps(applicationContext) } }

        // invoked on the lock screen: ask for the unlock first; give up if the user doesn't
        val km = getSystemService(KeyguardManager::class.java)
        if (km?.isKeyguardLocked == true) {
            waitingForUnlock = true
            km.requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
                override fun onDismissSucceeded() { waitingForUnlock = false }
                override fun onDismissCancelled() { waitingForUnlock = false; close(animate = false) }
                override fun onDismissError() { waitingForUnlock = false; close(animate = false) }
            })
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        Marks.write(this, Marks.PANEL)
        // invoked again while open: start a fresh request
        autoCloseJob?.cancel()
        input.requestFocus()
        showKeyboard()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && !imeShownOnce) {
            imeShownOnce = true
            input.requestFocus()
            if (prefs.autoVoice && ime.voiceIme() != null) {
                lifecycleScope.launch {
                    ime.toVoice()
                    updateMicState()
                    showKeyboard()
                }
            } else {
                showKeyboard()
            }
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // e.g. dark mode was just switched by a command: the old colours stay until the panel closes
    }

    override fun onStop() {
        super.onStop()
        // the bouncer showing over us, or a permission dialog: not a reason to close
        if (isChangingConfigurations || requestingPermission || waitingForUnlock) return
        close(animate = false)
    }

    override fun onDestroy() {
        super.onDestroy()
        permissionWaiter?.complete(false)
        restoreKeyboardDetached()
    }

    // --- input ----------------------------------------------------------------------------------

    private fun showKeyboard() {
        input.post {
            WindowCompat.getInsetsController(window, input).show(WindowInsetsCompat.Type.ime())
        }
    }

    private fun toggleVoice() {
        autoCloseJob?.cancel()
        if (ime.voiceIme() == null) {
            val msg = if (ime.audioInputInstalled()) "请先在系统设置里启用「离线语音输入」输入法"
            else "没有找到「离线语音输入」（Audio Input）"
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
            return
        }
        lifecycleScope.launch {
            val ok = if (ime.isVoiceActive()) ime.toKeyboard() else ime.toVoice()
            if (!ok) Toast.makeText(this@PanelActivity, "切换输入法失败：需要 root 或在设置里完成一键配置", Toast.LENGTH_SHORT).show()
            updateMicState()
            input.requestFocus()
            showKeyboard()
        }
    }

    private fun updateMicState() {
        val voice = ime.isVoiceActive()
        mic.setImageResource(if (voice) R.drawable.ic_keyboard else R.drawable.ic_mic)
        mic.contentDescription = if (voice) "切换到键盘" else "语音输入"
    }

    private fun onInputChanged() {
        if (suppressWatcher) return
        autoCloseJob?.cancel()
        autoSendJob?.cancel()
        val text = input.text?.toString().orEmpty()
        if (text.isBlank() || work?.isActive == true || !prefs.autoSend || !ime.isVoiceActive()) return
        // Audio Input commits a whole sentence after a pause; send once it stays unchanged a moment
        autoSendJob = lifecycleScope.launch {
            delay(AUTO_SEND_DELAY_MS)
            submit()
        }
    }

    // --- requests -------------------------------------------------------------------------------

    private fun submit() {
        autoSendJob?.cancel()
        autoCloseJob?.cancel()
        val text = input.text?.toString()?.trim().orEmpty()
        if (text.isEmpty()) return
        if (work?.isActive == true) return
        suppressWatcher = true
        input.setText("")
        suppressWatcher = false
        addBubble(text, user = true)
        work = lifecycleScope.launch { handle(text) }
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
            val outcome = agent.run(text) { step -> runOnUiThread { setStatus("正在$step…") } }
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
            leftApp && allOk -> close(animate = false)
            // something was switched / set in place: show the result briefly, then get out of the way
            allOk && answer == null -> scheduleClose(1600)
            else -> {} // an answer or a problem: stay for follow-ups
        }
    }

    private fun scheduleClose(ms: Long) {
        autoCloseJob?.cancel()
        autoCloseJob = lifecycleScope.launch {
            delay(ms)
            if (input.text.isNullOrBlank()) close()
        }
    }

    private fun online(): Boolean {
        val cm = getSystemService(ConnectivityManager::class.java) ?: return true
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
        scroll.visibility = View.VISIBLE
        val tv = TextView(this).apply {
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

    private var maxScrollPx = Int.MAX_VALUE

    private fun applyScrollMax() {
        val want = if (messages.height > maxScrollPx) maxScrollPx else ViewGroup.LayoutParams.WRAP_CONTENT
        val lp = scroll.layoutParams
        if (lp.height != want) {
            lp.height = want
            scroll.post { scroll.layoutParams = lp }
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    // --- closing --------------------------------------------------------------------------------

    private fun close(animate: Boolean = true) {
        if (isFinishing) return
        work?.cancel()
        autoSendJob?.cancel()
        autoCloseJob?.cancel()
        restoreKeyboardDetached()
        finish()
        if (!animate) {
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
        }
    }

    /** Switches the keyboard back even though the activity is going away. */
    private fun restoreKeyboardDetached() {
        if (!ime.hasPendingRestore()) return
        val app = applicationContext
        AppScope.launch { ImeSwitcher(app).restore() }
    }

    companion object {
        const val EXTRA_SOURCE = "source"
        private const val AUTO_SEND_DELAY_MS = 1100L
    }
}
