package io.github.capsopasme.assistant.share

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.media.ExifInterface
import android.os.ParcelFileDescriptor
import android.util.Log
import io.github.capsopasme.assistant.ocr.OcrEngine
import io.github.capsopasme.assistant.screen.ScreenContent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.util.zip.ZipFile
import kotlin.coroutines.coroutineContext
import kotlin.math.max
import kotlin.math.min

/** A file (or text) shared to the assistant, copied into the app's cache by [ShareActivity]. */
data class SharedFile(val path: String, val name: String, val mime: String?, val size: Long)

/** What a shared item became for the model. */
data class Attachment(
    val name: String,
    /** 文本 / 图片 / PDF / 文档 / 文字 */
    val kind: String,
    val text: String?,
    /** JPEGs (an image, or the pages of a scanned PDF) */
    val images: List<ByteArray> = emptyList(),
    /** a short line for the bubble: "12 页 · 约 3.2 万字", "已截取前 15 万字", "不支持的格式" */
    val note: String,
    val usable: Boolean = true,
)

/**
 * Turns shared files into text (and images) for DeepSeek, which reads text and images but no
 * documents: plain text in any common encoding, images (sent as an image plus their OCR text),
 * PDFs (the PDF's own text; scanned PDFs as page images), Office / OpenDocument / EPUB (the text
 * inside the zip). Everything else is named but not sent.
 */
object Attachments {
    private const val TAG = "Attachments"
    /** total text sent along; DeepSeek takes 1M tokens, but a huge prompt is slow and costly */
    const val MAX_CHARS = 150_000
    private const val MAX_TEXT_BYTES = 4L * 1024 * 1024
    private const val MAX_PDF_IMAGES = 6
    private const val PAGE_PX = 1600

    private val TEXT_EXT = setOf(
        "txt", "md", "markdown", "csv", "tsv", "json", "xml", "yaml", "yml", "ini", "conf", "cfg", "log", "srt", "ass",
        "lrc", "html", "htm", "kt", "kts", "java", "py", "js", "ts", "c", "h", "cpp", "hpp", "cs", "go", "rs", "sh",
        "bat", "ps1", "sql", "gradle", "properties", "toml", "tex", "rtf", "vtt", "m", "swift", "dart", "lua", "php", "rb",
    )
    private val IMAGE_EXT = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "heic", "heif")
    private val ZIP_EXT = setOf("docx", "pptx", "xlsx", "odt", "odp", "ods", "epub")

    suspend fun extract(context: Context, files: List<SharedFile>, sharedText: String?, withImages: Boolean): List<Attachment> =
        withContext(Dispatchers.IO) {
            val out = ArrayList<Attachment>()
            var budget = MAX_CHARS
            sharedText?.trim()?.takeIf { it.isNotEmpty() }?.let {
                val t = it.take(budget)
                budget -= t.length
                out += Attachment("分享的文字", "文字", t, note = "${it.length} 字")
            }
            for (f in files) {
                coroutineContext.ensureActive()
                val a = runCatching { one(context, f, budget, withImages) }.getOrElse { e ->
                    Log.w(TAG, "extract ${f.name} failed", e)
                    Attachment(f.name, "文件", null, note = "读取失败：${e.message ?: e.javaClass.simpleName}", usable = false)
                }
                budget -= a.text?.length ?: 0
                out += a
            }
            out
        }

    private suspend fun one(context: Context, f: SharedFile, budget: Int, withImages: Boolean): Attachment {
        if (f.path.isEmpty()) return Attachment(f.name, "文件", null, note = "文件太大（超过 60 MB），没有读取", usable = false)
        val ext = f.name.substringAfterLast('.', "").lowercase()
        val mime = f.mime?.lowercase().orEmpty()
        return when {
            mime.startsWith("image/") || ext in IMAGE_EXT -> image(context, f, withImages)
            mime == "application/pdf" || ext == "pdf" -> pdf(f, budget, withImages)
            ext in ZIP_EXT || mime.contains("officedocument") || mime.contains("opendocument") || mime == "application/epub+zip" -> zipped(f, ext, budget)
            mime.startsWith("text/") || ext in TEXT_EXT || mime.contains("json") || mime.contains("xml") || mime.contains("javascript") -> text(f, ext, budget)
            mime.startsWith("audio/") || mime.startsWith("video/") ->
                Attachment(f.name, "媒体", null, note = "暂不支持音频和视频", usable = false)
            else -> sniffText(f, budget) ?: Attachment(f.name, "文件", null, note = "不支持的格式", usable = false)
        }
    }

    // --- text -------------------------------------------------------------------------------

    private fun text(f: SharedFile, ext: String, budget: Int): Attachment {
        val file = File(f.path)
        val bytes = file.inputStream().use { it.readNBytesCompat(MAX_TEXT_BYTES) }
        var s = decode(bytes)
        if (ext == "html" || ext == "htm") s = stripMarkup(s)
        return textAttachment(f.name, "文本", s, budget, extra = if (file.length() > MAX_TEXT_BYTES) "（文件很大，只读了开头）" else "")
    }

    /** an unknown type that turns out to be text */
    private fun sniffText(f: SharedFile, budget: Int): Attachment? {
        val file = File(f.path)
        if (file.length() > MAX_TEXT_BYTES) return null
        val bytes = file.readBytes()
        if (bytes.isEmpty() || bytes.take(4096).count { it.toInt() == 0 } > 0) return null
        return textAttachment(f.name, "文本", decode(bytes), budget)
    }

    private fun textAttachment(name: String, kind: String, s: String, budget: Int, extra: String = "", prefix: String = ""): Attachment {
        val clean = s.replace("\r\n", "\n").replace(Regex("\n{3,}"), "\n\n").trim()
        if (clean.isEmpty()) return Attachment(name, kind, null, note = "${prefix}没有文字内容", usable = false)
        val cut = clean.length > budget
        val t = if (cut) clean.take(max(0, budget)) else clean
        val note = prefix + "约 ${count(clean.length)}" + (if (cut) "，已截取前 ${count(t.length)}" else "") + extra
        return Attachment(name, kind, t.ifEmpty { null }, note = note, usable = t.isNotEmpty())
    }

    private fun count(n: Int) = if (n >= 10_000) "%.1f 万字".format(n / 10_000.0) else "$n 字"

    /** UTF-8 (with or without BOM), UTF-16 with BOM, else GB18030 (covers GBK / GB2312) */
    fun decode(bytes: ByteArray): String {
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) return String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) return String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
        val start = if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) 3 else 0
        return try {
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes, start, bytes.size - start)).toString()
        } catch (_: CharacterCodingException) {
            // a file cut at MAX_TEXT_BYTES may end mid-character: allow that one, else GB18030
            val lenient = String(bytes, start, bytes.size - start, Charsets.UTF_8)
            val bad = lenient.count { it == '\uFFFD' }
            if (bad <= 2) lenient else String(bytes, start, bytes.size - start, Charset.forName("GB18030"))
        }
    }

    // --- markup -----------------------------------------------------------------------------

    private val BLOCK_END = Regex("(?i)</(w:p|a:p|text:p|text:h|p|div|li|tr|h[1-6]|br|table|section|article)\\s*>|<(br|w:br|w:cr|text:line-break)\\s*/?>")
    private val CELL_END = Regex("(?i)</(w:tc|td|th|table:table-cell|c)\\s*>|<w:tab\\s*/>|<text:tab\\s*/>")
    private val SCRIPT = Regex("(?is)<(script|style|head)[^>]*>.*?</\\1\\s*>")
    private val TAG_RE = Regex("(?s)<[^>]+>")

    /** XML / HTML → text: block ends become line breaks, cells become tabs, tags go */
    fun stripMarkup(xml: String): String {
        var s = SCRIPT.replace(xml, "")
        s = BLOCK_END.replace(s, "\n")
        s = CELL_END.replace(s, "\t")
        s = TAG_RE.replace(s, "")
        s = unescape(s)
        return s.lines().joinToString("\n") { it.trim().replace(Regex("[ \t]{2,}"), " ") }.replace(Regex("\n{3,}"), "\n\n").trim()
    }

    private fun unescape(s: String): String = Regex("&(#x?[0-9a-fA-F]+|amp|lt|gt|quot|apos|nbsp);").replace(s) { m ->
        when (val e = m.groupValues[1]) {
            "amp" -> "&"; "lt" -> "<"; "gt" -> ">"; "quot" -> "\""; "apos" -> "'"; "nbsp" -> " "
            else -> runCatching {
                val code = if (e.startsWith("#x") || e.startsWith("#X")) e.substring(2).toInt(16) else e.substring(1).toInt()
                String(Character.toChars(code))
            }.getOrDefault(m.value)
        }
    }

    // --- Office / OpenDocument / EPUB ----------------------------------------------------------

    private fun zipped(f: SharedFile, ext: String, budget: Int): Attachment {
        ZipFile(f.path).use { zip ->
            val names = zip.entries().asSequence().map { it.name }.toList()
            val numbered = Regex("(\\d+)")
            fun byNumber(list: List<String>) = list.sortedBy { numbered.find(it.substringAfterLast('/'))?.value?.toIntOrNull() ?: 0 }
            val parts: List<String> = when {
                names.contains("word/document.xml") -> listOf("word/document.xml")
                names.any { it.startsWith("ppt/slides/slide") } -> byNumber(names.filter { it.matches(Regex("ppt/slides/slide\\d+\\.xml")) })
                names.contains("xl/sharedStrings.xml") -> listOf("xl/sharedStrings.xml")
                names.contains("content.xml") -> listOf("content.xml")
                ext == "epub" || names.any { it.endsWith(".xhtml") } -> epubOrder(zip, names)
                else -> emptyList()
            }
            if (parts.isEmpty()) return Attachment(f.name, "文档", null, note = "读不出文字", usable = false)
            val sb = StringBuilder()
            for ((i, p) in parts.withIndex()) {
                if (sb.length > budget) break
                val e = zip.getEntry(p) ?: continue
                val xml = zip.getInputStream(e).use { String(it.readNBytesCompat(MAX_TEXT_BYTES), Charsets.UTF_8) }
                val t = stripMarkup(xml)
                if (t.isBlank()) continue
                if (p.startsWith("ppt/slides/")) sb.append("【第 ${i + 1} 页】\n")
                sb.append(t).append("\n\n")
            }
            val kind = when (ext) { "pptx", "odp" -> "演示文稿"; "xlsx", "ods" -> "表格"; "epub" -> "电子书"; else -> "文档" }
            val prefix = if (parts.size > 1 && kind == "演示文稿") "${parts.size} 页 · " else ""
            return textAttachment(f.name, kind, sb.toString(), budget, prefix = prefix)
        }
    }

    /** EPUB chapters in reading order (the OPF spine), falling back to file order */
    private fun epubOrder(zip: ZipFile, names: List<String>): List<String> {
        val opfName = names.firstOrNull { it.endsWith(".opf") } ?: return names.filter { it.endsWith(".xhtml") || it.endsWith(".html") }.sorted()
        val opf = zip.getInputStream(zip.getEntry(opfName)).use { String(it.readBytes(), Charsets.UTF_8) }
        val base = opfName.substringBeforeLast('/', "").let { if (it.isEmpty()) "" else "$it/" }
        val hrefs = Regex("<item\\b[^>]*>").findAll(opf).mapNotNull { m ->
            val id = Regex("\\bid=\"([^\"]+)\"").find(m.value)?.groupValues?.get(1)
            val href = Regex("\\bhref=\"([^\"]+)\"").find(m.value)?.groupValues?.get(1)
            if (id != null && href != null) id to href else null
        }.toMap()
        val spine = Regex("<itemref\\b[^>]*idref=\"([^\"]+)\"").findAll(opf).mapNotNull { hrefs[it.groupValues[1]] }.toList()
        val order = spine.map { base + java.net.URLDecoder.decode(it, "UTF-8") }.filter { it in names }
        return order.ifEmpty { names.filter { it.endsWith(".xhtml") || it.endsWith(".html") }.sorted() }
    }

    // --- PDF --------------------------------------------------------------------------------

    private suspend fun pdf(f: SharedFile, budget: Int, withImages: Boolean): Attachment {
        ParcelFileDescriptor.open(File(f.path), ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
            PdfRenderer(pfd).use { r ->
                val pages = r.pageCount
                val sb = StringBuilder()
                for (i in 0 until pages) {
                    coroutineContext.ensureActive()
                    if (sb.length > budget) break
                    val t = r.openPage(i).use { pageText(it) } ?: break // no text API on this system
                    if (t.isNotBlank()) sb.append("【第 ${i + 1} 页】\n").append(t.trim()).append("\n\n")
                }
                val textChars = sb.replace(Regex("【第 \\d+ 页】"), "").count { !it.isWhitespace() }
                if (textChars >= 20 * min(pages, 5)) return textAttachment(f.name, "PDF", sb.toString(), budget, prefix = "$pages 页 · ")
                // scanned (or no text API): the first pages as images, which the model reads
                if (!withImages) return Attachment(f.name, "PDF", null, note = "$pages 页 · 扫描版，没有可提取的文字", usable = false)
                val imgs = ArrayList<ByteArray>()
                for (i in 0 until min(pages, MAX_PDF_IMAGES)) {
                    coroutineContext.ensureActive()
                    r.openPage(i).use { page ->
                        val scale = PAGE_PX.toFloat() / max(page.width, page.height)
                        val bmp = Bitmap.createBitmap(max(1, (page.width * scale).toInt()), max(1, (page.height * scale).toInt()), Bitmap.Config.ARGB_8888)
                        bmp.eraseColor(Color.WHITE)
                        page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        imgs += ScreenContent.jpeg(bmp)
                        bmp.recycle()
                    }
                }
                val more = if (pages > imgs.size) "，只发送前 ${imgs.size} 页" else ""
                return Attachment(f.name, "PDF", null, imgs, note = "$pages 页 · 扫描版，按图片发送$more")
            }
        }
    }

    /**
     * The page's text through PdfRenderer.Page#getTextContents (Android 15+), called reflectively
     * so the app still builds and runs where it doesn't exist. Null: not available.
     */
    private fun pageText(page: PdfRenderer.Page): String? = runCatching {
        val list = page.javaClass.getMethod("getTextContents").invoke(page) as? List<*> ?: return null
        list.joinToString("\n") { item -> item?.javaClass?.getMethod("getText")?.invoke(item) as? String ?: "" }
    }.getOrNull()

    // --- images -----------------------------------------------------------------------------

    private suspend fun image(context: Context, f: SharedFile, withImages: Boolean): Attachment {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.path, bounds)
        if (bounds.outWidth <= 0) return Attachment(f.name, "图片", null, note = "无法解码这张图片", usable = false)
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 2400) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = Bitmap.Config.ARGB_8888 }
        var bmp = BitmapFactory.decodeFile(f.path, opts) ?: return Attachment(f.name, "图片", null, note = "无法解码这张图片", usable = false)
        val rotation = runCatching {
            when (ExifInterface(f.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
        }.getOrDefault(0f)
        if (rotation != 0f) {
            val m = Matrix().apply { postRotate(rotation) }
            val rotated = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
            if (rotated !== bmp) bmp.recycle()
            bmp = rotated
        }
        try {
            val jpeg = if (withImages) ScreenContent.jpeg(bmp) else null
            val text = withTimeoutOrNull(8_000) { runCatching { OcrEngine.readText(context, bmp) }.getOrNull() }
            OcrEngine.scheduleRelease(context)
            val note = "${bounds.outWidth}×${bounds.outHeight}" + if (text != null) " · 识别出 ${text.length} 字" else " · 没有文字"
            if (jpeg == null && text == null) return Attachment(f.name, "图片", null, note = "$note（当前模型不能看图）", usable = false)
            return Attachment(f.name, "图片", text?.let { "图片里识别到的文字（本机 OCR）：\n$it" }, listOfNotNull(jpeg), note)
        } finally {
            bmp.recycle()
        }
    }

    /** InputStream.readNBytes exists only from Android 13 */
    private fun java.io.InputStream.readNBytesCompat(limit: Long): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(64 * 1024)
        var total = 0L
        while (total < limit) {
            val n = read(buf, 0, min(buf.size.toLong(), limit - total).toInt())
            if (n < 0) break
            out.write(buf, 0, n)
            total += n
        }
        return out.toByteArray()
    }
}
