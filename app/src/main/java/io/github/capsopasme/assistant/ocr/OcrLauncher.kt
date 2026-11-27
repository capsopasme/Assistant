package io.github.capsopasme.assistant.ocr

import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import io.github.capsopasme.assistant.vis.AssistVoiceService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Sent by the status bar module (statusbar_dt2s) on a two-finger double tap of the status bar,
 * through `cmd activity broadcast` as root. Protected by a signature permission: root is exempt,
 * no other app can send it.
 */
class OcrTriggerReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == OcrLauncher.ACTION_OCR) OcrLauncher.trigger(context.applicationContext)
    }
}

/**
 * Starts an OCR session: screenshot first (before any window of ours exists, so it shows exactly
 * what was on screen), the models loading meanwhile, then the assistant's session window in OCR
 * mode. Triggered again while open, it closes instead.
 */
object OcrLauncher {
    private const val TAG = "OcrLauncher"
    const val ACTION_OCR = "io.github.capsopasme.assistant.action.OCR"
    const val EXTRA_MODE = "io.github.capsopasme.assistant.extra.MODE"
    const val MODE_OCR = "ocr"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var shot: Bitmap? = null
    private var busy = false

    fun trigger(context: Context) {
        OcrController.active?.let { it.closeAnimated(); return }
        if (busy) return
        if (context.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true) return // never on the lock screen
        val vis = AssistVoiceService.instance
        if (vis == null) {
            toast(context, "「助手」不是默认数字助理，无法识别屏幕文字")
            return
        }
        busy = true
        OcrEngine.preload(context)
        scope.launch {
            try {
                val bmp = ScreenCapture.capture()
                shot?.recycle()
                shot = bmp
                vis.showSession(Bundle().apply { putString(EXTRA_MODE, MODE_OCR) }, 0)
                // the session normally opens within a frame or two; if the system didn't open it,
                // say so instead of silently holding a 10 MB screenshot
                scope.launch {
                    delay(2_000)
                    if (shot === bmp) {
                        shot = null
                        bmp.recycle()
                        toast(context, "无法打开识别界面，请重试")
                    }
                }
            } catch (e: ScreenCapture.Failed) {
                toast(context, e.message ?: "截屏失败")
            } catch (e: Exception) {
                Log.w(TAG, "ocr start failed", e)
                toast(context, "截屏失败：${e.message}")
            } finally {
                busy = false
            }
        }
    }

    /** the screenshot for the session that is opening (taken once) */
    fun takeShot(): Bitmap? {
        val s = shot
        shot = null
        return s
    }

    /** recycles [bmp] once [job] has really finished (it may be mid-way through a model step) */
    fun recycleAfter(job: Job?, bmp: Bitmap) {
        scope.launch {
            job?.join()
            if (!bmp.isRecycled) bmp.recycle()
        }
    }

    private fun toast(context: Context, text: String) {
        Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
    }
}
