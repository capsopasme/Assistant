package io.github.capsopasme.assistant.ocr

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
 * OCR is a one-shot action, so it is tuned for "finish fast, then idle": three threads on the big
 * cores, no spin-waiting between operators (ORT spins by default, which burns CPU for nothing on
 * a phone), sessions kept for a minute after use and then closed so the native memory goes back.
 * Every call runs on one thread of its own; nothing runs when OCR isn't being used.
 *
 * Models (bundled by CI, see .github/scripts/fetch-ocr-models.sh): assets/ocr/det.onnx,
 * rec.onnx and keys.txt (the recognizer's dictionary, one entry per line).
 */
@OptIn(ExperimentalCoroutinesApi::class)
object OcrEngine {
    private const val TAG = "OcrEngine"
    private const val KEEP_MS = 60_000L
    /** detector input: long side at most this (multiple of 32); screen text stays ≥ ~15 px tall */
    private const val DET_LIMIT = 1280
    private const val REC_H = 48
    private const val REC_MIN_W = 320
    private const val REC_MAX_W = 3200
    private const val REC_BATCH = 8
    private const val MIN_CONF = 0.5f

    class MissingModels : Exception("OCR 模型没有打包进这个版本")

    private val dispatcher = Dispatchers.Default.limitedParallelism(1)
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private var env: OrtEnvironment? = null
    private var det: OrtSession? = null
    private var rec: OrtSession? = null
    private var dict: List<String> = emptyList()
    private var releaseJob: Job? = null

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
        val e = env!!
        val s = det!!
        val w = shot.width
        val h = shot.height
        val scale = min(1f, DET_LIMIT.toFloat() / max(w, h))
        val dw = max(32, (w * scale / 32f).roundToInt() * 32)
        val dh = max(32, (h * scale / 32f).roundToInt() * 32)
        val small = Bitmap.createScaledBitmap(shot, dw, dh, true)
        val px = IntArray(dw * dh)
        small.getPixels(px, 0, dw, 0, 0, dw, dh)
        if (small !== shot) small.recycle()
        // PaddleOCR feeds BGR with ImageNet mean/std applied in that channel order
        val n = dw * dh
        val buf = floatBuffer(3 * n)
        val m0 = 0.485f; val m1 = 0.456f; val m2 = 0.406f
        val s0 = 1f / (0.229f * 255f); val s1 = 1f / (0.224f * 255f); val s2 = 1f / (0.225f * 255f)
        for (i in 0 until n) {
            val c = px[i]
            buf.put(i, ((c and 0xFF) - m0 * 255f) * s0)                 // B
            buf.put(n + i, (((c shr 8) and 0xFF) - m1 * 255f) * s1)     // G
            buf.put(2 * n + i, (((c shr 16) and 0xFF) - m2 * 255f) * s2) // R
        }
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
        DbPost.boxes(arr, ow, oh, w.toFloat() / ow, h.toFloat() / oh, w, h)
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
        val chunk = REC_BATCH * 2
        var start = 0
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
            onChunk(start, texts.map { it.orEmpty() })
            start = end
        }
    }

    /** closes the sessions after a minute without use */
    fun scheduleRelease() {
        releaseJob?.cancel()
        releaseJob = scope.launch {
            delay(KEEP_MS)
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
        val line = Bitmap.createBitmap(width, REC_H, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(line)
        val px = IntArray(plane)
        boxes.forEachIndexed { k, b ->
            val rw = min(width, max(1, ceil(REC_H * ratios[k]).toInt()))
            line.eraseColor(0)
            canvas.drawBitmap(shot, Rect(b.l, b.t, b.r, b.b), Rect(0, 0, rw, REC_H), filter)
            line.getPixels(px, 0, width, 0, 0, width, REC_H)
            val base = k * 3 * plane
            for (y in 0 until REC_H) {
                val row = y * width
                for (x in 0 until rw) {
                    val c = px[row + x]
                    val o = base + row + x
                    // BGR, (v / 255 - 0.5) / 0.5
                    buf.put(o, (c and 0xFF) / 127.5f - 1f)
                    buf.put(o + plane, ((c shr 8) and 0xFF) / 127.5f - 1f)
                    buf.put(o + 2 * plane, ((c shr 16) and 0xFF) / 127.5f - 1f)
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

    private fun ensureLoaded(ctx: Context) {
        if (det != null && rec != null) return
        val assets = ctx.assets
        val names = runCatching { assets.list("ocr")?.toSet() }.getOrNull().orEmpty()
        if (!names.containsAll(listOf("det.onnx", "rec.onnx", "keys.txt"))) throw MissingModels()
        val t0 = System.nanoTime()
        val e = env ?: OrtEnvironment.getEnvironment().also { env = it }
        val opts = OrtSession.SessionOptions().apply {
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            setIntraOpNumThreads(3)
            setInterOpNumThreads(1)
            addConfigEntry("session.intra_op.allow_spinning", "0")
            addConfigEntry("session.inter_op.allow_spinning", "0")
        }
        if (dict.isEmpty()) {
            dict = assets.open("ocr/keys.txt").bufferedReader().use { r ->
                r.readText().split('\n').map { it.removeSuffix("\r") }.dropLastWhile { it.isEmpty() }
            }
        }
        if (det == null) det = e.createSession(assets.open("ocr/det.onnx").use { it.readBytes() }, opts)
        if (rec == null) rec = e.createSession(assets.open("ocr/rec.onnx").use { it.readBytes() }, opts)
        opts.close()
        Log.i(TAG, "models loaded in ${(System.nanoTime() - t0) / 1_000_000} ms, dict ${dict.size}")
    }

    private var cached: FloatBuffer? = null
    private fun floatBuffer(n: Int): FloatBuffer {
        cached?.let { if (it.capacity() >= n) { it.clear(); it.limit(n); return it } }
        val b = ByteBuffer.allocateDirect(n * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        cached = b
        return b
    }
}
