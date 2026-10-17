package io.github.capsopasme.assistant.system

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import io.github.capsopasme.assistant.util.Prefs

/**
 * Switches to the voice-only input method (Audio Input) for dictation and back afterwards.
 *
 * With WRITE_SECURE_SETTINGS (granted once through root in the setup) the switch is a settings
 * write, with no root shell involved; otherwise `ime set` through root. The previous keyboard is
 * remembered in the preferences, so it is restored even if the panel was killed meanwhile.
 */
class ImeSwitcher(private val context: Context) {

    private val prefs = Prefs.get(context)
    private val imm get() = context.getSystemService(InputMethodManager::class.java)

    fun current(): String? = Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)

    /** The configured voice IME if it is enabled, else an enabled Audio Input. */
    fun voiceIme(): String? {
        val enabled = runCatching { imm.enabledInputMethodList.map { it.id } }.getOrDefault(emptyList())
        prefs.voiceIme?.let { if (it in enabled) return it }
        return enabled.firstOrNull { it.startsWith("$AUDIO_INPUT_PKG/") }
    }

    fun audioInputInstalled(): Boolean =
        runCatching { context.packageManager.getPackageInfo(AUDIO_INPUT_PKG, 0); true }.getOrDefault(false)

    fun isVoiceActive(): Boolean {
        val v = voiceIme() ?: return false
        return current() == v
    }

    fun canSwitchWithoutRoot() =
        context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED

    private suspend fun switchTo(id: String): Boolean {
        if (current() == id) return true
        if (canSwitchWithoutRoot()) {
            val ok = runCatching { Settings.Secure.putString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD, id) }.getOrDefault(false)
            if (ok) {
                // the input method service picks the change up asynchronously
                repeat(10) {
                    if (current() == id) return true
                    kotlinx.coroutines.delay(50)
                }
            }
        }
        val r = RootShell.run("ime set ${RootShell.q(id)}", 4_000)
        return r.ok && current() == id
    }

    /** Switches to voice input, remembering the keyboard to come back to. */
    suspend fun toVoice(): Boolean {
        val voice = voiceIme() ?: return false
        val cur = current()
        if (cur == voice) return true
        if (cur != null) prefs.sp.edit().putString(Prefs.KEY_IME_RESTORE, cur).commit()
        return switchTo(voice)
    }

    /** Back to the remembered keyboard, if voice input is still the active one. */
    suspend fun restore(): Boolean {
        val saved = prefs.sp.getString(Prefs.KEY_IME_RESTORE, null) ?: return false
        val voice = voiceIme()
        val cur = current()
        if (voice != null && cur != voice) {
            // the user already switched by hand
            prefs.sp.edit().remove(Prefs.KEY_IME_RESTORE).apply()
            return false
        }
        val ok = switchTo(saved)
        if (ok) prefs.sp.edit().remove(Prefs.KEY_IME_RESTORE).apply()
        return ok
    }

    /** Back to a keyboard for typing (the remembered one, else the first other enabled IME). */
    suspend fun toKeyboard(): Boolean {
        if (restore()) return true
        val voice = voiceIme()
        val other = prefs.sp.getString(Prefs.KEY_IME_RESTORE, null)
            ?: runCatching { imm.enabledInputMethodList.map { it.id }.firstOrNull { it != voice } }.getOrNull()
            ?: return false
        return switchTo(other)
    }

    fun hasPendingRestore() = prefs.sp.contains(Prefs.KEY_IME_RESTORE)

    companion object {
        const val AUDIO_INPUT_PKG = "io.github.capsopasme.audioinput"
    }
}
