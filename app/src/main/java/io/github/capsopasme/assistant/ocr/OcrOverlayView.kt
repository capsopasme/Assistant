package io.github.capsopasme.assistant.ocr

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowInsets
import android.view.animation.AnimationUtils
import kotlin.math.PI
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * The OCR overlay: the screenshot shown as a frozen frame exactly where it was, then softly
 * blurred and dimmed while the detected paragraphs light up as crisp rounded blocks.
 *
 *  - tap a block: copy it          - long press: copy everything
 *  - drag across blocks: copy those blocks together
 *  - tap outside the blocks (or back): close
 *
 * Everything is drawn here in one view, driven by the frame clock only while something is
 * animating: a still overlay draws no frames at all.
 */
@SuppressLint("ViewConstructor")
class OcrOverlayView(context: Context, private val shot: Bitmap) : View(context) {

    interface Listener {
        /** copy these blocks ([all] = the long press) */
        fun onCopy(blocks: List<Int>, all: Boolean)
        fun onDismiss()
        /** the close animation has finished */
        fun onClosed()
    }

    var listener: Listener? = null

    private class UiBlock(val rect: RectF) {
        var ready = false     // recognized
        var empty = false     // recognized, nothing readable: fades away
        var tSel = -1L        // selected at (frame time), -1 = not selected
        var tEmpty = -1L
    }

    private val dp = resources.displayMetrics.density
    private val radius = 10 * dp
    private val pad = 4 * dp
    private val accent = runCatching { context.getColor(android.R.color.system_accent1_200) }.getOrDefault(0xFF8AB4F8.toInt())

    private val blurred: Bitmap = run {
        // two-step downscale then bilinear upscale when drawn = a cheap, smooth blur
        val a = Bitmap.createScaledBitmap(shot, max(1, shot.width / 3), max(1, shot.height / 3), true)
        val b = Bitmap.createScaledBitmap(a, max(1, shot.width / 12), max(1, shot.height / 12), true)
        if (a !== b && a !== shot) a.recycle()
        b
    }
    private val shotRect = RectF(0f, 0f, shot.width.toFloat(), shot.height.toFloat())
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val crisp = Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = BitmapShader(shot, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP) }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.5f * dp }
    private val pillBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xE61F1F1F.toInt() }
    private val pillText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 14 * resources.displayMetrics.scaledDensity
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
    }
    private val shimmer = Paint()
    private var shimmerH = -1f
    private val shimmerM = android.graphics.Matrix()
    private val tmp = RectF()

    private var blocks: List<UiBlock> = emptyList()
    private var offX = 0f
    private var offY = 0f

    private fun now() = AnimationUtils.currentAnimationTimeMillis()

    private var tScan = -1L
    private var tReveal = -1L
    private var tClose = -1L
    private var closedSent = false
    private var pill: String? = null
    private var tPill = -1L
    private var hint: String? = null
    private var tHint = -1L

    // --- what the controller tells the view -------------------------------------------------

    fun startScanning() {
        tScan = now()
        invalidate()
    }

    /** paragraph boxes in screen pixels, in reading order */
    fun setBlocks(boxes: List<Box>, hintText: String?) {
        blocks = boxes.map { b -> UiBlock(RectF(b.l - pad, b.t - pad, b.r + pad, b.b + pad)) }
        tReveal = now()
        performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
        if (hintText != null) { hint = hintText; tHint = tReveal + 300 }
        invalidate()
    }

    /** a block's text is known; [empty] = nothing readable in it */
    fun setReady(index: Int, empty: Boolean) {
        val b = blocks.getOrNull(index) ?: return
        b.ready = true
        if (empty && !b.empty) { b.empty = true; b.tEmpty = now() }
        invalidate()
    }

    fun scanningDone() {
        tScan = -1L
        invalidate()
    }

    fun showMessage(text: String) {
        pill = text
        tPill = now()
        hint = null
        invalidate()
    }

    /** highlights the copied blocks and shows what was copied, then closes */
    fun showCopied(indices: Collection<Int>, message: String, wave: Boolean) {
        val t = now()
        indices.forEachIndexed { k, i -> blocks.getOrNull(i)?.let { if (it.tSel < 0) it.tSel = t + if (wave) min(k * 22L, 300L) else 0L } }
        performHapticFeedback(HapticFeedbackConstants.CONFIRM)
        showMessage(message)
        postDelayed({ close() }, if (wave) 650L else 480L)
    }

    fun close() {
        if (tClose >= 0) return
        tClose = now()
        invalidate()
    }

    // --- geometry ---------------------------------------------------------------------------

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        // draw the screenshot exactly where it was on screen, wherever this window starts
        val loc = IntArray(2)
        getLocationOnScreen(loc)
        offX = loc[0].toFloat()
        offY = loc[1].toFloat()
    }

    private fun hit(x: Float, y: Float): Int {
        val sx = x + offX
        val sy = y + offY
        val slop = 6 * dp
        var best = -1
        var bestArea = Float.MAX_VALUE
        blocks.forEachIndexed { i, b ->
            if (b.empty) return@forEachIndexed
            val r = b.rect
            if (sx >= r.left - slop && sx <= r.right + slop && sy >= r.top - slop && sy <= r.bottom + slop) {
                val area = r.width() * r.height()
                if (area < bestArea) { bestArea = area; best = i } // innermost when they overlap
            }
        }
        return best
    }

    // --- touch ------------------------------------------------------------------------------

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var downBlock = -1
    private var dragging = false
    private var longPressed = false
    private val dragSel = LinkedHashSet<Int>()
    private val longPress = Runnable {
        longPressed = true
        if (blocks.any { !it.empty }) {
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            listener?.onCopy(blocks.indices.filter { !blocks[it].empty }, all = true)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (tClose >= 0) return true
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x; downY = e.y
                dragging = false; longPressed = false
                dragSel.clear()
                downBlock = hit(e.x, e.y)
                if (blocks.isNotEmpty()) postDelayed(longPress, ViewConfiguration.getLongPressTimeout().toLong())
            }
            MotionEvent.ACTION_MOVE -> {
                if (longPressed) return true
                if (!dragging && hypot(e.x - downX, e.y - downY) > touchSlop) {
                    dragging = true
                    removeCallbacks(longPress)
                    if (downBlock >= 0) select(downBlock)
                }
                if (dragging) {
                    val i = hit(e.x, e.y)
                    if (i >= 0 && i !in dragSel) select(i)
                }
            }
            MotionEvent.ACTION_UP -> {
                removeCallbacks(longPress)
                if (longPressed) return true
                when {
                    dragging && dragSel.isNotEmpty() -> listener?.onCopy(dragSel.sorted(), all = false)
                    dragging -> {}
                    downBlock >= 0 -> listener?.onCopy(listOf(downBlock), all = false)
                    else -> listener?.onDismiss()
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(longPress)
                clearDragSelection()
            }
        }
        return true
    }

    private fun select(i: Int) {
        dragSel.add(i)
        blocks[i].tSel = now()
        performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        invalidate()
    }

    private fun clearDragSelection() {
        for (i in dragSel) blocks.getOrNull(i)?.tSel = -1L
        dragSel.clear()
        invalidate()
    }

    // --- drawing ----------------------------------------------------------------------------

    private fun ease(x: Float): Float { val c = x.coerceIn(0f, 1f); val i = 1 - c; return 1 - i * i * i }
    private fun lin(t: Long, from: Long, dur: Long): Float = if (from < 0 || t < from) 0f else min(1f, (t - from).toFloat() / dur)

    override fun onDraw(canvas: Canvas) {
        val t = now()
        var more = false

        val closeA = if (tClose >= 0) 1f - lin(t, tClose, 170) else 1f
        if (tClose >= 0) {
            if (closeA <= 0f) {
                if (!closedSent) { closedSent = true; post { listener?.onClosed() } }
                return
            }
            more = true
        }
        val reveal = if (tReveal >= 0) ease(lin(t, tReveal, 260)) else 0f
        if (tReveal >= 0 && reveal < 1f) more = true

        canvas.save()
        canvas.translate(-offX, -offY)
        val alpha = (255 * closeA).toInt()
        // 1. the frozen frame, then the blurred, dimmed copy fading in over it
        bitmapPaint.alpha = alpha
        canvas.drawBitmap(shot, 0f, 0f, bitmapPaint)
        if (reveal > 0f) {
            bitmapPaint.alpha = (alpha * reveal).toInt()
            canvas.drawBitmap(blurred, null, shotRect, bitmapPaint)
            canvas.drawColor(Color.argb((0.42f * reveal * 255 * closeA).toInt(), 0, 0, 0))
        }
        // 2. blocks: the crisp screenshot inside a rounded rect, a soft fill and a hairline
        if (tReveal >= 0) blocks.forEachIndexed { i, b ->
            val appear = ease(lin(t, tReveal + min(i * 18L, 280L), 240))
            if (appear < 1f) more = true
            var a = appear * closeA
            if (b.empty) {
                val fade = lin(t, b.tEmpty, 200)
                if (fade < 1f) more = true
                a *= 1f - fade
            }
            if (a <= 0.01f) return@forEachIndexed
            val sel = if (b.tSel >= 0) ease(lin(t, b.tSel, 160)) else 0f
            if (b.tSel >= 0 && sel < 1f) more = true
            val scale = 0.94f + 0.06f * appear + 0.025f * sel * (1f - sel) * 4f // a small pop when selected
            val r = b.rect
            canvas.save()
            canvas.scale(scale, scale, r.centerX(), r.centerY())
            crisp.alpha = (255 * a).toInt()
            canvas.drawRoundRect(r, radius, radius, crisp)
            fill.color = blend(Color.WHITE, accent, sel)
            fill.alpha = ((0.08f + 0.22f * sel) * 255 * a).toInt()
            canvas.drawRoundRect(r, radius, radius, fill)
            var sa = 0.78f + 0.22f * sel
            if (!b.ready) { sa *= 0.45f + 0.35f * (0.5f + 0.5f * sin(2 * PI * (t % 1000) / 1000.0).toFloat()); more = true }
            stroke.color = blend(Color.WHITE, accent, sel)
            stroke.alpha = (sa * 255 * a).toInt()
            tmp.set(r)
            tmp.inset(stroke.strokeWidth / 2, stroke.strokeWidth / 2)
            canvas.drawRoundRect(tmp, radius, radius, stroke)
            canvas.restore()
        }
        canvas.restore()

        // 3. scanning: a soft light band sweeping down while the text is being found
        if (tScan >= 0 && tClose < 0) {
            val p = ((t - tScan) % 1100L) / 1100f
            val y = -height * 0.2f + p * height * 1.4f
            val band = height * 0.12f
            if (shimmerH != band) { // one gradient, moved with its matrix
                shimmerH = band
                shimmer.shader = LinearGradient(0f, -band, 0f, band,
                    intArrayOf(0x00FFFFFF, 0x2EFFFFFF, 0x00FFFFFF), null, Shader.TileMode.CLAMP)
            }
            shimmerM.setTranslate(0f, y)
            shimmer.shader.setLocalMatrix(shimmerM)
            canvas.drawRect(0f, y - band, width.toFloat(), y + band, shimmer)
            more = true
        }

        // 4. the hint and the message pill at the bottom
        val bottomInset = rootWindowInsets?.getInsets(WindowInsets.Type.navigationBars())?.bottom ?: 0
        var baseline = height - bottomInset - 28 * dp
        pill?.let { text ->
            val pa = ease(lin(t, tPill, 200))
            if (pa < 1f) more = true
            drawPill(canvas, text, baseline + (1 - pa) * 14 * dp, pa * closeA, 1f)
            baseline -= 52 * dp
        }
        hint?.let { text ->
            if (t >= tHint) {
                val inA = lin(t, tHint, 220)
                val outA = 1f - lin(t, tHint + 3200, 420)
                val ha = min(inA, outA)
                if (outA > 0f) more = true else hint = null
                if (ha > 0f) drawPill(canvas, text, baseline, ha * closeA, 0.82f)
            } else more = true
        }
        if (more) postInvalidateOnAnimation()
    }

    private fun drawPill(canvas: Canvas, text: String, bottom: Float, a: Float, size: Float) {
        if (a <= 0f) return
        pillText.textSize = 14 * resources.displayMetrics.scaledDensity * size
        val maxW = width - 48 * dp
        val shown = ellipsize(text, maxW - 40 * dp)
        val tw = pillText.measureText(shown)
        val h = 40 * dp * size
        val w = tw + 40 * dp * size
        val left = (width - w) / 2
        tmp.set(left, bottom - h, left + w, bottom)
        pillBg.alpha = (0xE6 * a).toInt()
        canvas.drawRoundRect(tmp, h / 2, h / 2, pillBg)
        pillText.alpha = (255 * a).toInt()
        val fm = pillText.fontMetrics
        canvas.drawText(shown, left + 20 * dp * size, tmp.centerY() - (fm.ascent + fm.descent) / 2, pillText)
    }

    private fun ellipsize(s: String, maxW: Float): String {
        if (pillText.measureText(s) <= maxW) return s
        var end = s.length
        while (end > 1 && pillText.measureText(s, 0, end) + pillText.measureText("…") > maxW) end--
        return s.substring(0, end) + "…"
    }

    private fun blend(a: Int, b: Int, f: Float): Int {
        val g = f.coerceIn(0f, 1f)
        fun ch(sh: Int) = (((a shr sh) and 0xFF) * (1 - g) + ((b shr sh) and 0xFF) * g).toInt()
        return Color.rgb(ch(16), ch(8), ch(0))
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(longPress)
        super.onDetachedFromWindow()
        if (blurred !== shot) blurred.recycle()
    }
}
