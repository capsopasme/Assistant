package io.github.capsopasme.assistant.ocr

import android.graphics.Bitmap
import android.graphics.ColorSpace
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit

/**
 * Takes a screenshot with root `screencap` in its raw form: no PNG encoding (~0.1 s on the
 * Xiaomi 13 for 1080×2400), read straight from the pipe into memory, never written to disk.
 *
 * Raw format: width, height, pixel format, and since Android 12 the dataspace (4 bytes each,
 * little-endian), then width × height RGBA pixels. The header length is whatever is left over,
 * so both layouts work. The dataspace matters: in a wide-gamut display mode the pixels are
 * Display P3, and showing them as sRGB would make the frozen frame visibly differ from the screen.
 */
object ScreenCapture {
    private const val TAG = "ScreenCapture"
    private const val FORMAT_RGBA_8888 = 1
    private const val FORMAT_RGBX_8888 = 2
    private const val DATASPACE_DISPLAY_P3 = 143261696 // STANDARD_DCI_P3 | TRANSFER_SRGB | RANGE_FULL
    private const val STANDARD_DCI_P3 = 10

    class Failed(message: String) : Exception(message)

    suspend fun capture(): Bitmap = withContext(Dispatchers.IO) {
        val p = try {
            ProcessBuilder("su", "-c", "/system/bin/screencap").redirectErrorStream(false).start()
        } catch (e: Exception) {
            throw Failed("无法获取 root：${e.message}")
        }
        val (bytes, n) = try {
            p.outputStream.close()
            readAll(p.inputStream)
        } finally {
            if (!p.waitFor(3, TimeUnit.SECONDS)) p.destroyForcibly()
        }
        decode(bytes, n)
    }

    /** Parses screencap's raw output; [size] = the number of valid bytes in [buf]. */
    fun decode(buf: ByteArray, size: Int): Bitmap {
        if (size < 16) throw Failed("截屏失败（没有 root 权限？）")
        val bb = ByteBuffer.wrap(buf, 0, size).order(ByteOrder.LITTLE_ENDIAN)
        val w = bb.getInt(0)
        val h = bb.getInt(4)
        val format = bb.getInt(8)
        if (w <= 0 || h <= 0 || w > 10000 || h > 10000) throw Failed("截屏数据无效")
        val pixels = w.toLong() * h * 4
        val header = size - pixels
        if (header < 12 || header > 64) throw Failed("截屏数据长度不符（$size 字节）")
        if (format != FORMAT_RGBA_8888 && format != FORMAT_RGBX_8888) throw Failed("不支持的截屏格式 $format")
        val dataspace = if (header >= 16) bb.getInt(12) else 0
        val p3 = dataspace == DATASPACE_DISPLAY_P3 || ((dataspace and 0x3F0000) ushr 16) == STANDARD_DCI_P3
        val cs = ColorSpace.get(if (p3) ColorSpace.Named.DISPLAY_P3 else ColorSpace.Named.SRGB)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888, false, cs)
        bmp.copyPixelsFromBuffer(ByteBuffer.wrap(buf, header.toInt(), pixels.toInt()))
        bmp.setHasAlpha(false) // RGBX: the fourth byte is undefined
        Log.i(TAG, "screenshot ${w}x$h format=$format dataspace=$dataspace p3=$p3")
        return bmp
    }

    /** the bytes and how many are valid (no final copy: the buffer is ~10 MB) */
    private fun readAll(input: java.io.InputStream): Pair<ByteArray, Int> {
        var buf = ByteArray(1080 * 2400 * 4 + 64)
        var n = 0
        input.use { s ->
            while (true) {
                if (n == buf.size) buf = buf.copyOf(buf.size * 2)
                val r = s.read(buf, n, buf.size - n)
                if (r < 0) break
                n += r
            }
        }
        return buf to n
    }
}
