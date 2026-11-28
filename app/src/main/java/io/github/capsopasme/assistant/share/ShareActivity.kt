package io.github.capsopasme.assistant.share

import android.app.Activity
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.OpenableColumns
import android.util.Log
import android.widget.Toast
import io.github.capsopasme.assistant.ocr.OcrLauncher
import io.github.capsopasme.assistant.panel.PanelActivity
import io.github.capsopasme.assistant.vis.AssistVoiceService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Files handed over by [ShareActivity], waiting for the panel to pick them up. */
object Inbox {
    private var files: List<SharedFile> = emptyList()
    private var text: String? = null
    private var at = 0L

    @Synchronized fun put(files: List<SharedFile>, text: String?) {
        this.files = files
        this.text = text
        at = SystemClock.elapsedRealtime()
    }

    /** what was shared in the last half minute, taken once */
    @Synchronized fun take(): Pair<List<SharedFile>, String?>? {
        if (at == 0L || SystemClock.elapsedRealtime() - at > 30_000) return null
        val r = files to text
        files = emptyList(); text = null; at = 0L
        return r.takeIf { it.first.isNotEmpty() || !it.second.isNullOrBlank() }
    }
}

/**
 * "分享" / "用其他应用打开" → 助手. Invisible: copies what was shared into the app's cache right away
 * (the read grant on a shared content:// URI ends with this activity), finishes without an
 * animation, and the assistant panel rises over the app the user was in, with the files attached.
 * The panel is the assistant session's window (like OCR), so nothing is started from the background.
 */
class ShareActivity : Activity() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var done = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val intent = intent
        val uris = urisOf(intent)
        val text = textOf(intent)
        if (uris.isEmpty() && text.isNullOrBlank()) {
            Toast.makeText(this, "没有收到可以处理的内容", Toast.LENGTH_SHORT).show()
            finishQuietly()
            return
        }
        val slowHint = Handler(Looper.getMainLooper())
        val hint = Runnable { Toast.makeText(this, "正在读取文件…", Toast.LENGTH_SHORT).show() }
        slowHint.postDelayed(hint, 700)
        scope.launch {
            val files = withContext(Dispatchers.IO) { copyAll(uris) }
            slowHint.removeCallbacks(hint)
            if (files.isEmpty() && text.isNullOrBlank()) {
                Toast.makeText(this@ShareActivity, "读取失败：没有权限或文件不存在", Toast.LENGTH_SHORT).show()
                finishQuietly()
                return@launch
            }
            Inbox.put(files, text)
            open()
        }
    }

    /** Shows the panel: the assistant session if we are the assistant, else the panel activity. */
    private fun open() {
        val vis = AssistVoiceService.instance
        if (vis == null) {
            runCatching {
                startActivity(Intent(this, PanelActivity::class.java).putExtra(PanelActivity.EXTRA_SHARE, true)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            finishQuietly()
            return
        }
        finishQuietly()
        // once this activity is gone and the app below is back in front: a session shown while our
        // own activity is still on top would be hidden again as it finishes
        val app = applicationContext
        Handler(Looper.getMainLooper()).postDelayed({
            AssistVoiceService.instance?.showSession(Bundle().apply { putString(OcrLauncher.EXTRA_MODE, MODE_SHARE) }, 0)
                ?: runCatching {
                    app.startActivity(Intent(app, PanelActivity::class.java).putExtra(PanelActivity.EXTRA_SHARE, true)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
        }, 180)
    }

    private fun finishQuietly() {
        if (done) return
        done = true
        finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun urisOf(i: Intent): List<Uri> {
        val out = LinkedHashSet<Uri>()
        when (i.action) {
            Intent.ACTION_SEND -> streamExtra(i)?.let { out += it }
            Intent.ACTION_SEND_MULTIPLE -> streamList(i).forEach { out += it }
            Intent.ACTION_VIEW, Intent.ACTION_EDIT -> i.data?.let { out += it }
        }
        // the grant travels in ClipData; some apps put the files only there
        i.clipData?.let { c -> for (k in 0 until c.itemCount) c.getItemAt(k).uri?.let { out += it } }
        return out.filter { it.scheme == ContentResolver.SCHEME_CONTENT || it.scheme == ContentResolver.SCHEME_FILE }.take(MAX_FILES)
    }

    @Suppress("DEPRECATION")
    private fun streamExtra(i: Intent): Uri? =
        if (Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java) else i.getParcelableExtra(Intent.EXTRA_STREAM)

    @Suppress("DEPRECATION")
    private fun streamList(i: Intent): List<Uri> =
        (if (Build.VERSION.SDK_INT >= 33) i.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
        else i.getParcelableArrayListExtra(Intent.EXTRA_STREAM)).orEmpty()

    private fun textOf(i: Intent): String? {
        if (i.action != Intent.ACTION_SEND && i.action != Intent.ACTION_SEND_MULTIPLE) return null
        val subject = i.getStringExtra(Intent.EXTRA_SUBJECT)?.trim()
        val body = i.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()?.trim()
        return listOfNotNull(subject?.takeIf { it.isNotEmpty() && body?.contains(it) != true }, body?.takeIf { it.isNotEmpty() })
            .joinToString("\n").ifBlank { null }
    }

    private fun copyAll(uris: List<Uri>): List<SharedFile> {
        val dir = File(cacheDir, "share").apply { mkdirs() }
        // what earlier shares left behind
        dir.listFiles()?.forEach { if (System.currentTimeMillis() - it.lastModified() > 24 * 3600_000L) it.delete() }
        val out = ArrayList<SharedFile>()
        for ((n, uri) in uris.withIndex()) {
            runCatching { copyOne(this, uri, dir, n) }.onFailure { Log.w(TAG, "copy $uri failed", it) }.getOrNull()?.let { out += it }
        }
        return out
    }

    private fun copyOne(ctx: Context, uri: Uri, dir: File, n: Int): SharedFile? {
        var name: String? = null
        var size = -1L
        if (uri.scheme == ContentResolver.SCHEME_CONTENT) {
            runCatching {
                ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                    if (c.moveToFirst()) {
                        val ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        val si = c.getColumnIndex(OpenableColumns.SIZE)
                        if (ni >= 0) name = c.getString(ni)
                        if (si >= 0 && !c.isNull(si)) size = c.getLong(si)
                    }
                }
            }
        }
        val display = (name ?: uri.lastPathSegment?.substringAfterLast('/') ?: "文件${n + 1}").take(120)
        if (size > MAX_BYTES) {
            Log.i(TAG, "$display too large ($size)")
            return SharedFile("", display, ctx.contentResolver.getType(uri), size)
        }
        val safe = display.replace(Regex("[\\\\/:*?\"<>|\\s]"), "_")
        val dest = File(dir, "${System.currentTimeMillis()}_${n}_$safe")
        val input = ctx.contentResolver.openInputStream(uri) ?: return null
        var total = 0L
        input.use { inp ->
            dest.outputStream().use { out ->
                val buf = ByteArray(256 * 1024)
                while (true) {
                    val r = inp.read(buf)
                    if (r < 0) break
                    total += r
                    if (total > MAX_BYTES) { dest.delete(); return SharedFile("", display, ctx.contentResolver.getType(uri), total) }
                    out.write(buf, 0, r)
                }
            }
        }
        return SharedFile(dest.absolutePath, display, ctx.contentResolver.getType(uri), total)
    }

    companion object {
        private const val TAG = "ShareActivity"
        const val MODE_SHARE = "share"
        private const val MAX_FILES = 10
        /** bigger files are named but not read */
        private const val MAX_BYTES = 60L * 1024 * 1024
    }
}
