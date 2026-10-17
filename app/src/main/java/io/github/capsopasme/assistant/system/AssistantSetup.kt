package io.github.capsopasme.assistant.system

import android.Manifest
import android.app.role.RoleManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import io.github.capsopasme.assistant.vis.AssistVoiceService
import io.github.capsopasme.assistant.vis.StubRecognitionService

/**
 * Makes this app the phone's digital assistant and routes the power-key long press to it.
 *
 * HyperOS (China) specifics, see README:
 * - the long press only reaches a third-party assistant with system setting
 *   long_press_power_key = launch_google_search ("数字助理"); the default value starts 小爱;
 * - on boot HyperOS re-initialises the assistant and falls back to 小爱 when the configured
 *   speech recognizer isn't a working one, so the recognizer is pointed at Xiaomi's own engine
 *   (com.xiaomi.mibrain.speech) when it's installed;
 * - everything is re-applied after boot / app updates ([BootReceiver]) and checked whenever the
 *   assistant service is bound.
 */
object AssistantSetup {

    private const val MIBRAIN_RECOGNIZER = "com.xiaomi.mibrain.speech/com.xiaomi.mibrain.speech.asr.AsrService"
    const val POWER_KEY_ASSISTANT = "launch_google_search"
    private const val OP_SHOW_WHEN_LOCKED = 10020
    private const val OP_BACKGROUND_START_ACTIVITY = 10021

    data class Status(
        val roleHeld: Boolean,
        val interactionService: Boolean,
        val powerKey: String?,
        val recognizer: String?,
    ) {
        val powerKeyOk get() = powerKey == POWER_KEY_ASSISTANT
        val allOk get() = roleHeld && interactionService && powerKeyOk
    }

    fun vis(context: Context) = ComponentName(context, AssistVoiceService::class.java).flattenToShortString()
    private fun visFull(context: Context) = ComponentName(context, AssistVoiceService::class.java).flattenToString()

    fun status(context: Context): Status {
        val cr = context.contentResolver
        val role = runCatching {
            context.getSystemService(RoleManager::class.java).isRoleHeld(RoleManager.ROLE_ASSISTANT)
        }.getOrDefault(false)
        // hidden settings may be unreadable for apps on Android 12+: then the role decides
        val visSetting = runCatching { Settings.Secure.getString(cr, "voice_interaction_service") }.getOrNull()
        val visOk = if (visSetting == null) role else ComponentName.unflattenFromString(visSetting)?.packageName == context.packageName
        // long_press_power_key is a hidden system setting: apps targeting Android 12+ may not read
        // it directly, so it comes from the last root check
        return Status(role, visOk, lastPowerKey, runCatching { Settings.Secure.getString(cr, "voice_recognition_service") }.getOrNull())
    }

    @Volatile private var lastPowerKey: String? = null

    /** Reads the hidden settings through root (also refreshes [status]). */
    suspend fun refreshHidden(): Boolean {
        val r = RootShell.run("settings get system long_press_power_key", 4_000)
        if (r.ok) lastPowerKey = r.out.trim().lines().lastOrNull()?.trim()
        return r.ok
    }

    private fun recognizer(context: Context): String {
        val mibrain = runCatching {
            context.packageManager.getServiceInfo(ComponentName.unflattenFromString(MIBRAIN_RECOGNIZER)!!, 0).isEnabled
        }.getOrDefault(false)
        return if (mibrain) MIBRAIN_RECOGNIZER else ComponentName(context, StubRecognitionService::class.java).flattenToString()
    }

    /**
     * Applies everything through root. Returns a human-readable report, one line per step.
     * Safe to run repeatedly.
     */
    suspend fun apply(context: Context): Pair<Boolean, String> {
        if (!RootShell.available()) {
            return false to "没有 root 权限：请在 KernelSU 管理器里给「助手」开启超级用户，然后再试"
        }
        val pkg = context.packageName
        val vis = visFull(context)
        val steps = listOf(
            "设为默认数字助理" to "cmd role add-role-holder --user 0 android.app.role.ASSISTANT $pkg 0 || cmd role add-role-holder android.app.role.ASSISTANT $pkg",
            "语音交互服务" to "settings put secure voice_interaction_service $vis && settings put secure assistant $vis",
            "语音识别服务" to "settings put secure voice_recognition_service ${recognizer(context)}",
            "长按电源键唤起助理" to "settings put system long_press_power_key $POWER_KEY_ASSISTANT && settings put global power_button_long_press 0",
            // the assistant never looks at the screen: no screenshot / view-tree capture on every launch
            "关闭读屏与截屏" to "settings put secure assist_structure_enabled 0; settings put secure assist_screenshot_enabled 0; true",
            "授予切换输入法权限" to "pm grant $pkg ${Manifest.permission.WRITE_SECURE_SETTINGS}",
            "授予修改系统设置" to "appops set $pkg WRITE_SETTINGS allow",
            // the assistant window can't show permission dialogs nicely: grant what calls need up front
            "授予通讯录和拨号权限" to "pm grant $pkg ${Manifest.permission.READ_CONTACTS} && pm grant $pkg ${Manifest.permission.CALL_PHONE}",
            // HyperOS blocks activity starts from the background per app ("后台弹出界面", MIUI
            // app-op 10021): needed to open other apps from the panel
            "允许后台弹出界面" to "appops set $pkg $OP_BACKGROUND_START_ACTIVITY allow",
            "允许锁屏显示" to "appops set $pkg $OP_SHOW_WHEN_LOCKED allow",
            // the AOSP exemption from background-start limits; harmless if HyperOS ignores it
            "允许显示在其他应用上层" to "appops set $pkg SYSTEM_ALERT_WINDOW allow",
            // HyperOS' "自启动" (10008) and boot broadcast (10007): let the boot receiver re-apply this
            "允许自启动" to "appops set $pkg 10008 allow 2>/dev/null; appops set $pkg 10007 allow 2>/dev/null; true",
        )
        val report = StringBuilder()
        var allOk = true
        for ((name, cmd) in steps) {
            val r = RootShell.run(cmd, 10_000)
            val ok = r.ok
            if (!ok) allOk = false
            report.append(if (ok) "✓ " else "✗ ").append(name)
            if (!ok && r.out.isNotBlank()) report.append("：").append(r.out.lines().last().take(80))
            report.append('\n')
        }
        refreshHidden()
        val s = status(context)
        if (!s.roleHeld || !s.interactionService) {
            allOk = false
            report.append("✗ 系统没有接受本应用为数字助理，请在系统设置「默认应用 → 数字助理应用」里手动选择「助手」\n")
        }
        return allOk to report.toString().trim()
    }

    /** Re-applies silently when HyperOS has reset the assistant (after boot, or 小爱 taking over). */
    suspend fun ensure(context: Context) {
        val s = status(context)
        if (s.roleHeld && s.interactionService) {
            if (!refreshHidden()) return // no root: nothing we could fix anyway
            if (lastPowerKey == POWER_KEY_ASSISTANT && backgroundStartAllowed(context) != false) return
        }
        apply(context)
    }

    /** HyperOS "后台弹出界面" for this app: true / false, null if it can't be read (no root). */
    suspend fun backgroundStartAllowed(context: Context): Boolean? {
        val r = RootShell.run("appops get ${context.packageName} $OP_BACKGROUND_START_ACTIVITY", 4_000)
        if (r.code == -1) return null
        return Regex(":\\s*allow").containsMatchIn(r.out.lowercase())
    }

    fun hasWriteSecure(context: Context) =
        context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED

    /** System page for picking the digital assistant (the manual route without root). */
    fun assistantSettingsIntent(): Intent =
        Intent(Settings.ACTION_VOICE_INPUT_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun manageDefaultAppsIntent(): Intent =
        Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** HyperOS' autostart page for this app; falls back to the app's info page. */
    fun autostartIntents(context: Context): List<Intent> = listOf(
        Intent().setComponent(ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity")),
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:${context.packageName}")),
    ).map { it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
}
