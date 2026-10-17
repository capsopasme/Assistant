package io.github.capsopasme.assistant.system

import android.app.NotificationManager
import android.app.UiModeManager
import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.location.LocationManager
import android.media.AudioManager
import android.net.wifi.WifiManager
import android.nfc.NfcAdapter
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.KeyEvent
import io.github.capsopasme.assistant.action.ActionResult
import io.github.capsopasme.assistant.action.MediaCommand
import io.github.capsopasme.assistant.action.SwitchTarget
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Phone switches, volume and brightness. Root commands do the switching (third-party apps may not
 * toggle Bluetooth, airplane mode … themselves on Android 16); public APIs read the state back, so
 * the reported result is what the phone actually did, not what was asked.
 */
class SystemControl(private val context: Context) {

    private val cr get() = context.contentResolver

    // --- switches -------------------------------------------------------------------------------

    /** Current state, or null if it can't be read. */
    suspend fun state(target: SwitchTarget): Boolean? = when (target) {
        SwitchTarget.BLUETOOTH -> Settings.Global.getInt(cr, Settings.Global.BLUETOOTH_ON, 0) == 1
        SwitchTarget.WIFI -> context.getSystemService(WifiManager::class.java)?.isWifiEnabled
        SwitchTarget.AIRPLANE -> Settings.Global.getInt(cr, Settings.Global.AIRPLANE_MODE_ON, 0) == 1
        SwitchTarget.MOBILE_DATA -> rootGet("settings get global mobile_data")?.let { it == "1" }
        SwitchTarget.DARK_MODE -> context.getSystemService(UiModeManager::class.java)?.nightMode == UiModeManager.MODE_NIGHT_YES
        SwitchTarget.EYE_CARE -> rootGet("settings get system screen_paper_mode_enabled")?.let { it == "1" }
        SwitchTarget.FLASHLIGHT -> torchState()
        SwitchTarget.DND -> context.getSystemService(NotificationManager::class.java)?.currentInterruptionFilter
            ?.let { it != NotificationManager.INTERRUPTION_FILTER_ALL && it != NotificationManager.INTERRUPTION_FILTER_UNKNOWN }
        SwitchTarget.LOCATION -> context.getSystemService(LocationManager::class.java)?.isLocationEnabled
        SwitchTarget.AUTO_ROTATE -> Settings.System.getInt(cr, Settings.System.ACCELEROMETER_ROTATION, 0) == 1
        SwitchTarget.NFC -> NfcAdapter.getDefaultAdapter(context)?.isEnabled
    }

    private suspend fun rootGet(cmd: String): String? {
        val r = RootShell.run(cmd, 4_000)
        return if (r.ok) r.out.trim().lines().lastOrNull()?.trim() else null
    }

    suspend fun setSwitch(target: SwitchTarget, wanted: Boolean?): ActionResult {
        if (target == SwitchTarget.FLASHLIGHT) return setTorch(wanted)
        if (target == SwitchTarget.NFC && NfcAdapter.getDefaultAdapter(context) == null) return ActionResult(false, "这台手机没有 NFC")

        val before = state(target)
        val on = wanted ?: (before?.not() ?: return ActionResult(false, "读不到${target.label}的当前状态，请说明打开还是关闭"))
        val word = if (on) "打开" else "关闭"
        if (before == on) return ActionResult(true, "${target.label}已经是${word}状态")

        if (!RootShell.available()) return withoutRoot(target, on)

        val cmd = rootCommand(target, on)
        val r = RootShell.run(cmd, 10_000)
        if (!r.ok && r.code == -1) return ActionResult(false, "执行失败：${r.out.ifBlank { "root 会话中断" }}")
        // the change takes a moment (Bluetooth / Wi-Fi radios): wait for it
        val deadline = SystemClock.elapsedRealtime() + if (target == SwitchTarget.BLUETOOTH || target == SwitchTarget.WIFI) 6_000 else 3_000
        while (SystemClock.elapsedRealtime() < deadline) {
            if (state(target) == on) return ActionResult(true, "已${word}${target.label}")
            delay(250)
        }
        return when (val now = state(target)) {
            on -> ActionResult(true, "已${word}${target.label}")
            null -> ActionResult(r.ok, if (r.ok) "已尝试${word}${target.label}" else "${word}${target.label}失败：${r.out.take(120)}")
            else -> ActionResult(false, "${word}${target.label}没有成功${if (r.out.isNotBlank()) "：" + r.out.take(120) else ""}")
        }
    }

    private fun rootCommand(t: SwitchTarget, on: Boolean): String {
        val en = if (on) "enable" else "disable"
        return when (t) {
            SwitchTarget.BLUETOOTH -> "cmd bluetooth_manager $en || svc bluetooth $en"
            SwitchTarget.WIFI -> "svc wifi $en || cmd wifi set-wifi-enabled ${if (on) "enabled" else "disabled"}"
            SwitchTarget.AIRPLANE -> "cmd connectivity airplane-mode $en"
            SwitchTarget.MOBILE_DATA -> "svc data $en"
            // HyperOS keeps its own flag next to the AOSP night mode; keep both in step
            SwitchTarget.DARK_MODE -> "cmd uimode night ${if (on) "yes" else "no"}; settings put system dark_mode_enable ${if (on) 1 else 0}"
            SwitchTarget.EYE_CARE -> "settings put system screen_paper_mode_enabled ${if (on) 1 else 0}"
            SwitchTarget.DND -> "cmd notification set_dnd ${if (on) "priority" else "off"}"
            SwitchTarget.LOCATION -> "cmd location set-location-enabled $on"
            SwitchTarget.AUTO_ROTATE -> "settings put system accelerometer_rotation ${if (on) 1 else 0}"
            SwitchTarget.NFC -> "svc nfc $en"
            SwitchTarget.FLASHLIGHT -> error("not a root switch")
        }
    }

    /** Without root: what an ordinary app may do, otherwise the right settings page. */
    private fun withoutRoot(t: SwitchTarget, on: Boolean): ActionResult {
        val word = if (on) "打开" else "关闭"
        when (t) {
            SwitchTarget.AUTO_ROTATE -> if (Settings.System.canWrite(context)) {
                Settings.System.putInt(cr, Settings.System.ACCELEROMETER_ROTATION, if (on) 1 else 0)
                return ActionResult(true, "已${word}${t.label}")
            }
            SwitchTarget.DND -> {
                val nm = context.getSystemService(NotificationManager::class.java)
                if (nm != null && nm.isNotificationPolicyAccessGranted) {
                    nm.setInterruptionFilter(if (on) NotificationManager.INTERRUPTION_FILTER_PRIORITY else NotificationManager.INTERRUPTION_FILTER_ALL)
                    return ActionResult(true, "已${word}${t.label}")
                }
            }
            else -> {}
        }
        val page = when (t) {
            SwitchTarget.WIFI -> Intent(Settings.Panel.ACTION_WIFI)
            SwitchTarget.MOBILE_DATA -> Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY)
            SwitchTarget.NFC -> Intent(Settings.Panel.ACTION_NFC)
            SwitchTarget.BLUETOOTH -> Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
            SwitchTarget.AIRPLANE -> Intent(Settings.ACTION_AIRPLANE_MODE_SETTINGS)
            SwitchTarget.LOCATION -> Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
            SwitchTarget.DARK_MODE, SwitchTarget.EYE_CARE, SwitchTarget.AUTO_ROTATE -> Intent(Settings.ACTION_DISPLAY_SETTINGS)
            SwitchTarget.DND -> Intent(Settings.ACTION_ZEN_MODE_PRIORITY_SETTINGS)
            SwitchTarget.FLASHLIGHT -> return ActionResult(false, "无法控制手电筒")
        }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val opened = runCatching { context.startActivity(page) }.isSuccess
        return ActionResult(false, "没有 root 权限，无法直接${word}${t.label}" + if (opened) "，已打开设置页" else "", leftApp = opened)
    }

    // --- torch ----------------------------------------------------------------------------------

    private fun torchCameraId(cm: CameraManager): String? = runCatching {
        cm.cameraIdList.firstOrNull { id ->
            val ch = cm.getCameraCharacteristics(id)
            ch.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true &&
                ch.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
        } ?: cm.cameraIdList.firstOrNull { cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true }
    }.getOrNull()

    /** The torch state is only reported through a callback, which fires once right after registering. */
    private suspend fun torchState(): Boolean? {
        val cm = context.getSystemService(CameraManager::class.java) ?: return null
        val id = torchCameraId(cm) ?: return null
        return suspendCancellableCoroutine { cont ->
            val handler = Handler(Looper.getMainLooper())
            val cb = object : CameraManager.TorchCallback() {
                override fun onTorchModeChanged(cameraId: String, enabled: Boolean) {
                    if (cameraId == id && cont.isActive) { cm.unregisterTorchCallback(this); cont.resume(enabled) }
                }
                override fun onTorchModeUnavailable(cameraId: String) {
                    if (cameraId == id && cont.isActive) { cm.unregisterTorchCallback(this); cont.resume(null) }
                }
            }
            cm.registerTorchCallback(cb, handler)
            handler.postDelayed({ if (cont.isActive) { cm.unregisterTorchCallback(cb); cont.resume(null) } }, 800)
            cont.invokeOnCancellation { cm.unregisterTorchCallback(cb) }
        }
    }

    private suspend fun setTorch(wanted: Boolean?): ActionResult {
        val cm = context.getSystemService(CameraManager::class.java) ?: return ActionResult(false, "无法控制手电筒")
        val id = torchCameraId(cm) ?: return ActionResult(false, "没有找到闪光灯")
        val on = wanted ?: (torchState()?.not() ?: true)
        return try {
            cm.setTorchMode(id, on)
            ActionResult(true, if (on) "已打开手电筒" else "已关闭手电筒")
        } catch (e: Exception) {
            // the camera is in use by another app
            ActionResult(false, "手电筒暂时不可用（相机可能正被占用）")
        }
    }

    // --- volume ---------------------------------------------------------------------------------

    fun setVolume(percent: Int?, delta: Int?): ActionResult {
        val am = context.getSystemService(AudioManager::class.java) ?: return ActionResult(false, "无法调节音量")
        val stream = AudioManager.STREAM_MUSIC
        val max = am.getStreamMaxVolume(stream)
        val min = am.getStreamMinVolume(stream)
        val cur = am.getStreamVolume(stream)
        val target = when {
            percent != null -> (max * percent.coerceIn(0, 100) / 100.0).roundToInt()
            delta != null -> {
                val step = maxOf(1, (max * kotlin.math.abs(delta) / 100.0).roundToInt())
                cur + if (delta > 0) step else -step
            }
            else -> return ActionResult(false, "没有说要调到多少")
        }.coerceIn(min, max)
        try {
            am.setStreamVolume(stream, target, AudioManager.FLAG_SHOW_UI)
        } catch (e: SecurityException) {
            return ActionResult(false, "勿扰模式下系统不允许调整音量")
        }
        val now = am.getStreamVolume(stream)
        val pct = if (max > 0) (now * 100.0 / max).roundToInt() else 0
        return when {
            now == target -> ActionResult(true, if (now == 0) "已静音（媒体音量）" else "媒体音量已调到 $pct%")
            // HyperOS' hearing protection caps headphone volume until the user confirms
            now < target -> ActionResult(true, "媒体音量只调到了 $pct%（可能被系统的安全音量限制）")
            else -> ActionResult(true, "媒体音量现在是 $pct%")
        }
    }

    // --- brightness -----------------------------------------------------------------------------

    suspend fun setBrightness(percent: Int?, delta: Int?): ActionResult {
        val root = RootShell.available()
        val currentLinear: Float = if (root) {
            rootGet("settings get system screen_brightness_float")?.toFloatOrNull()
        } else {
            null
        } ?: (Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS, 128) / 255f)
        val currentPct = (linearToSlider(currentLinear.coerceIn(0f, 1f)) * 100).roundToInt()
        val targetPct = when {
            percent != null -> percent
            delta != null -> currentPct + delta
            else -> return ActionResult(false, "没有说要调到多少")
        }.coerceIn(2, 100) // never black out the screen
        val linear = sliderToLinear(targetPct / 100f)

        if (root) {
            val f = "%.4f".format(java.util.Locale.US, linear)
            val r = RootShell.run(
                "settings put system screen_brightness_mode 0; cmd display set-brightness $f || settings put system screen_brightness_float $f"
            )
            return if (r.ok) ActionResult(true, "亮度已调到 $targetPct%" + if (percent == null && delta != null) "（原来约 $currentPct%）" else "")
            else ActionResult(false, "调节亮度失败：${r.out.take(120)}")
        }
        if (Settings.System.canWrite(context)) {
            Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
            Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS, (linear * 255).roundToInt().coerceIn(1, 255))
            return ActionResult(true, "亮度已调到 $targetPct%")
        }
        return ActionResult(false, "调节亮度需要 root 权限（或在设置里授予「修改系统设置」）")
    }

    // The brightness slider is not linear: AOSP maps it through a gamma curve (BrightnessUtils).
    private val R = 0.5f
    private val A = 0.17883277f
    private val B = 0.28466892f
    private val C = 0.55991073f

    private fun sliderToLinear(slider: Float): Float {
        val ret = if (slider <= R) (slider / R) * (slider / R) else exp((slider - C) / A) + B
        return (ret / 12f).coerceIn(0f, 1f)
    }

    private fun linearToSlider(linear: Float): Float {
        val v = linear * 12f
        val ret = if (v <= 1f) sqrt(v) * R else A * ln(v - B) + C
        return ret.coerceIn(0f, 1f)
    }

    // --- media keys -----------------------------------------------------------------------------

    fun media(cmd: MediaCommand): ActionResult {
        val am = context.getSystemService(AudioManager::class.java) ?: return ActionResult(false, "无法控制播放")
        val code = when (cmd) {
            MediaCommand.PLAY -> KeyEvent.KEYCODE_MEDIA_PLAY
            MediaCommand.PAUSE -> KeyEvent.KEYCODE_MEDIA_PAUSE
            MediaCommand.NEXT -> KeyEvent.KEYCODE_MEDIA_NEXT
            MediaCommand.PREVIOUS -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
        }
        val now = SystemClock.uptimeMillis()
        am.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, 0))
        am.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, code, 0))
        return ActionResult(true, "已${cmd.label}")
    }
}
