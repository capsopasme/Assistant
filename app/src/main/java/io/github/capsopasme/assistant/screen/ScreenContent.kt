package io.github.capsopasme.assistant.screen

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Base64
import android.util.Log
import io.github.capsopasme.assistant.ocr.OcrEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import kotlin.math.sqrt

/**
 * What was on the screen, for the model: the text read on the phone (exact characters) and, when
 * allowed, the screenshot itself (layout, pictures). DeepSeek scales an image to about
 * 1300 × 1300 pixels' worth anyway, so it is sent at that size as a JPEG.
 */
class ScreenContent(
    /** OCR text, paragraphs in reading order; null = nothing readable */
    val text: String?,
    /** the screenshot, JPEG; null = not sent */
    val jpeg: ByteArray?,
    /** the screen was black: a window that forbids screenshots (banking, passwords) */
    val black: Boolean,
    val at: Long = SystemClock.elapsedRealtime(),
) {
    fun dataUrl(): String? = jpeg?.let { "data:image/jpeg;base64," + Base64.encodeToString(it, Base64.NO_WRAP) }

    /** the text part for the model */
    fun describe(maxChars: Int = MAX_TEXT): String = when {
        black -> "（截屏是全黑的：当前页面禁止截屏，例如密码或支付页面。看不到内容。）"
        text == null -> "（屏幕上没有识别到文字。）" + if (jpeg != null) "截图见图片。" else ""
        else -> buildString {
            append("屏幕上识别到的文字（本机 OCR，按阅读顺序，每行一段；可能有个别错字")
            if (jpeg != null) append("，版面和图片见截图")
            append("）：\n")
            if (text.length > maxChars) append(text.take(maxChars)).append("\n…（后面还有 ${text.length - maxChars} 字，已省略）")
            else append(text)
        }
    }

    companion object {
        private const val TAG = "ScreenContent"
        const val MAX_TEXT = 6000
        /** DeepSeek's working size: about 1300 × 1300 pixels */
        private const val TARGET_PIXELS = 1_690_000.0
        /** reading the text should not hold the question up for long */
        private const val OCR_TIMEOUT_MS = 6_000L

        /**
         * Reads [shot] (not recycled here): OCR and the JPEG at the same time. A slow OCR is given
         * up on rather than keeping the user waiting; the image still goes.
         */
        suspend fun from(context: Context, shot: Bitmap, withImage: Boolean): ScreenContent = coroutineScope {
            val t0 = SystemClock.elapsedRealtime()
            val jpeg = if (withImage) async(Dispatchers.Default) { runCatching { jpeg(shot) }.getOrNull() } else null
            val text = withTimeoutOrNull(OCR_TIMEOUT_MS) {
                runCatching { OcrEngine.readText(context, shot) }.onFailure { Log.w(TAG, "ocr failed", it) }.getOrNull()
            }
            val black = OcrEngine.lastAllBlack && text == null
            val img = if (black) null else jpeg?.await()
            Log.i(TAG, "screen read in ${SystemClock.elapsedRealtime() - t0} ms, text ${text?.length ?: 0}, jpeg ${img?.size ?: 0}")
            ScreenContent(text, img, black)
        }

        fun jpeg(bmp: Bitmap): ByteArray {
            val px = bmp.width.toDouble() * bmp.height
            val scale = if (px > TARGET_PIXELS) sqrt(TARGET_PIXELS / px) else 1.0
            val src = if (scale < 1.0) Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt(), (bmp.height * scale).toInt(), true) else bmp
            val out = ByteArrayOutputStream(256 * 1024)
            src.compress(Bitmap.CompressFormat.JPEG, 82, out)
            if (src !== bmp) src.recycle()
            return out.toByteArray()
        }

        /** a content made of text only (e.g. an OCR result already on screen) */
        suspend fun fromText(text: String?, shot: Bitmap?, withImage: Boolean): ScreenContent = withContext(Dispatchers.Default) {
            ScreenContent(text?.ifBlank { null }, if (withImage && shot != null) runCatching { jpeg(shot) }.getOrNull() else null, false)
        }
    }
}

/**
 * Does a request refer to what is on the screen? Clear phrasings only: everything else can still
 * reach the screen through the model's read_screen tool.
 */
object ScreenIntent {
    private val PATTERNS = listOf(
        "屏幕", "这个页面", "这一页", "这页", "当前页面", "页面上", "这个界面", "界面上", "这上面", "上面写", "上面说",
        "图里", "图上", "图片里", "这张图", "这个图", "截图", "看看这个", "看一下这个", "看下这个", "你看这个", "帮我看看",
        "帮我看一下", "你看看", "看得到", "看到了吗", "能看到", "这篇", "这段话", "这段", "这句话", "这句", "这条消息",
        "这条", "这个人说", "他说的", "她说的", "这个表格", "这个题", "这道题", "这个公式", "这个单词", "这个词",
    )
    /** "翻译一下" / "总结一下" alone (nothing else to work on) also mean the screen */
    private val BARE = Regex("^(帮我|请|麻烦)?(翻译|总结|概括|解释|解读|分析|读|念|讲讲|说说)(一下)?(吧|呗)?$")
    private val FRESH = Regex("(再看一下|再看看|重新看|再截|刷新|现在呢|换了|变了)")
    private val PUNCT = Regex("[\\s\\p{Punct}，。！？、：；~～“”‘’…]+")

    /** about the screen as a device ("屏幕亮一点", "锁屏"), not about what it shows */
    private val DEVICE = Regex("屏幕(调|亮|暗|常亮|旋转|超时|刷新率|分辨率|录制|录屏|保护|尺寸|坏|碎|颜色模式)|(关|熄|锁|息|亮)屏|关(掉|闭)?屏幕|截个?屏(?!幕)")

    fun matches(text: String): Boolean {
        val t = text.lowercase().replace(PUNCT, "")
        if (t.isEmpty() || DEVICE.containsMatchIn(t)) return false
        return PATTERNS.any { t.contains(it) } || BARE.matches(t)
    }

    /** the user wants a new look rather than the one taken earlier in this conversation */
    fun wantsFresh(text: String): Boolean = FRESH.containsMatchIn(text.lowercase().replace(PUNCT, ""))
}

/**
 * Takes a screenshot of what is under [window]'s content: the window is made fully transparent,
 * a couple of frames are let through so the change is really on screen, the screenshot is taken,
 * and the window comes back. The panel blinks for about a tenth of a second.
 */
suspend fun captureBehind(decor: android.view.View?): Bitmap {
    if (decor == null) return io.github.capsopasme.assistant.ocr.ScreenCapture.capture()
    val alpha = decor.alpha
    decor.alpha = 0f
    try {
        repeat(2) { awaitFrame() }
        kotlinx.coroutines.delay(40) // SurfaceFlinger latches the transparent frame on a later vsync
        return io.github.capsopasme.assistant.ocr.ScreenCapture.capture()
    } finally {
        decor.alpha = alpha
    }
}

private suspend fun awaitFrame() = kotlinx.coroutines.suspendCancellableCoroutine<Unit> { cont ->
    val ch = android.view.Choreographer.getInstance() // main thread: the caller's
    val cb = android.view.Choreographer.FrameCallback { if (cont.isActive) cont.resumeWith(Result.success(Unit)) }
    ch.postFrameCallback(cb)
    cont.invokeOnCancellation { ch.removeFrameCallback(cb) }
}
