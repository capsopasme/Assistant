package io.github.capsopasme.assistant.ocr

import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

/**
 * Pure post-processing for PP-OCRv6 (no Android types, unit-tested): the detector's probability
 * map → text boxes, the recognizer's CTC output → text, and text boxes → paragraph blocks for the
 * overlay.
 */

/** An axis-aligned box in screen pixels; [r] and [b] are exclusive. */
data class Box(val l: Int, val t: Int, val r: Int, val b: Int) {
    val w get() = r - l
    val h get() = b - t
    val cy get() = (t + b) / 2
}

object DbPost {
    /**
     * DBNet post-processing, axis-aligned (screen text is horizontal): threshold the map, take
     * 8-connected components, score each by the mean probability inside its bounding box (like
     * PaddleOCR's box_score_fast), drop weak or tiny ones, and expand ("unclip") the shrunk
     * region back to the full text box. Parameters are the model's own (inference.yml).
     *
     * @param prob the map, [mapW] × [mapH]; boxes are scaled by [sx], [sy] and clamped to
     *        [outW] × [outH].
     */
    fun boxes(
        prob: FloatArray, mapW: Int, mapH: Int,
        sx: Float, sy: Float, outW: Int, outH: Int,
        thresh: Float = 0.2f, boxThresh: Float = 0.45f, unclipRatio: Float = 1.4f,
        minSize: Int = 3, maxCandidates: Int = 3000,
    ): List<Box> {
        val n = mapW * mapH
        require(prob.size >= n)
        val mask = ByteArray(n)
        for (i in 0 until n) if (prob[i] > thresh) mask[i] = 1
        val stack = IntArray(n)
        val out = ArrayList<Box>()
        var found = 0
        for (start in 0 until n) {
            if (mask[start].toInt() != 1) continue
            // flood fill one component
            var sp = 0
            stack[sp++] = start
            mask[start] = 2
            var minX = mapW; var maxX = -1; var minY = mapH; var maxY = -1
            while (sp > 0) {
                val p = stack[--sp]
                val x = p % mapW
                val y = p / mapW
                if (x < minX) minX = x
                if (x > maxX) maxX = x
                if (y < minY) minY = y
                if (y > maxY) maxY = y
                for (dy in -1..1) {
                    val yy = y + dy
                    if (yy < 0 || yy >= mapH) continue
                    val row = yy * mapW
                    for (dx in -1..1) {
                        val xx = x + dx
                        if (xx < 0 || xx >= mapW) continue
                        val q = row + xx
                        if (mask[q].toInt() == 1) {
                            mask[q] = 2
                            stack[sp++] = q
                        }
                    }
                }
            }
            if (++found > maxCandidates) break
            val bw = maxX - minX + 1
            val bh = maxY - minY + 1
            if (min(bw, bh) < minSize) continue
            var sum = 0.0
            for (y in minY..maxY) {
                val row = y * mapW
                for (x in minX..maxX) sum += prob[row + x]
            }
            if (sum / (bw * bh) < boxThresh) continue
            // unclip: offset the region outwards by area * ratio / perimeter
            val d = bw.toFloat() * bh * unclipRatio / (2f * (bw + bh))
            val l = minX - d
            val t = minY - d
            val r = maxX + 1 + d
            val b = maxY + 1 + d
            if (min(r - l, b - t) < minSize + 2) continue
            val box = Box(
                (l * sx).toInt().coerceIn(0, outW), (t * sy).toInt().coerceIn(0, outH),
                ceil(r * sx).toInt().coerceIn(0, outW), ceil(b * sy).toInt().coerceIn(0, outH),
            )
            if (box.w > 1 && box.h > 1) out.add(box)
        }
        return out
    }
}

object Ctc {
    /**
     * Greedy CTC decoding of one sequence: [probs] holds [steps] × [classes] values starting at
     * [offset]. Class 0 is the blank, 1..dict.size the dictionary, and a class past the
     * dictionary is the space PaddleOCR appends (use_space_char). Returns the text and its mean
     * confidence (0 when empty). Accepts logits as well as probabilities.
     */
    fun decode(probs: FloatArray, offset: Int, steps: Int, classes: Int, dict: List<String>): Pair<String, Float> {
        val sb = StringBuilder()
        var confSum = 0f
        var kept = 0
        var prev = -1
        for (t in 0 until steps) {
            val base = offset + t * classes
            var best = 0
            var bestV = probs[base]
            for (c in 1 until classes) {
                val v = probs[base + c]
                if (v > bestV) { bestV = v; best = c }
            }
            if (best != 0 && best != prev) {
                val ch = if (best - 1 < dict.size) dict[best - 1] else " "
                sb.append(ch)
                confSum += confidence(probs, base, classes, bestV)
                kept++
            }
            prev = best
        }
        return sb.toString() to if (kept == 0) 0f else confSum / kept
    }

    /** the best class's probability; softmax when the row isn't a distribution already */
    private fun confidence(p: FloatArray, base: Int, classes: Int, best: Float): Float {
        if (best in 0f..1f) {
            var s = 0f
            for (c in 0 until classes) s += p[base + c]
            if (s in 0.98f..1.02f) return best
        }
        var s = 0.0
        for (c in 0 until classes) s += exp((p[base + c] - best).toDouble())
        return (1.0 / s).toFloat()
    }
}

/** A visual line: one or more detected boxes side by side (indices into the box list). */
data class Row(val parts: List<Int>, val box: Box)

/** A paragraph block shown in the overlay. */
data class Block(val rows: List<Row>, val box: Box) {
    /** all box indices of the block in reading order */
    val parts: List<Int> get() = rows.flatMap { it.parts }
}

object Layout {
    /**
     * Groups detected boxes into rows (fragments of one line split by wide spacing) and rows into
     * paragraph blocks: a row joins the block above when it follows closely (gap below about a
     * line height), has a similar height, and lines up with it (left-aligned, or mostly
     * overlapping, which covers centred text). Two columns or a chat's two sides stay apart.
     * Blocks come in reading order: top to bottom, then left to right.
     */
    fun blocks(boxes: List<Box>): List<Block> {
        val rows = rows(boxes)
        class Open(val rows: MutableList<Row>, var box: Box)
        val open = ArrayList<Open>()
        for (row in rows) {
            var best: Open? = null
            var bestGap = Int.MAX_VALUE
            for (b in open) {
                val last = b.rows.last().box
                val hMin = min(last.h, row.box.h).coerceAtLeast(1)
                val hMax = max(last.h, row.box.h)
                if (hMax > hMin * 1.4f) continue
                val gap = row.box.t - last.b
                if (gap < -hMin * 0.3f || gap > hMin * 0.9f) continue
                val leftAligned = kotlin.math.abs(row.box.l - b.box.l) <= hMin * 1.2f
                val overlap = min(row.box.r, last.r) - max(row.box.l, last.l)
                val overlapping = overlap >= 0.5f * min(row.box.w, last.w)
                if (!leftAligned && !overlapping) continue
                if (gap < bestGap) { bestGap = gap; best = b }
            }
            if (best == null) open.add(Open(mutableListOf(row), row.box))
            else {
                best.rows.add(row)
                best.box = union(best.box, row.box)
            }
        }
        return open.map { Block(it.rows.toList(), it.box) }
            .sortedWith(compareBy({ it.box.t / 8 }, { it.box.l }))
    }

    /** boxes on the same line with only a word-sized gap become one row */
    fun rows(boxes: List<Box>): List<Row> {
        val order = boxes.indices.sortedWith(compareBy({ boxes[it].t }, { boxes[it].l }))
        val rows = ArrayList<MutableList<Int>>()
        val rowBox = ArrayList<Box>()
        for (i in order) {
            val b = boxes[i]
            var joined = false
            for (k in rows.indices.reversed()) {
                val rb = rowBox[k]
                if (rb.b < b.t) continue // entirely above this box
                val last = boxes[rows[k].last()]
                val hMin = min(last.h, b.h).coerceAtLeast(1)
                val vOverlap = min(last.b, b.b) - max(last.t, b.t)
                if (vOverlap < 0.6f * hMin || max(last.h, b.h) > hMin * 1.5f) continue
                val gap = b.l - last.r
                if (gap < -0.3f * hMin || gap > 0.6f * hMin) continue
                rows[k].add(i)
                rowBox[k] = union(rb, b)
                joined = true
                break
            }
            if (!joined) {
                rows.add(mutableListOf(i))
                rowBox.add(b)
            }
        }
        return rows.indices.map { Row(rows[it].toList(), rowBox[it]) }
            .sortedWith(compareBy({ it.box.t }, { it.box.l }))
    }

    /**
     * The block's text. Fragments of a row are joined with a space (none next to CJK); a row that
     * reaches the block's right edge wrapped and continues on the next row (joined the same way),
     * any other row ends with a line break. Empty fragments (nothing recognized) are skipped.
     */
    fun text(block: Block, texts: List<String?>): String {
        val sb = StringBuilder()
        var prevRow: Row? = null
        for (row in block.rows) {
            val line = joinWords(row.parts.mapNotNull { texts.getOrNull(it)?.trim()?.takeIf(String::isNotEmpty) })
            if (line.isEmpty()) continue
            val p = prevRow
            if (p != null && sb.isNotEmpty()) {
                val wrapped = p.box.r >= block.box.r - p.box.h * 1.5f
                sb.append(if (wrapped) separator(sb.last(), line.first()) else "\n")
            }
            sb.append(line)
            prevRow = row
        }
        return sb.toString()
    }

    fun joinWords(words: List<String>): String {
        val sb = StringBuilder()
        for (w in words) {
            if (sb.isNotEmpty()) sb.append(separator(sb.last(), w.first()))
            sb.append(w)
        }
        return sb.toString()
    }

    private fun separator(a: Char, b: Char) = if (isCjk(a) || isCjk(b)) "" else " "

    fun isCjk(c: Char): Boolean = c in '一'..'鿿' || c in '㐀'..'䶿' ||
        c in '぀'..'ヿ' || c in '　'..'〿' || c in '＀'..'￯' ||
        c in '가'..'힯'

    private fun union(a: Box, b: Box) = Box(min(a.l, b.l), min(a.t, b.t), max(a.r, b.r), max(a.b, b.b))
}
