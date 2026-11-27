package io.github.capsopasme.assistant.util

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import androidx.preference.PreferenceDataStore
import androidx.preference.PreferenceManager
import io.github.capsopasme.assistant.llm.Provider
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Typed access to the settings. Every preference key lives here. */
class Prefs(context: Context) {
    private val app = context.applicationContext
    val sp: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(app)

    val provider: Provider
        get() = Provider.fromId(sp.getString(KEY_PROVIDER, null))

    fun apiKey(p: Provider): String = SecretStore.decrypt(sp.getString(p.keyPref, null)).trim()
    fun model(p: Provider): String = sp.getString(p.modelPref, null)?.trim().takeUnless { it.isNullOrEmpty() } ?: p.defaultModel
    fun baseUrl(p: Provider): String = sp.getString(p.urlPref, null)?.trim()?.trimEnd('/').takeUnless { it.isNullOrEmpty() } ?: p.defaultBaseUrl

    val autoVoice get() = sp.getBoolean(KEY_AUTO_VOICE, true)
    val autoSend get() = sp.getBoolean(KEY_AUTO_SEND, true)
    /** quiet time after a sentence before it is sent (complete phone commands go sooner) */
    val sendWaitMs: Long get() = sp.getString(KEY_SEND_WAIT, null)?.toLongOrNull()?.coerceIn(300, 3000) ?: 800L
    val voiceResident get() = sp.getBoolean(KEY_VOICE_RESIDENT, true)
    val mapApp: String get() = sp.getString(KEY_MAP_APP, MAP_COMAPS) ?: MAP_COMAPS
    val aliases: String get() = sp.getString(KEY_ALIASES, "") ?: ""
    val keepAssistant get() = sp.getBoolean(KEY_KEEP_ASSISTANT, true)
    val keepAlive get() = sp.getBoolean(KEY_KEEP_ALIVE, true)
    /** The user generated ChgCtl's icon.conf once: keep it current after boots / SystemUI updates. */
    var chargeIconSetup: Boolean
        get() = sp.getBoolean(KEY_CHARGE_ICON_SETUP, false)
        set(v) = sp.edit().putBoolean(KEY_CHARGE_ICON_SETUP, v).apply()

    companion object {
        const val KEY_PROVIDER = "provider"
        const val KEY_AUTO_VOICE = "auto_voice"
        const val KEY_AUTO_SEND = "auto_send"
        const val KEY_SEND_WAIT = "send_wait_ms"
        const val KEY_VOICE_RESIDENT = "voice_resident"
        const val KEY_MAP_APP = "map_app"
        const val KEY_ALIASES = "app_aliases"
        const val KEY_KEEP_ASSISTANT = "keep_assistant"
        const val KEY_KEEP_ALIVE = "keep_alive"
        const val KEY_CHARGE_ICON_SETUP = "charge_icon_setup"

        const val MAP_COMAPS = "comaps"
        const val MAP_AMAP = "amap"
        const val MAP_SYSTEM = "system"

        @Volatile private var instance: Prefs? = null
        fun get(context: Context): Prefs = instance ?: synchronized(this) {
            instance ?: Prefs(context).also { instance = it }
        }
    }
}

/**
 * API keys are stored encrypted with a key that never leaves the Android keystore, so they don't
 * sit in plain text in the preferences file (backups are off as well).
 */
object SecretStore {
    private const val TAG = "SecretStore"
    private const val ALIAS = "assistant_api_keys"
    private const val PREFIX = "enc1:"

    @Synchronized
    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }

    fun encrypt(plain: String): String {
        if (plain.isEmpty()) return ""
        return try {
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.ENCRYPT_MODE, key())
            val out = c.iv + c.doFinal(plain.toByteArray(Charsets.UTF_8))
            PREFIX + Base64.encodeToString(out, Base64.NO_WRAP)
        } catch (e: Exception) {
            Log.w(TAG, "encrypt failed", e)
            ""
        }
    }

    /** Empty when nothing is stored or the keystore key is gone (the key has to be entered again). */
    fun decrypt(stored: String?): String {
        if (stored.isNullOrEmpty()) return ""
        if (!stored.startsWith(PREFIX)) return stored // tolerate a plain value
        return try {
            val raw = Base64.decode(stored.removePrefix(PREFIX), Base64.NO_WRAP)
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, raw, 0, 12))
            String(c.doFinal(raw, 12, raw.size - 12), Charsets.UTF_8)
        } catch (e: Exception) {
            Log.w(TAG, "decrypt failed", e)
            ""
        }
    }
}

/** Lets the settings screen edit API keys while only ciphertext reaches the preferences file. */
class SecretDataStore(private val sp: SharedPreferences) : PreferenceDataStore() {
    override fun putString(key: String, value: String?) {
        sp.edit().putString(key, SecretStore.encrypt(value?.trim().orEmpty())).apply()
    }

    override fun getString(key: String, defValue: String?): String? =
        SecretStore.decrypt(sp.getString(key, null)).ifEmpty { defValue }
}
