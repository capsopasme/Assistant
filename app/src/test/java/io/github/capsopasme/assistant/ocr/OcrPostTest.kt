package io.github.capsopasme.assistant.ocr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OcrPostTest {

    // --- DB ---------------------------------------------------------------------------------

    private fun map(w: Int, h: Int, vararg rects: IntArray, v: Float = 0.9f): FloatArray {
        val m = FloatArray(w * h)
        for (r in rects) for (y in r[1] until r[3]) for (x in r[0] until r[2]) m[y * w + x] = v
        return m
    }

    @Test
    fun dbFindsSeparateLinesAndUnclips() {
        val w = 100; val h = 60
        val m = map(w, h, intArrayOf(10, 10, 60, 16), intArrayOf(10, 30, 40, 36))
        val boxes = DbPost.boxes(m, w, h, 1f, 1f, w, h)
        assertEquals(2, boxes.size)
        val a = boxes.minBy { it.t }
        // 50x6 region expanded by 50*6*1.4/(2*56) = 3.75 on every side
        assertTrue(a.l in 5..7 && a.t in 5..7 && a.r in 63..65 && a.b in 19..21)
    }

    @Test
    fun dbDropsWeakAndTinyRegions() {
        val w = 50; val h = 50
        val weak = map(w, h, intArrayOf(5, 5, 40, 12), v = 0.3f) // above thresh, below box thresh
        assertEquals(0, DbPost.boxes(weak, w, h, 1f, 1f, w, h).size)
        val tiny = map(w, h, intArrayOf(5, 5, 7, 30)) // 2 px wide
        assertEquals(0, DbPost.boxes(tiny, w, h, 1f, 1f, w, h).size)
    }

    @Test
    fun dbScalesToScreenAndClamps() {
        val w = 40; val h = 20
        val m = map(w, h, intArrayOf(0, 0, 40, 6))
        val b = DbPost.boxes(m, w, h, 2f, 2f, 80, 40).single()
        assertEquals(0, b.l)
        assertEquals(0, b.t)
        assertEquals(80, b.r)
    }

    // --- CTC --------------------------------------------------------------------------------

    @Test
    fun ctcCollapsesRepeatsAndBlanks() {
        val dict = listOf("a", "b", "你")
        val classes = dict.size + 2 // blank + dict + space
        // a a blank a b space 你 你
        val seq = intArrayOf(1, 1, 0, 1, 2, 4, 3, 3)
        val p = FloatArray(seq.size * classes)
        seq.forEachIndexed { t, c -> p[t * classes + c] = 1f }
        val (text, conf) = Ctc.decode(p, 0, seq.size, classes, dict)
        assertEquals("aab 你", text)
        assertEquals(1f, conf, 1e-4f)
    }

    @Test
    fun ctcAcceptsLogits() {
        val dict = listOf("x")
        val p = floatArrayOf(0f, 5f, 0f) // logits, classes = 3
        val (text, conf) = Ctc.decode(p, 0, 1, 3, dict)
        assertEquals("x", text)
        assertTrue(conf > 0.95f)
    }

    // --- layout -----------------------------------------------------------------------------

    @Test
    fun paragraphLinesFormOneBlockAndJoinWrappedCjk() {
        // two wrapped lines of a paragraph, then a separate setting row far below
        val boxes = listOf(
            Box(40, 100, 1040, 140),
            Box(40, 150, 600, 190),
            Box(40, 400, 300, 440),
        )
        val blocks = Layout.blocks(boxes)
        assertEquals(2, blocks.size)
        assertEquals(listOf(0, 1), blocks[0].parts)
        val text = Layout.text(blocks[0], listOf("第一行写满了", "接着第二行。", "蓝牙"))
        assertEquals("第一行写满了接着第二行。", text)
    }

    @Test
    fun shortLinesKeepLineBreaksAndLatinGetsSpaces() {
        val boxes = listOf(
            Box(40, 100, 400, 140),
            Box(40, 150, 1040, 190),
            Box(40, 200, 500, 240),
        )
        val b = Layout.blocks(boxes).single()
        assertEquals("Title\nthe wrapped first line continues here", Layout.text(b, listOf("Title", "the wrapped first line", "continues here")))
    }

    @Test
    fun columnsAndChatSidesStayApart() {
        val boxes = listOf(
            Box(40, 100, 400, 140), Box(680, 100, 1040, 140), // same row, far apart
            Box(40, 150, 400, 190), Box(680, 150, 1040, 190),
        )
        val blocks = Layout.blocks(boxes)
        assertEquals(2, blocks.size)
        assertEquals(listOf(0, 2), blocks[0].parts)
        assertEquals(listOf(1, 3), blocks[1].parts)
    }

    @Test
    fun fragmentsOfOneLineBecomeOneRow() {
        val boxes = listOf(Box(40, 100, 200, 140), Box(215, 102, 400, 141))
        val rows = Layout.rows(boxes)
        assertEquals(1, rows.size)
        val b = Layout.blocks(boxes).single()
        assertEquals("Hello world", Layout.text(b, listOf("Hello", "world")))
        assertEquals("你好世界", Layout.text(b, listOf("你好", "世界")))
    }

    @Test
    fun emptyRecognitionIsSkipped() {
        val boxes = listOf(Box(40, 100, 400, 140), Box(40, 150, 400, 190))
        val b = Layout.blocks(boxes).single()
        assertEquals("second", Layout.text(b, listOf(null, "second")))
        assertEquals("", Layout.text(b, listOf("", null)))
    }
}
