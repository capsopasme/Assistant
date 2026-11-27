package io.github.capsopasme.assistant.charge

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AlertDialog
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceDataStore
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SeekBarPreference
import androidx.preference.SwitchPreferenceCompat
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.github.capsopasme.assistant.R
import io.github.capsopasme.assistant.system.RootShell
import io.github.capsopasme.assistant.util.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** ChgCtl settings; also opened by long-pressing the bypass tile. */
class ChargeSettingsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_settings)
        findViewById<MaterialToolbar>(R.id.toolbar).apply {
            title = getString(R.string.charge_title)
            setNavigationIcon(R.drawable.ic_back)
            setNavigationOnClickListener { finish() }
        }
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.settingsRoot)) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            v.updatePadding(top = bars.top, left = bars.left, right = bars.right, bottom = bars.bottom)
            insets
        }
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction().replace(R.id.settingsContainer, ChargeSettingsFragment()).commit()
        }
    }
}

class ChargeSettingsFragment : PreferenceFragmentCompat() {

    private var st: ChgCtl.Status? = null
    private var poll: Job? = null
    /** a long root task (calibration…) is running: no polling meanwhile */
    private var busy = false
    private val store = ConfigStore()

    /**
     * Preference values come from the last status read; a change goes to the module.
     * [syncing] = we are only showing the module's values, nothing to write.
     */
    private inner class ConfigStore : PreferenceDataStore() {
        var syncing = false

        override fun getBoolean(key: String, defValue: Boolean): Boolean {
            val s = st ?: return defValue
            return when (key) {
                "BYPASS" -> s.bypass
                "BYPASS_OFF_ON_UNPLUG" -> s.offOnUnplug
                "BYPASS_PERSIST" -> s.persist
                "VIBRATE" -> s.vibrate
                "ICON" -> s.iconCfg
                else -> defValue
            }
        }

        override fun getInt(key: String, defValue: Int): Int {
            val s = st ?: return defValue
            return when (key) {
                "LIMIT" -> s.limit
                "RESUME" -> s.resume
                "BYPASS_FLOOR" -> s.floor
                "AUTO_BYPASS_SOC" -> s.autoSoc
                else -> defValue
            }
        }

        override fun getString(key: String, defValue: String?): String? {
            val s = st ?: return defValue
            return when (key) {
                "AUTO_BYPASS" -> s.auto
                "AUTO_BYPASS_OFF_MIN" -> s.autoOffMin.toString()
                else -> defValue
            }
        }

        override fun putBoolean(key: String, value: Boolean) {
            if (syncing) return
            if (key == "BYPASS") {
                write("旁路供电") {
                    val ok = ChgCtl.send(if (value) ChgCtl.Cmd.BYPASS_ON else ChgCtl.Cmd.BYPASS_OFF)
                    RootShell.Result(if (ok) 0 else 1, if (ok) "" else "守护进程没有运行")
                }
            } else {
                write(key) { ChgCtl.set(key, if (value) "1" else "0") }
            }
        }

        override fun putInt(key: String, value: Int) {
            if (syncing) return
            val s = st ?: return
            when (key) {
                "LIMIT" -> write("停充上限") { ChgCtl.limit(value, minOf(s.resume, value - 5)) }
                "RESUME" -> if (s.limit < 100) write("复充阈值") { ChgCtl.limit(s.limit, value) }
                else -> write(key) { ChgCtl.set(key, value.toString()) }
            }
        }

        override fun putString(key: String, value: String?) {
            if (syncing || value == null) return
            write(key) { ChgCtl.set(key, value) }
        }
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        preferenceManager.preferenceDataStore = store
        setPreferencesFromResource(R.xml.charge_preferences, rootKey)
        setEditable(false)
        click("ch_status") { refreshNow() }
        click("ch_icon_setup") { iconSetup() }
        click("ch_icon_test") { iconTest() }
        click("ch_calibrate") { calibrate() }
        click("ch_log") { showLog() }
        click("ch_restart") { longTask("正在重启守护进程…") { ChgCtl.restart().out.ifBlank { "完成" } } }
    }

    override fun onResume() {
        super.onResume()
        poll?.cancel()
        poll = viewLifecycleOwner.lifecycleScope.launch {
            while (isActive) {
                if (!busy) refresh()
                delay(if (st?.plugged == true) 3_000 else 6_000)
            }
        }
    }

    override fun onPause() {
        poll?.cancel()
        super.onPause()
    }

    private fun click(key: String, action: () -> Unit) {
        findPreference<Preference>(key)?.setOnPreferenceClickListener { action(); true }
    }

    private fun refreshNow() {
        viewLifecycleOwner.lifecycleScope.launch { refresh() }
    }

    private suspend fun refresh() {
        val s = withContext(Dispatchers.IO) { runCatching { ChgCtl.status() }.getOrNull() }
        if (!isAdded) return
        st = s
        show(s)
    }

    /** Write, report a failure, then read back what the module made of it. */
    private fun write(what: String, block: suspend () -> RootShell.Result) {
        viewLifecycleOwner.lifecycleScope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { block() }.getOrElse { RootShell.Result(-1, it.message.orEmpty()) } }
            if (!isAdded) return@launch
            if (!r.ok) {
                val msg = r.out.lines().lastOrNull { it.isNotBlank() }?.trim()?.removePrefix("✗ ") ?: "失败"
                Toast.makeText(requireContext(), "$what：$msg", Toast.LENGTH_SHORT).show()
            }
            delay(400)
            refresh()
        }
    }

    private val editableKeys = listOf(
        "BYPASS", "LIMIT", "RESUME", "AUTO_BYPASS", "AUTO_BYPASS_SOC", "AUTO_BYPASS_OFF_MIN",
        "BYPASS_FLOOR", "BYPASS_OFF_ON_UNPLUG", "BYPASS_PERSIST", "VIBRATE", "ICON",
        "ch_icon_setup", "ch_icon_test", "ch_calibrate",
    )

    private fun setEditable(on: Boolean) {
        for (k in editableKeys) findPreference<Preference>(k)?.isEnabled = on
    }

    private fun show(s: ChgCtl.Status?) {
        val status = findPreference<Preference>("ch_status") ?: return
        findPreference<Preference>("ch_log")?.isEnabled = s != null
        findPreference<Preference>("ch_restart")?.isEnabled = s != null
        if (s == null) {
            status.title = "ChgCtl"
            status.summary = if (RootShell.lastAvailable == false) "✗ 没有 root：在 KernelSU 管理器里给「助手」开启超级用户"
            else "✗ 没有安装 ChgCtl 模块（或读取失败，点这里重试）"
            setEditable(false)
            return
        }
        status.title = "ChgCtl ${s.version}"
        val lines = mutableListOf<String>()
        val ma = if (s.currentMa > 0) "+${s.currentMa}" else s.currentMa.toString()
        lines += "${s.soc}% · ${ChgCtl.modeText(if (s.running) s.mode else "stopped")} · 电池 $ma mA · ${s.tempC}°C"
        val type = when (s.switchType) {
            "idle" -> "实测真旁路"; "discharge" -> "实测会放电"; "none" -> "实测无效"; else -> "未校准"
        }
        lines += "充电开关 ${s.switch}（$type）· 输入 ${if (s.usb) "USB" else if (s.wireless) "无线" else "无"}"
        if (!s.supported) lines += "⚠ 模块版本过旧：自动旁路、状态栏图标和磁贴需要 ChgCtl v1.3.0 以上"
        if (s.warn.isNotEmpty()) lines += "⚠ ${s.warn}"
        if (s.nightCharging) lines += "⚠ 系统「智能充电保护」开着，会把电量卡在 80%，建议关闭"
        if (s.running && !s.calibrated) lines += "⚠ 尚未校准（插上充电器后运行下面的「校准」）"
        status.summary = lines.joinToString("\n")

        setEditable(s.running && s.supported)
        if (!(s.running && s.supported)) return

        store.syncing = true
        try {
            findPreference<SwitchPreferenceCompat>("BYPASS")?.isChecked = s.bypass
            findPreference<SeekBarPreference>("LIMIT")?.value = s.limit
            findPreference<SeekBarPreference>("RESUME")?.value = s.resume
            findPreference<ListPreference>("AUTO_BYPASS")?.value = s.auto
            findPreference<SeekBarPreference>("AUTO_BYPASS_SOC")?.value = s.autoSoc
            findPreference<ListPreference>("AUTO_BYPASS_OFF_MIN")?.value = s.autoOffMin.toString()
            findPreference<SeekBarPreference>("BYPASS_FLOOR")?.value = s.floor
            findPreference<SwitchPreferenceCompat>("BYPASS_OFF_ON_UNPLUG")?.isChecked = s.offOnUnplug
            findPreference<SwitchPreferenceCompat>("BYPASS_PERSIST")?.isChecked = s.persist
            findPreference<SwitchPreferenceCompat>("VIBRATE")?.isChecked = s.vibrate
            findPreference<SwitchPreferenceCompat>("ICON")?.isChecked = s.iconCfg
        } finally {
            store.syncing = false
        }

        findPreference<SwitchPreferenceCompat>("BYPASS")?.apply {
            isEnabled = s.switchType != "discharge" && s.switch != "none"
            summary = when {
                s.switchType == "discharge" -> "当前开关会让电池放电，不能旁路（请校准）"
                s.bypass && !s.plugged -> "待命：插上充电器后生效"
                s.bypass && s.mode == "floor" -> "电量低于保底 ${s.floor}%，先充到保底再旁路"
                s.bypass && s.autoOwned -> "旁路中（由自动规则打开，拔电后结束）"
                s.bypass -> "旁路中：充电器直接供电，电池不充也不放"
                s.override -> "本次插电已手动关闭，自动规则不会再打开（拔电后恢复）"
                else -> "插电时由充电器直接给手机供电，电池不充也不放"
            }
        }
        findPreference<SeekBarPreference>("LIMIT")?.summary =
            if (s.limit >= 100) "不限制，充到 100%" else "充到 ${s.limit}% 停止，之后由充电器直接供电"
        findPreference<SeekBarPreference>("RESUME")?.apply {
            isEnabled = s.limit < 100
            max = (s.limit - 1).coerceAtLeast(11)
        }
        val keepAlive = Prefs.get(requireContext()).keepAlive
        findPreference<ListPreference>("AUTO_BYPASS")?.summary = when (s.auto) {
            "screen" -> "插着电、电量 ≥ ${s.autoSoc}% 且亮屏时旁路；灭屏${offText(s.autoOffMin)}恢复按上限充电" +
                if (keepAlive) "" else "\n⚠ 需要在主页开启「保持随时可唤起」，否则收不到亮灭屏"
            "plug" -> "插着电且电量 ≥ ${s.autoSoc}% 就旁路（不足时先充到 ${s.autoSoc}%）"
            else -> "关"
        }
        findPreference<SeekBarPreference>("AUTO_BYPASS_SOC")?.isEnabled = s.auto != "off"
        findPreference<ListPreference>("AUTO_BYPASS_OFF_MIN")?.apply {
            isEnabled = s.auto == "screen"
            summary = "灭屏${offText(s.autoOffMin)}恢复充电；亮屏时再旁路"
        }
        findPreference<SwitchPreferenceCompat>("ICON")?.summary = when {
            !s.iconCfg -> "已关闭"
            !s.iconOk -> "未就绪：点下面的「生成图标配置」"
            s.iconOn -> "显示中"
            else -> "就绪：插着电且电池保持不充（旁路或到达上限）时显示"
        }
    }

    private fun offText(min: Int) = if (min <= 0) "后立即" else " $min 分钟后"

    private fun iconSetup() {
        longTask("正在生成图标配置…") {
            val r = IconSetup.generate(requireContext().applicationContext)
            r.report
        }
    }

    private fun iconTest() {
        longTask("正在自检，留意状态栏…") { IconSetup.selfTest(requireContext().applicationContext).report }
    }

    private fun calibrate() {
        val s = st ?: return
        if (!s.plugged) {
            Toast.makeText(requireContext(), "请先插上充电器", Toast.LENGTH_SHORT).show()
            return
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("校准充电开关")
            .setMessage("会逐个短暂停充来测量电池电流（约 1~2 分钟），期间请勿拔线。开始？")
            .setPositiveButton("开始") { _, _ -> longTask("校准中，约 1~2 分钟，请勿拔线…") { ChgCtl.calibrate().out.ifBlank { "完成" } } }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showLog() {
        viewLifecycleOwner.lifecycleScope.launch {
            val text = withContext(Dispatchers.IO) { ChgCtl.log(150) }.trim().ifEmpty { "（空）" }
            textDialog("ChgCtl 日志", text)
        }
    }

    /** Runs a long root task behind a non-cancellable progress dialog, then shows its output. */
    private fun longTask(progress: String, block: suspend () -> String) {
        if (busy) return
        busy = true
        val dlg: AlertDialog = MaterialAlertDialogBuilder(requireContext())
            .setMessage(progress)
            .setCancelable(false)
            .show()
        viewLifecycleOwner.lifecycleScope.launch {
            val out = try {
                withContext(Dispatchers.IO) { block() }
            } catch (e: Exception) {
                "✗ ${e.message}"
            } finally {
                busy = false
                dlg.dismiss()
            }
            if (!isAdded) return@launch
            textDialog("结果", out)
            refresh()
        }
    }

    private fun textDialog(title: String, text: String) {
        if (!isAdded) return
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(title)
            .setMessage(text)
            .setPositiveButton("复制") { _, _ ->
                val cm = requireContext().getSystemService(android.content.ClipboardManager::class.java)
                cm.setPrimaryClip(android.content.ClipData.newPlainText(title, text))
            }
            .setNegativeButton("关闭", null)
            .show()
    }
}
