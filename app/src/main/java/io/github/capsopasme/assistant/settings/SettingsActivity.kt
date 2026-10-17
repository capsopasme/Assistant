package io.github.capsopasme.assistant.settings

import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.text.InputType
import android.view.inputmethod.InputMethodManager
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.github.capsopasme.assistant.BuildConfig
import io.github.capsopasme.assistant.R
import io.github.capsopasme.assistant.llm.LlmClient
import io.github.capsopasme.assistant.llm.LlmException
import io.github.capsopasme.assistant.llm.Provider
import io.github.capsopasme.assistant.panel.PanelActivity
import io.github.capsopasme.assistant.system.AssistantSetup
import io.github.capsopasme.assistant.system.ImeSwitcher
import io.github.capsopasme.assistant.system.RootShell
import io.github.capsopasme.assistant.util.Marks
import io.github.capsopasme.assistant.util.Prefs
import io.github.capsopasme.assistant.util.SecretDataStore
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

class SettingsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_settings)
        findViewById<MaterialToolbar>(R.id.toolbar).title = getString(R.string.app_name)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.settingsRoot)) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            v.updatePadding(top = bars.top, left = bars.left, right = bars.right, bottom = bars.bottom)
            insets
        }
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction().replace(R.id.settingsContainer, SettingsFragment()).commit()
        }
    }
}

class SettingsFragment : PreferenceFragmentCompat() {

    private lateinit var prefs: Prefs
    private var refreshJob: Job? = null

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        prefs = Prefs.get(requireContext())
        setPreferencesFromResource(R.xml.preferences, rootKey)

        for (p in Provider.entries) setupProvider(p)

        click("setup_apply") { applySetup() }
        click("status_assistant") { openFirst(AssistantSetup.assistantSettingsIntent(), AssistantSetup.manageDefaultAppsIntent()) }
        click("status_power") { applySetup() }
        click("status_root") { refreshStatus() }
        click("status_bgstart") { applySetup() }
        click("status_last") { refreshStatus() }
        click("status_voice") {
            openFirst(Intent(android.provider.Settings.ACTION_INPUT_METHOD_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        click("autostart") { openFirst(*AssistantSetup.autostartIntents(requireContext()).toTypedArray()) }
        click("open_panel") { startActivity(Intent(requireContext(), PanelActivity::class.java)) }
        click("test_model") { testModel() }

        findPreference<Preference>("version")?.summary = BuildConfig.VERSION_NAME
        findPreference<EditTextPreference>(Prefs.KEY_ALIASES)?.apply {
            setOnBindEditTextListener { it.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE; it.minLines = 4 }
            summaryProvider = Preference.SummaryProvider<EditTextPreference> { p ->
                val n = p.text.orEmpty().lines().count { it.contains('=') || it.contains('＝') }
                if (n == 0) "例如「书架=Anx Reader」，让助手认识你自己的叫法" else "已设置 $n 个"
            }
        }
        findPreference<ListPreference>(Prefs.KEY_PROVIDER)?.setOnPreferenceChangeListener { _, _ ->
            view?.post { highlightProvider() }
            true
        }
        highlightProvider()
    }

    override fun onResume() {
        super.onResume()
        fillVoiceImes()
        refreshStatus()
    }

    private fun click(key: String, action: () -> Unit) {
        findPreference<Preference>(key)?.setOnPreferenceClickListener { action(); true }
    }

    private fun openFirst(vararg intents: Intent) {
        for (i in intents) {
            if (runCatching { startActivity(i) }.isSuccess) return
        }
    }

    private fun highlightProvider() {
        val current = prefs.provider
        for (p in Provider.entries) {
            findPreference<Preference>("cat_${p.id}")?.title = if (p == current) "${p.label}（使用中）" else p.label
        }
    }

    private fun setupProvider(p: Provider) {
        findPreference<EditTextPreference>(p.keyPref)?.apply {
            preferenceDataStore = SecretDataStore(prefs.sp)
            dialogMessage = "在 ${p.keyHelpUrl} 创建。保存在本机，经系统密钥库加密。"
            setOnBindEditTextListener {
                it.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                it.setSingleLine()
            }
            summaryProvider = Preference.SummaryProvider<EditTextPreference> {
                val key = prefs.apiKey(p)
                if (key.isEmpty()) "未填写" else "已填写（…${key.takeLast(4)}）"
            }
        }
        findPreference<EditTextPreference>(p.modelPref)?.apply {
            setOnBindEditTextListener { it.setSingleLine(); if (it.text.isEmpty()) it.hint = p.defaultModel }
            summaryProvider = Preference.SummaryProvider<EditTextPreference> { pref ->
                pref.text?.trim().takeUnless { it.isNullOrEmpty() } ?: "默认：${p.defaultModel}"
            }
        }
        findPreference<EditTextPreference>(p.urlPref)?.apply {
            setOnBindEditTextListener {
                it.setSingleLine()
                it.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
                if (it.text.isEmpty()) it.hint = p.defaultBaseUrl
            }
            summaryProvider = Preference.SummaryProvider<EditTextPreference> { pref ->
                pref.text?.trim().takeUnless { it.isNullOrEmpty() } ?: "默认：${p.defaultBaseUrl}"
            }
        }
        click("pick_${p.id}") { pickModel(p) }
    }

    private fun client(p: Provider) = LlmClient(p, prefs.apiKey(p), prefs.model(p), prefs.baseUrl(p))

    private fun pickModel(p: Provider) {
        val pref = findPreference<Preference>("pick_${p.id}")
        pref?.summary = "正在获取…"
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val models = client(p).listModels()
                pref?.summary = null
                if (models.isEmpty()) {
                    message("${p.label}", "服务器没有返回模型列表，请手动填写模型名。")
                    return@launch
                }
                val current = prefs.model(p)
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle("选择 ${p.label} 模型")
                    .setSingleChoiceItems(models.toTypedArray<CharSequence>(), models.indexOf(current)) { d, which ->
                        findPreference<EditTextPreference>(p.modelPref)?.text = models[which]
                        d.dismiss()
                        if (p == prefs.provider) refreshStatus()
                    }
                    .setNegativeButton("取消", null)
                    .show()
            } catch (e: LlmException) {
                pref?.summary = null
                message(p.label, e.message ?: "获取失败")
            }
        }
    }

    private fun testModel() {
        val p = prefs.provider
        val pref = findPreference<Preference>("test_model")
        pref?.summary = "正在测试 ${p.label} · ${prefs.model(p)} …"
        viewLifecycleOwner.lifecycleScope.launch {
            val start = SystemClock.elapsedRealtime()
            val text = try {
                val reply = client(p).chat(
                    JSONArray().put(JSONObject().put("role", "user").put("content", "用一句话介绍你自己（不超过20个字）")),
                    null,
                )
                "✓ ${SystemClock.elapsedRealtime() - start} ms：${reply.text ?: "（空回复）"}"
            } catch (e: LlmException) {
                "✗ ${e.message}"
            }
            pref?.summary = text
        }
    }

    private fun fillVoiceImes() {
        val list = findPreference<ListPreference>(Prefs.KEY_VOICE_IME) ?: return
        val imm = requireContext().getSystemService(InputMethodManager::class.java)
        val pm = requireContext().packageManager
        val imes = runCatching { imm.enabledInputMethodList }.getOrDefault(emptyList())
        list.entries = imes.map<_, CharSequence> { it.loadLabel(pm).toString() }.toTypedArray()
        list.entryValues = imes.map<_, CharSequence> { it.id }.toTypedArray()
        val effective = ImeSwitcher(requireContext()).voiceIme()
        list.summary = imes.firstOrNull { it.id == effective }?.loadLabel(pm)?.toString()
            ?: "未找到已启用的「离线语音输入」，可在这里选择其他语音输入法"
        list.setOnPreferenceChangeListener { _, _ -> view?.post { fillVoiceImes(); refreshStatus() }; true }
    }

    private fun refreshStatus() {
        val ctx = context ?: return
        refreshJob?.cancel()
        val s0 = AssistantSetup.status(ctx)
        findPreference<Preference>("status_assistant")?.summary =
            if (s0.roleHeld) "✓ 已是默认数字助理" else "✗ 还不是（点一键配置，或点这里手动选择「助手」）"
        val voice = ImeSwitcher(ctx)
        findPreference<Preference>("status_voice")?.summary = when {
            voice.voiceIme() != null -> "✓ 离线语音输入已启用" + if (AssistantSetup.hasWriteSecure(ctx)) "，可免 root 切换" else "（切换需要 root）"
            voice.audioInputInstalled() -> "✗ 离线语音输入未启用（点这里到系统输入法设置启用）"
            else -> "✗ 没有安装离线语音输入（Audio Input）"
        }
        findPreference<Preference>("status_last")?.summary = lastPressSummary(ctx)
        findPreference<Preference>("status_root")?.summary = "检查中…"
        findPreference<Preference>("status_power")?.summary = "检查中…"
        findPreference<Preference>("status_bgstart")?.summary = "检查中…"
        refreshJob = viewLifecycleOwner.lifecycleScope.launch {
            val root = RootShell.available()
            findPreference<Preference>("status_root")?.summary =
                if (root) "✓ 已授权" else "✗ 未授权：在 KernelSU 管理器里给「助手」开启超级用户（KernelSU 不会弹窗询问）"
            val power = if (root && AssistantSetup.refreshHidden()) {
                val s = AssistantSetup.status(ctx)
                if (s.powerKeyOk) "✓ 长按电源键唤起助手" else "✗ 当前是「${s.powerKey ?: "未知"}」（点这里一键配置）"
            } else "需要 root 才能检查和设置"
            findPreference<Preference>("status_power")?.summary = power
            findPreference<Preference>("status_bgstart")?.summary = when (AssistantSetup.backgroundStartAllowed(ctx)) {
                true -> "✓ 已允许"
                false -> "✗ 未允许：长按电源键只在助手界面里有效（点这里一键配置）"
                null -> "需要 root 才能检查；也可在 应用管理 → 助手 → 其他权限 里手动允许"
            }
        }
    }

    /** What happened on the last power-key press, from the marks the two processes leave. */
    private fun lastPressSummary(ctx: android.content.Context): String {
        val show = Marks.read(ctx, Marks.SHOW)
        if (show == 0L) return "还没有收到过（长按电源键后回来看这里）"
        val ago = (System.currentTimeMillis() - show) / 1000
        val agoText = when {
            ago < 60 -> "$ago 秒前"
            ago < 3600 -> "${ago / 60} 分钟前"
            ago < 86400 -> "${ago / 3600} 小时前"
            else -> "${ago / 86400} 天前"
        }
        val panel = Marks.read(ctx, Marks.PANEL)
        return when {
            Marks.read(ctx, Marks.BLOCKED) == show -> "$agoText：收到了，但面板被系统拦截（后台弹出界面）"
            panel >= show -> "$agoText：正常打开了面板"
            else -> "$agoText：收到了，面板没有打开"
        }
    }

    private fun applySetup() {
        val pref = findPreference<Preference>("setup_apply")
        pref?.summary = "正在配置…"
        viewLifecycleOwner.lifecycleScope.launch {
            val (ok, report) = AssistantSetup.apply(requireContext().applicationContext)
            pref?.summary = if (ok) "✓ 已配置，长按电源键试试" else "部分步骤失败，详见结果"
            message(if (ok) "配置完成" else "配置结果", report + if (ok) "\n\n长按电源键即可唤起助手。若没反应，重启一次手机后再试。" else "")
            refreshStatus()
        }
    }

    private fun message(title: String, text: String) {
        if (!isAdded) return
        MaterialAlertDialogBuilder(requireContext()).setTitle(title).setMessage(text).setPositiveButton("好", null).show()
    }
}
