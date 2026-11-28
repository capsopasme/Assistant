package io.github.capsopasme.assistant.util

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import android.view.HapticFeedbackConstants
import android.view.View
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sin

/**
 * "Done" / "didn't work" feedback after a request: a short chime plus a haptic tick on success,
 * only a different haptic on failure. Nothing for plain answers.
 *
 *  - Haptics go through the panel's view first: HyperOS maps CONFIRM / REJECT onto its own tuned
 *    effects for the linear motor. Without an attached view, a predefined vibration instead.
 *  - The chime is synthesized once (no audio file) into a static AudioTrack and played as a
 *    system sonification: silent and vibrate mode mute it, as they should. It holds a transient
 *    "may duck" focus for its length, so music the panel paused and is about to resume ducks
 *    under it instead of drowning it.
 */
object Feedback {
    private const val TAG = "Feedback"
    private const val RATE = 44_100
    /** length of the chime, ms */
    const val CHIME_MS = 170L

    private val main = Handler(Looper.getMainLooper())
    private var track: AudioTrack? = null
    private var focus: AudioFocusRequest? = null

    private val attrs: AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()

    /** Builds the chime ahead of use (first play would otherwise allocate on the spot). */
    fun prepare(context: Context) {
        if (!Prefs.get(context).feedbackSound || track != null) return
        runCatching { ensureTrack() }.onFailure { Log.w(TAG, "chime unavailable: ${it.message}") }
    }

    /** @return how long the chime plays (0 when silent): hold the audio focus at least that long */
    fun success(context: Context, view: View?): Long {
        val prefs = Prefs.get(context)
        if (prefs.feedbackHaptic) haptic(context, view, HapticFeedbackConstants.CONFIRM, VibrationEffect.EFFECT_CLICK)
        return if (prefs.feedbackSound) chime(context) else 0L
    }

    fun failure(context: Context, view: View?) {
        if (Prefs.get(context).feedbackHaptic) haptic(context, view, HapticFeedbackConstants.REJECT, VibrationEffect.EFFECT_DOUBLE_CLICK)
    }

    private fun haptic(context: Context, view: View?, constant: Int, fallback: Int) {
        if (view != null && view.isAttachedToWindow && view.performHapticFeedback(constant)) return
        runCatching {
            context.getSystemService(Vibrator::class.java)?.takeIf { it.hasVibrator() }
                ?.vibrate(VibrationEffect.createPredefined(fallback))
        }
    }

    private fun chime(context: Context): Long {
        val am = context.getSystemService(AudioManager::class.java) ?: return 0L
        if (am.ringerMode != AudioManager.RINGER_MODE_NORMAL) return 0L
        val t = runCatching { ensureTrack() }.getOrElse { Log.w(TAG, "chime failed", it); return 0L }
        val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(attrs)
            .setOnAudioFocusChangeListener { }
            .build()
        focus?.let { am.abandonAudioFocusRequest(it) }
        focus = req
        am.requestAudioFocus(req)
        return try {
            if (t.playState == AudioTrack.PLAYSTATE_PLAYING) t.stop()
            t.reloadStaticData()
            t.play()
            main.postDelayed({
                if (focus === req) {
                    am.abandonAudioFocusRequest(req)
                    focus = null
                }
            }, CHIME_MS + 80)
            CHIME_MS
        } catch (e: Exception) {
            Log.w(TAG, "chime play failed", e)
            am.abandonAudioFocusRequest(req)
            if (focus === req) focus = null
            0L
        }
    }

    private fun ensureTrack(): AudioTrack {
        track?.let { return it }
        val pcm = synth()
        val t = AudioTrack.Builder()
            .setAudioAttributes(attrs)
            .setAudioFormat(
                AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(RATE).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build()
            )
            .setTransferMode(AudioTrack.MODE_STATIC)
            .setBufferSizeInBytes(pcm.size * 2)
            .build()
        t.write(pcm, 0, pcm.size)
        track = t
        return t
    }

    /**
     * Two soft sine notes a fifth apart (E6 then B6), each with a quick attack and an exponential
     * decay; the second overlaps the first. Quiet on purpose: a confirmation, not a ring.
     */
    internal fun synth(): ShortArray {
        val n = (RATE * CHIME_MS / 1000).toInt()
        val out = ShortArray(n)
        val notes = listOf(Triple(1318.5, 0.0, 0.11), Triple(1975.5, 0.055, 0.115))
        for (i in 0 until n) {
            val t = i.toDouble() / RATE
            var v = 0.0
            for ((f, start, len) in notes) {
                val u = t - start
                if (u < 0 || u > len) continue
                val attack = min(1.0, u / 0.004)
                val decay = exp(-u * 28)
                val tail = min(1.0, (len - u) / 0.01) // no click at the end
                v += sin(2 * PI * f * u) * attack * decay * tail
            }
            out[i] = (v * 0.22 * Short.MAX_VALUE).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
        return out
    }
}
