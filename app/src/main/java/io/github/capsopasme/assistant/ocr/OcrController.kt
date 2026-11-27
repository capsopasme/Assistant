package io.github.capsopasme.assistant.ocr

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import android.view.ViewGroup
import io.github.capsopasme.assistant.panel.PanelHost
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * One OCR session in the assistant's window: shows [shot] frozen, finds the text, groups it into
 * paragraph blocks and recognizes them top to bottom; a tap copies a block, a long press copies
 * everything. Closes after copying, on a tap outside the blocks, on back, or when triggered again.
 */
class OcrController(
    private val context: Context,
    root: ViewGroup,
    private val shot: Bitmap,
    private val host: PanelHost,
) {
    private val view = OcrOverlayView(context, shot)
    private var blocks: List<Block> = emptyList()
    private var texts: MutableList<String?> = mutableListOf()
    private var blockText: Array<String?> = emptyArray()
    private var pendingCopy: Pair<List<Int>, Boolean>? = null
    private var job: Job? = null
    private var disposed = false

    init {
        root.addView(view, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        view.listener = object : OcrOverlayView.Listener {
            override fun onCopy(blocks: List<Int>, all: Boolean) = copy(blocks, all)
            override fun onDismiss() = view.close()
            override fun onClosed() = host.close()
        }
        active = this
        job = host.scope.launch { run() }
    }

    private suspend fun run() {
        view.startScanning()
        try {
            val found = OcrEngine.detect(context, shot)
            view.scanningDone()
            if (found.isEmpty()) {
                view.showMessage("没有识别到文字")
                delay(1300)
                view.close()
                return
            }
            blocks = Layout.blocks(found)
            texts = MutableList(found.size) { null }
            blockText = arrayOfNulls(blocks.size)
            view.setBlocks(blocks.map { it.box }, hint())
            // recognize in reading order, so the blocks at the top are ready first
            val order = blocks.flatMap { it.parts }
            OcrEngine.recognize(context, shot, order.map { found[it] }) { start, res ->
                withContext(Dispatchers.Main) {
                    if (disposed) return@withContext
                    res.forEachIndexed { k, s -> texts[order[start + k]] = s }
                    blocks.forEachIndexed { i, b ->
                        if (blockText[i] == null && b.parts.all { texts[it] != null }) {
                            val t = Layout.text(b, texts)
                            blockText[i] = t
                            view.setReady(i, empty = t.isEmpty())
                        }
                    }
                    pendingCopy?.let { (idx, all) -> if (idx.all { blockText[it] != null }) { pendingCopy = null; copy(idx, all) } }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: OcrEngine.MissingModels) {
            fail(e.message ?: "OCR 模型缺失")
        } catch (e: Throwable) {
            Log.w(TAG, "ocr failed", e)
            fail("识别失败：${e.message ?: e.javaClass.simpleName}")
        }
    }

    private suspend fun fail(message: String) {
        view.scanningDone()
        view.showMessage(message)
        delay(1800)
        view.close()
    }

    private fun copy(indices: List<Int>, all: Boolean) {
        if (disposed || indices.isEmpty()) return
        if (indices.any { blockText.getOrNull(it) == null }) {
            // still being recognized: copy as soon as it's done
            pendingCopy = indices to all
            view.showMessage("正在识别…")
            return
        }
        val parts = indices.mapNotNull { blockText[it]?.takeIf(String::isNotEmpty) }
        if (parts.isEmpty()) {
            view.showMessage("这里没有识别出文字")
            return
        }
        val text = parts.joinToString("\n")
        val cm = context.getSystemService(ClipboardManager::class.java)
        cm?.setPrimaryClip(ClipData.newPlainText("文字识别", text))
        val msg = when {
            all -> "已复制全部 · ${parts.size} 段"
            parts.size > 1 -> "已复制 ${parts.size} 段"
            else -> "已复制 · " + text.replace('\n', ' ')
        }
        view.showCopied(indices, msg, wave = all)
    }

    /** the gesture hint, for the first few times */
    private fun hint(): String? {
        val sp = context.getSharedPreferences("ocr", Context.MODE_PRIVATE)
        val n = sp.getInt("hints", 0)
        if (n >= 3) return null
        sp.edit().putInt("hints", n + 1).apply()
        return "点按复制 · 长按复制全部 · 划过多段一起复制"
    }

    /** triggered again while open: close (the same as tapping outside) */
    fun closeAnimated() = view.close()

    fun dispose() {
        if (disposed) return
        disposed = true
        if (active === this) active = null
        val j = job
        j?.cancel()
        (view.parent as? ViewGroup)?.removeView(view)
        // the screenshot may still be in use by a recognition step finishing on the OCR thread
        OcrLauncher.recycleAfter(j, shot)
        OcrEngine.scheduleRelease()
    }

    companion object {
        private const val TAG = "OcrController"
        @Volatile var active: OcrController? = null
            private set
    }
}
