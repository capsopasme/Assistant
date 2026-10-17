package io.github.capsopasme.assistant.voice

import android.Manifest
import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.RemoteException
import android.util.Log
import kotlin.math.sqrt

/**
 * Dictation through the offline recognizer of Audio Input (capsopasme/audio_input), without its
 * keyboard: this app records the microphone and streams 16 kHz PCM to Audio Input's
 * RecognizerBridgeService, which recognizes with the model chosen in Audio Input's settings.
 *
 * The binding is reference-counted ([retain] / [release]). While anything holds it, Audio Input's
 * recognizer process stays alive with its model loaded, so speaking doesn't wait for a reload.
 * [setResident] keeps it held for good (from the keep-alive service).
 *
 * Main thread only; callbacks arrive on the main thread.
 */
object VoiceInput {
    private const val TAG = "VoiceInput"
    const val AUDIO_INPUT_PKG = "io.github.capsopasme.audioinput"
    private val BRIDGE = ComponentName(AUDIO_INPUT_PKG, "$AUDIO_INPUT_PKG.asr.RecognizerBridgeService")

    // message protocol, shared with Audio Input (asr/VoiceProtocol.kt)
    private const val MSG_START = 1
    private const val MSG_AUDIO = 2
    private const val MSG_STOP = 3
    private const val MSG_CANCEL = 4
    private const val MSG_PRELOAD = 6
    private const val EVT_LOADING = 101
    private const val EVT_READY = 102
    private const val EVT_PARTIAL = 103
    private const val EVT_FINAL = 104
    private const val EVT_ERROR = 105
    private const val EVT_DONE = 106
    private const val KEY_PARTIAL = "partial"
    private const val KEY_IDLE_STOP_MS = "idle_stop_ms"
    private const val KEY_AUTO_STOPPED = "auto_stopped"
    private const val KEY_PCM16 = "pcm16"
    private const val KEY_TEXT = "text"
    private const val KEY_MESSAGE = "message"

    private const val SAMPLE_RATE = 16000
    private const val CHUNK = SAMPLE_RATE / 10 // 100 ms
    private const val MAX_PENDING = 300

    interface Listener {
        fun onLoading() {}
        fun onListening() {}
        /** 0..1, about ten times a second */
        fun onLevel(level: Float) {}
        fun onPartial(text: String) {}
        fun onFinal(text: String) {}
        /** @param autoStopped nobody spoke for a while */
        fun onDone(autoStopped: Boolean) {}
        fun onError(message: String) {}
    }

    enum class Availability { OK, NOT_INSTALLED, TOO_OLD }

    fun availability(context: Context): Availability {
        val pm = context.packageManager
        val installed = runCatching { pm.getPackageInfo(AUDIO_INPUT_PKG, 0); true }.getOrDefault(false)
        if (!installed) return Availability.NOT_INSTALLED
        val hasBridge = pm.resolveService(Intent().setComponent(BRIDGE), 0) != null
        return if (hasBridge) Availability.OK else Availability.TOO_OLD
    }

    fun hasMicPermission(context: Context) =
        context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private val main = Handler(Looper.getMainLooper())
    private var appContext: Context? = null
    private var refs = 0
    private var resident = false
    private var bound = false
    private var service: Messenger? = null
    private val pending = ArrayDeque<Message>()

    private var listener: Listener? = null
    private var sessionId = 0
    private var nextId = 1
    private var recorder: Recorder? = null

    private val incoming = Messenger(Handler(Looper.getMainLooper()) { msg -> handleEvent(msg); true })

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder) {
            service = Messenger(binder)
            while (pending.isNotEmpty()) send(pending.removeFirst())
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            pending.clear()
            if (sessionId != 0) failSession("语音识别服务断开了")
            // the system rebinds by itself while the binding exists
        }

        override fun onBindingDied(name: ComponentName?) {
            // the package was updated or removed: bind afresh
            unbindNow()
            if (refs > 0) bind()
        }
    }

    private val unbindLater = Runnable { if (refs == 0) unbindNow() }

    /** Starts / keeps the binding. */
    fun retain(context: Context) {
        appContext = context.applicationContext
        refs++
        main.removeCallbacks(unbindLater)
        bind()
    }

    /** Drops a [retain]; the binding goes [delayMs] after the last one is released. */
    fun release(delayMs: Long = 0) {
        if (refs == 0) return
        refs--
        if (refs == 0) {
            main.removeCallbacks(unbindLater)
            if (delayMs <= 0) unbindNow() else main.postDelayed(unbindLater, delayMs)
        }
    }

    /** Keeps the model loaded for good (or stops doing so). */
    fun setResident(context: Context, on: Boolean) {
        if (on == resident) return
        resident = on
        if (on) {
            if (availability(context) != Availability.OK) { resident = false; return }
            retain(context)
            preload()
        } else {
            release()
        }
    }

    /** Loads the model now, so the first sentence doesn't wait for it. */
    fun preload() {
        if (!bound || sessionId != 0) return
        enqueue(Message.obtain(null, MSG_PRELOAD, 0, 0).apply { data = Bundle() })
    }

    private fun bind() {
        if (bound) return
        val ctx = appContext ?: return
        bound = try {
            ctx.bindService(Intent().setComponent(BRIDGE), connection, Context.BIND_AUTO_CREATE or Context.BIND_IMPORTANT)
        } catch (e: SecurityException) {
            false
        }
        if (!bound) runCatching { ctx.unbindService(connection) }
    }

    private fun unbindNow() {
        if (!bound) return
        if (sessionId != 0) cancel()
        runCatching { appContext?.unbindService(connection) }
        bound = false
        service = null
        pending.clear()
    }

    val isListening get() = sessionId != 0

    /**
     * Starts listening. The caller has checked [availability] and the microphone permission.
     * @param idleStopMs end the session when nobody speaks for this long
     */
    fun start(context: Context, listener: Listener, idleStopMs: Int): Boolean {
        if (sessionId != 0) cancel()
        appContext = context.applicationContext
        if (!bound) bind()
        if (!bound) {
            listener.onError("连不上离线语音输入")
            return false
        }
        this.listener = listener
        val id = nextId++.also { if (nextId == 0) nextId = 1 }
        sessionId = id
        enqueue(Message.obtain(null, MSG_START, id, 0).apply {
            data = Bundle().apply {
                putBoolean(KEY_PARTIAL, true)
                putInt(KEY_IDLE_STOP_MS, idleStopMs)
            }
        })
        val rec = Recorder(id)
        recorder = rec
        if (!rec.start()) {
            failSession("无法打开麦克风（可能正被其他应用使用）")
            return false
        }
        return true
    }

    /** Stops listening; the sentence being spoken is still recognized and delivered. */
    fun stop() {
        val id = sessionId
        if (id == 0) return
        // the recorder posts its last chunk, then MSG_STOP, in order on the main thread
        recorder?.finish { enqueue(Message.obtain(null, MSG_STOP, id, 0).apply { data = Bundle() }) }
            ?: enqueue(Message.obtain(null, MSG_STOP, id, 0).apply { data = Bundle() })
        recorder = null
    }

    /** Stops listening and drops whatever hasn't been recognized yet. */
    fun cancel() {
        val id = sessionId
        if (id == 0) return
        recorder?.abort()
        recorder = null
        enqueue(Message.obtain(null, MSG_CANCEL, id, 0).apply { data = Bundle() })
        sessionId = 0
        listener = null
    }

    private fun failSession(message: String) {
        val l = listener
        recorder?.abort()
        recorder = null
        if (sessionId != 0) enqueue(Message.obtain(null, MSG_CANCEL, sessionId, 0).apply { data = Bundle() })
        sessionId = 0
        listener = null
        l?.onError(message)
    }

    private fun sendAudio(pcm: ShortArray, id: Int) {
        if (id != sessionId) return
        if (service == null && pending.size >= MAX_PENDING) return
        enqueue(Message.obtain(null, MSG_AUDIO, id, 0).apply { data = Bundle().apply { putShortArray(KEY_PCM16, pcm) } })
    }

    private fun enqueue(m: Message) {
        if (service != null) send(m) else if (bound) pending.addLast(m)
    }

    private fun send(m: Message) {
        m.replyTo = incoming
        try {
            service?.send(m)
        } catch (e: RemoteException) {
            Log.w(TAG, "send failed", e)
        }
    }

    private fun handleEvent(msg: Message) {
        if (msg.arg1 == 0 || msg.arg1 != sessionId) return
        val l = listener ?: return
        val d = msg.data
        when (msg.what) {
            EVT_LOADING -> l.onLoading()
            EVT_READY -> l.onListening()
            EVT_PARTIAL -> l.onPartial(d.getString(KEY_TEXT).orEmpty())
            EVT_FINAL -> l.onFinal(d.getString(KEY_TEXT).orEmpty())
            EVT_ERROR -> {
                recorder?.abort()
                recorder = null
                sessionId = 0
                listener = null
                val m = d.getString(KEY_MESSAGE).orEmpty()
                l.onError(if (m == "not allowed") "离线语音输入拒绝了请求" else m.ifBlank { "识别出错" })
            }
            EVT_DONE -> {
                recorder?.abort()
                recorder = null
                sessionId = 0
                listener = null
                l.onDone(d.getBoolean(KEY_AUTO_STOPPED, false))
            }
        }
    }

    /** Microphone capture on its own thread; chunks are handed to the main thread in order. */
    private class Recorder(private val id: Int) {
        @Volatile private var running = true
        @Volatile private var onFinished: (() -> Unit)? = null
        private var thread: Thread? = null

        @SuppressLint("MissingPermission")
        fun start(): Boolean {
            val min = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            if (min <= 0) return false
            val record = try {
                AudioRecord(
                    MediaRecorder.AudioSource.VOICE_RECOGNITION, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, maxOf(min, CHUNK * 2 * 4),
                )
            } catch (e: Exception) {
                return false
            }
            if (record.state != AudioRecord.STATE_INITIALIZED) {
                record.release()
                return false
            }
            try {
                record.startRecording()
            } catch (e: Exception) {
                record.release()
                return false
            }
            if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                record.release()
                return false
            }
            thread = Thread({ loop(record) }, "voice-capture").apply { start() }
            return true
        }

        private fun loop(record: AudioRecord) {
            var silentSamples = 0
            var anySound = false
            try {
                while (running) {
                    val buf = ShortArray(CHUNK)
                    var filled = 0
                    while (filled < CHUNK && running) {
                        val n = record.read(buf, filled, CHUNK - filled)
                        if (n < 0) throw IllegalStateException("read error $n")
                        filled += n
                    }
                    if (filled == 0) break
                    val chunk = if (filled == CHUNK) buf else buf.copyOf(filled)
                    var sum = 0.0
                    var nonZero = false
                    for (s in chunk) {
                        sum += s.toDouble() * s
                        if (s.toInt() != 0) nonZero = true
                    }
                    if (nonZero) anySound = true else silentSamples += chunk.size
                    val rms = sqrt(sum / chunk.size) / 32768.0
                    val level = (rms * 12).coerceIn(0.0, 1.0).toFloat()
                    main.post {
                        sendAudio(chunk, id)
                        if (id == sessionId) listener?.onLevel(level)
                    }
                    // exact zeros for 2 s: the microphone is muted by the privacy toggle or held by a call
                    if (!anySound && silentSamples >= SAMPLE_RATE * 2) {
                        main.post { if (id == sessionId) failSession("麦克风没有声音（被通话占用或被系统静音）") }
                        running = false
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "capture failed", e)
                main.post { if (id == sessionId) failSession("录音中断：${e.message ?: "未知错误"}") }
            } finally {
                runCatching { record.stop() }
                record.release()
                onFinished?.let { done -> main.post(done) }
            }
        }

        /** Ends capture; [then] runs on the main thread after the last chunk was handed over. */
        fun finish(then: () -> Unit) {
            onFinished = then
            running = false
        }

        fun abort() {
            onFinished = null
            running = false
        }
    }
}
