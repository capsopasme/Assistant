package io.github.capsopasme.assistant.ocr

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtLoggingLevel
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorSpace
import android.graphics.Paint
import android.graphics.Rect
import android.os.SystemClock
import android.util.Log
import io.github.capsopasme.assistant.util.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.coroutines.coroutineContext
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * PP-OCRv6 small (detection + recognition) on ONNX Runtime, CPU.
 *
 * OCR is a one-shot action, so it is tuned for "finish fast, then idle":
 *  - one global thread pool (three threads, no spin-waiting between operators: ORT spins by
 *    default, which burns CPU for nothing on a phone) shared by both models;
 *  - models loaded from a file path (copied out of the APK once per version), so ~20 MB of model
 *    bytes never pass through the Java heap;
 *  - pixels taken raw from the bitmap (no per-pixel colour conversion of a Display P3 screenshot);
 *  - sessions kept for a while after use (setting, default 3 minutes) and then closed so the
 *    native memory goes back. Nothing runs when OCR isn't being used.
 *
 * Models (bundled by CI, see .github/scripts/fetch-ocr-models.sh): assets/ocr/det.onnx,
 * rec.onnx and keys.txt (the recognizer's dictionary, one entry per line).
 */
@OptIn(ExperimentalCoroutinesApi::class)
object OcrEngine {
    private const val TAG = "OcrEngine"
    /** detector input: long side at most this (multiple of 32); screen text stays ≥ ~15 px tall */
    private const val DET_LIMIT = 1280
    private const val REC_H = 48
    private const val REC_MIN_W = 320
    private const val REC_MAX_W = 3200
    private const val REC_BATCH = 8
    private const val MIN_CONF = 0.5f
    private const val THREADS = 3

    class MissingModels : Exception("OCR 模型没有打包进这个版本")

    private val dispatcher = Dispatchers.Default.limitedParallelism(1)
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private var env: OrtEnvironment? = null
    private var det: OrtSession? = null
    private var rec: OrtSession? = null
    private var dict: List<String> = emptyList()
    private var releaseJob: Job? = null

    /** the last detection saw an (almost) entirely black screen: a window that forbids screenshots */
    @Volatile var lastAllBlack = false
        private set

    /** starts loading the models in the background (e.g. while the screenshot is taken) */
    fun preload(context: Context) {
        val app = context.applicationContext
        releaseJob?.cancel()
        scope.launch { runCatching { ensureLoaded(app) }.onFailure { Log.w(TAG, "preload failed", it) } }
    }

    /** text boxes on [shot], in its pixels */
    suspend fun detect(context: Context, shot: Bitmap): List<Box> = withContext(dispatcher) {
        releaseJob?.cancel()
        ensureLoaded(context.applicationContext)
        val t0 = SystemClock.elapsedRealtime()
        val e = env!!
        val s = det!!
        val w = shot.width
        val h = shot.height
        val scale = min(1f, DET_LIMIT.toFloat() / max(w, h))
        val dw = max(32, (w * scale / 32f).roundToInt() * 32)
        val dh = max(32, (h * scale / 32f).roundToInt() * 32)
        val small = if (dw == w && dh == h) shot else Bitmap.createScaledBitmap(shot, dw, dh, true)
        val n = dw * dh
        // raw RGBA bytes, in the bitmap's own colour space: the detector doesn't care about
        // colorimetry, and getPixels() would convert every pixel of a Display P3 screenshot
        val stride = small.rowBytes
        val raw = ByteBuffer.allocateDirect(stride * dh)
        small.copyPixelsToBuffer(raw)
        if (small !== shot) small.recycle()
        // PaddleOCR feeds BGR with ImageNet mean/std applied in that channel order
        val buf = floatBuffer(3 * n)
        val m0 = 0.485f * 255f; val m1 = 0.456f * 255f; val m2 = 0.406f * 255f
        val s0 = 1f / (0.229f * 255f); val s1 = 1f / (0.224f * 255f); val s2 = 1f / (0.225f * 255f)
        var dark = 0
        for (y in 0 until dh) {
            val rowStart = y * stride
            for (x in 0 until dw) {
                val p = rowStart + x * 4 // R G B A
                val r = raw.get(p).toInt() and 0xFF
                val g = raw.get(p + 1).toInt() and 0xFF
                val b = raw.get(p + 2).toInt() and 0xFF
                val i = y * dw + x
                buf.put(i, (b - m0) * s0)
                buf.put(n + i, (g - m1) * s1)
                buf.put(2 * n + i, (r - m2) * s2)
                if (r < 10 && g < 10 && b < 10) dark++
            }
        }
        lastAllBlack = dark > n * 0.97
        coroutineContext.ensureActive()
        val prob = OnnxTensor.createTensor(e, buf, longArrayOf(1, 3, dh.toLong(), dw.toLong())).use { input ->
            s.run(mapOf(s.inputNames.first() to input)).use { out ->
                val t = out[0] as OnnxTensor
                val shape = t.info.shape
                val ow = shape.last().toInt()
                val oh = shape[shape.size - 2].toInt()
                val fb = t.floatBuffer
                val arr = FloatArray(fb.remaining())
                fb.get(arr)
                Triple(arr, ow, oh)
            }
        }
        val (arr, ow, oh) = prob
        val boxes = DbPost.boxes(arr, ow, oh, w.toFloat() / ow, h.toFloat() / oh, w, h)
        OcrStats.detectMs = SystemClock.elapsedRealtime() - t0
        OcrStats.boxes = boxes.size
        boxes
    }

    /**
     * Recognizes [boxes] of [shot] in the given order, a chunk at a time: [onChunk] receives the
     * index of the chunk's first box and its texts ("" when nothing reliable was read) as each
     * chunk finishes, so the overlay can fill in top to bottom.
     */
    suspend fun recognize(
        context: Context, shot: Bitmap, boxes: List<Box>,
        onChunk: suspend (start: Int, texts: List<String>) -> Unit,
    ) = withContext(dispatcher) {
        releaseJob?.cancel()
        ensureLoaded(context.applicationContext)
        val t0 = SystemClock.elapsedRealtime()
        val chunk = REC_BATCH * 2
        var start = 0
        var first = true
        while (start < boxes.size) {
            coroutineContext.ensureActive()
            val end = min(boxes.size, start + chunk)
            val idx = (start until end).sortedBy { boxes[it].w.toFloat() / max(1, boxes[it].h) }
            val texts = arrayOfNulls<String>(end - start)
            for (b in idx.chunked(REC_BATCH)) {
                coroutineContext.ensureActive()
                val res = recBatch(shot, b.map { boxes[it] })
                b.forEachIndexed { k, i -> texts[i - start] = res[k] }
            }
            if (first) { OcrStats.firstChunkMs = SystemClock.elapsedRealtime() - t0; first = false }
            onChunk(start, texts.map { it.orEmpty() })
            start = end
        }
        OcrStats.recognizeMs = SystemClock.elapsedRealtime() - t0
    }

    /**
     * All the text on [shot] in reading order, one paragraph per line (for the model). Null when
     * nothing was found.
     */
    suspend fun readText(context: Context, shot: Bitmap): String? {
        val found = detect(context, shot)
        if (found.isEmpty()) return null
        val blocks = Layout.blocks(found)
        val order = blocks.flatMap { it.parts }
        val texts = MutableList<String?>(found.size) { null }
        recognize(context, shot, order.map { found[it] }) { start, res ->
            res.forEachIndexed { k, s -> texts[order[start + k]] = s }
        }
        return blocks.map { Layout.text(it, texts) }.filter { it.isNotBlank() }.joinToString("\n").ifBlank { null }
    }

    /** closes the sessions after the configured time without use */
    fun scheduleRelease(context: Context) {
        val keep = Prefs.get(context).ocrKeepMs
        releaseJob?.cancel()
        releaseJob = scope.launch {
            delay(keep)
            det?.close(); rec?.close()
            det = null; rec = null
            cached = null // the input buffer (up to ~15 MB of direct memory) goes with them
            Log.i(TAG, "models released")
        }
    }

    private val filter = Paint(Paint.FILTER_BITMAP_FLAG)

    private fun recBatch(shot: Bitmap, boxes: List<Box>): List<String> {
        val e = env!!
        val s = rec!!
        val ratios = boxes.map { it.w.toFloat() / max(1, it.h) }
        val maxRatio = max(REC_MIN_W.toFloat() / REC_H, ratios.max())
        val width = min(REC_MAX_W, ceil(REC_H * maxRatio).toInt())
        val plane = REC_H * width
        val buf = floatBuffer(boxes.size * 3 * plane)
        for (i in 0 until boxes.size * 3 * plane) buf.put(i, 0f) // padding = 0 after normalization, like PaddleOCR
        // same colour space as the screenshot: drawing into it copies pixels without converting them
        val cs = shot.colorSpace ?: ColorSpace.get(ColorSpace.Named.SRGB)
        val line = Bitmap.createBitmap(width, REC_H, Bitmap.Config.ARGB_8888, true, cs)
        val canvas = Canvas(line)
        val stride = line.rowBytes
        val raw = ByteBuffer.allocateDirect(stride * REC_H)
        boxes.forEachIndexed { k, b ->
            val rw = min(width, max(1, ceil(REC_H * ratios[k]).toInt()))
            line.eraseColor(0)
            canvas.drawBitmap(shot, Rect(b.l, b.t, b.r, b.b), Rect(0, 0, rw, REC_H), filter)
            raw.clear()
            line.copyPixelsToBuffer(raw)
            val base = k * 3 * plane
            for (y in 0 until REC_H) {
                val row = y * width
                for (x in 0 until rw) {
                    val p = y * stride + x * 4
                    val o = base + row + x
                    // BGR, (v / 255 - 0.5) / 0.5
                    buf.put(o, (raw.get(p + 2).toInt() and 0xFF) / 127.5f - 1f)
                    buf.put(o + plane, (raw.get(p + 1).toInt() and 0xFF) / 127.5f - 1f)
                    buf.put(o + 2 * plane, (raw.get(p).toInt() and 0xFF) / 127.5f - 1f)
                }
            }
        }
        line.recycle()
        return OnnxTensor.createTensor(e, buf, longArrayOf(boxes.size.toLong(), 3, REC_H.toLong(), width.toLong())).use { input ->
            s.run(mapOf(s.inputNames.first() to input)).use { out ->
                val t = out[0] as OnnxTensor
                val shape = t.info.shape // [N, T, C]
                val steps = shape[1].toInt()
                val classes = shape[2].toInt()
                val fb = t.floatBuffer
                val arr = FloatArray(fb.remaining())
                fb.get(arr)
                List(boxes.size) { k ->
                    val (text, conf) = Ctc.decode(arr, k * steps * classes, steps, classes, dict)
                    if (conf >= MIN_CONF) text.trim() else ""
                }
            }
        }
    }

    private fun environment(): OrtEnvironment {
        env?.let { return it }
        val e = try {
            OrtEnvironment.ThreadingOptions().use { t ->
                t.setGlobalIntraOpNumThreads(THREADS)
                t.setGlobalInterOpNumThreads(1)
                t.setGlobalSpinControl(false)
                OrtEnvironment.getEnvironment(OrtLoggingLevel.ORT_LOGGING_LEVEL_WARNING, "ocr", t)
            }.also { globalThreads = true }
        } catch (ex: Exception) {
            Log.w(TAG, "global thread pool unavailable, per-session threads", ex)
            globalThreads = false
            OrtEnvironment.getEnvironment()
        }
        env = e
        return e
    }

    private var globalThreads = false

    /** the model copied out of the APK into app storage (once per app version) */
    private fun modelFile(ctx: Context, name: String): File {
        val dir = File(ctx.noBackupFilesDir, "ocr")
        val f = File(dir, name)
        val size = runCatching { ctx.assets.openFd("ocr/$name").use { it.length } }.getOrDefault(-1L)
        val stamp = File(dir, "$name.ver")
        val ver = runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).longVersionCode }.getOrDefault(0L).toString()
        if (f.isFile && (size < 0 || f.length() == size) && stamp.isFile && stamp.readText() == ver) return f
        dir.mkdirs()
        val tmp = File(dir, "$name.tmp")
        ctx.assets.open("ocr/$name").use { input -> tmp.outputStream().use { input.copyTo(it, 256 * 1024) } }
        if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
        stamp.writeText(ver)
        return f
    }

    private fun ensureLoaded(ctx: Context) {
        if (det != null && rec != null) return
        val assets = ctx.assets
        val names = runCatching { assets.list("ocr")?.toSet() }.getOrNull().orEmpty()
        if (!names.containsAll(listOf("det.onnx", "rec.onnx", "keys.txt"))) throw MissingModels()
        val t0 = SystemClock.elapsedRealtime()
        val e = environment()
        val opts = OrtSession.SessionOptions().apply {
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            if (globalThreads) disablePerSessionThreads()
            else {
                setIntraOpNumThreads(THREADS)
                setInterOpNumThreads(1)
                addConfigEntry("session.intra_op.allow_spinning", "0")
                addConfigEntry("session.inter_op.allow_spinning", "0")
            }
        }
        if (dict.isEmpty()) {
            dict = assets.open("ocr/keys.txt").bufferedReader().use { r ->
                r.readText().split('\n').map { it.removeSuffix("\r") }.dropLastWhile { it.isEmpty() }
            }
        }
        if (det == null) det = e.createSession(modelFile(ctx, "det.onnx").absolutePath, opts)
        if (rec == null) rec = e.createSession(modelFile(ctx, "rec.onnx").absolutePath, opts)
        opts.close()
        OcrStats.loadMs = SystemClock.elapsedRealtime() - t0
        OcrStats.coldLoads++
        Log.i(TAG, "models loaded in ${OcrStats.loadMs} ms, dict ${dict.size}, global threads $globalThreads")
    }

    /** whether the models are loaded right now (the next OCR starts warm) */
    val loaded: Boolean get() = det != null && rec != null

    private var cached: FloatBuffer? = null
    private fun floatBuffer(n: Int): FloatBuffer {
        cached?.let { if (it.capacity() >= n) { it.clear(); it.limit(n); return it } }
        val b = ByteBuffer.allocateDirect(n * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        cached = b
        return b
    }
}

/** Timings of the last OCR, for the diagnostics page. */
object OcrStats {
    @Volatile var at = 0L
    @Volatile var captureMs = -1L
    @Volatile var loadMs = -1L
    @Volatile var detectMs = -1L
    @Volatile var firstChunkMs = -1L
    @Volatile var recognizeMs = -1L
    @Volatile var boxes = 0
    @Volatile var warm = false
    @Volatile var coldLoads = 0

    fun summary(): String {
        if (at == 0L) return "还没有用过"
        val time = java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date(at))
        fun ms(v: Long) = if (v < 0) "-" else "${v}ms"
        return "$time 截屏 ${ms(captureMs)} · 模型${if (warm) "已在内存" else "加载 ${ms(loadMs)}"} · " +
            "检测 ${ms(detectMs)}（$boxes 处） · 首批文字 ${ms(firstChunkMs)} · 识别完 ${ms(recognizeMs)}"
    }
}
