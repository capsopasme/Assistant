package io.github.capsopasme.assistant.parse

/**
 * Numbers, durations and clock times as people say them in Chinese ("两个半小时", "七点一刻",
 * "1小时20分"), pure Kotlin so that the parsing is covered by plain unit tests.
 */
object ChineseNumber {

    private val DIGITS = mapOf(
        '零' to 0, '〇' to 0, '一' to 1, '壹' to 1, '二' to 2, '两' to 2, '贰' to 2, '三' to 3,
        '四' to 4, '五' to 5, '六' to 6, '七' to 7, '八' to 8, '九' to 9,
    )
    private val UNITS = mapOf('十' to 10, '拾' to 10, '百' to 100, '千' to 1000)

    const val NUM_CHARS = "零〇一壹二两贰三四五六七八九十拾百千万"

    /** "25", "2.5", "二十五", "十", "一百二"(=120), "两千" → number; null if it isn't one. */
    fun parse(text: String): Double? {
        val s = text.trim()
        if (s.isEmpty()) return null
        s.toDoubleOrNull()?.let { return it }
        // full-width digits
        val ascii = buildString { for (c in s) append(if (c in '０'..'９') '0' + (c - '０') else c) }
        ascii.toDoubleOrNull()?.let { return it }
        return parseChinese(s)?.toDouble()
    }

    fun parseInt(text: String): Int? = parse(text)?.let { if (it == Math.floor(it) && it in 0.0..1e9) it.toInt() else null }

    private fun parseChinese(s: String): Long? {
        if (s.any { it !in NUM_CHARS }) return null
        val wan = s.indexOf('万')
        if (wan >= 0) {
            val high = if (wan == 0) 1L else parseSection(s.substring(0, wan)) ?: return null
            val rest = s.substring(wan + 1)
            val low = if (rest.isEmpty()) 0L else {
                // "两万五" = 25000
                if (rest.length == 1 && rest[0] in DIGITS) DIGITS.getValue(rest[0]) * 1000L
                else parseSection(rest) ?: return null
            }
            return high * 10000 + low
        }
        return parseSection(s)
    }

    /** One section below 万. */
    private fun parseSection(s: String): Long? {
        if (s.isEmpty()) return null
        // plain digit strings like "一二三" (rare) or "二零二六"
        if (s.length > 1 && s.all { it in DIGITS } && (s.length >= 3 || s[0] == '零' || s[0] == '〇')) {
            var v = 0L
            for (c in s) v = v * 10 + DIGITS.getValue(c)
            return v
        }
        var total = 0L
        var current = -1L // digit waiting for its unit; -1 = none
        var lastUnit = 0
        for ((i, c) in s.withIndex()) {
            val d = DIGITS[c]
            if (d != null) {
                if (current >= 0 && d != 0) return null // two digits in a row: "三四"
                current = if (d == 0) -1 else d.toLong()
                if (d == 0) lastUnit = 0 // "一百零五": the 5 is ones
                continue
            }
            val u = UNITS[c] ?: return null
            if (lastUnit in 1..u) return null // "十百", units must decrease
            val n = if (current < 0) {
                if (i == 0 && u == 10) 1L else return null // leading "十" = 10
            } else current
            total += n * u
            current = -1
            lastUnit = u
        }
        if (current >= 0) {
            // colloquial trailing digit: "一百二" = 120, "三千五" = 3500, but "二十五" = 25
            total += if (lastUnit >= 100 && s.length >= 2 && s[s.length - 2] in UNITS) current * (lastUnit / 10) else current
        }
        return total
    }

    private val DURATION_TOKEN = Regex(
        "((?:\\d+(?:\\.\\d+)?)|[$NUM_CHARS]+)?(半)?(小时|分钟|分|秒钟|秒|刻钟|刻|h|min|m|s)(半)?"
    )

    /**
     * "十分钟", "1小时20分", "一个半小时", "半小时", "90秒", "5分半", "一刻钟" → seconds.
     * The whole string must be a duration; null otherwise (and for zero).
     */
    fun parseDurationSeconds(input: String): Int? {
        var s = input.lowercase()
            .replace(Regex("[\\s，,、]"), "")
            .replace("钟头", "小时")
            .replace("半个", "半")
            .replace("个半", "半")
            .replace(Regex("个(?=小时|钟|分|秒|刻)"), "")
            .replace("分半钟", "分半")
            .replace("零", "")
        s = s.removeSuffix("钟").let { if (it.endsWith("分") || it.endsWith("秒") || it.endsWith("刻")) it else s }
        if (s.isEmpty()) return null
        var pos = 0
        var total = 0.0
        var lastScale = Double.MAX_VALUE
        while (pos < s.length) {
            val m = DURATION_TOKEN.matchAt(s, pos) ?: return null
            if (m.value.isEmpty()) return null
            val numText = m.groupValues[1]
            val halfBefore = m.groupValues[2].isNotEmpty()
            val unit = m.groupValues[3]
            val halfAfter = m.groupValues[4].isNotEmpty()
            val scale = when (unit) {
                "小时", "h" -> 3600.0
                "分钟", "分", "min", "m" -> 60.0
                "秒钟", "秒", "s" -> 1.0
                "刻钟", "刻" -> 900.0
                else -> return null
            }
            if (scale >= lastScale) return null // "十分钟一小时"
            lastScale = scale
            var n = if (numText.isEmpty()) {
                if (!halfBefore) return null
                0.0
            } else parse(numText) ?: return null
            if (halfBefore || halfAfter) n += 0.5
            if (halfBefore && halfAfter) return null
            total += n * scale
            pos = m.range.last + 1
        }
        if (total < 1 || total > 24 * 3600 * 7) return null
        return Math.round(total).toInt()
    }

    data class ClockTime(val hour: Int, val minute: Int, val periodGiven: Boolean)

    private val PERIODS = listOf(
        "凌晨" to 0, "早上" to 1, "早晨" to 1, "清晨" to 1, "上午" to 1, "早" to 1, "明早" to 1,
        "中午" to 2, "下午" to 3, "傍晚" to 3, "晚上" to 4, "今晚" to 4, "夜里" to 4, "晚" to 4, "半夜" to 5, "午夜" to 5,
    ).sortedByDescending { it.first.length }

    private val CLOCK_COLON = Regex("^(\\d{1,2})[:：](\\d{1,2})$")
    private val CLOCK_CN = Regex("^([\\d$NUM_CHARS]{1,3})[点時时](?:(半)|(一刻)|(三刻)|(?:([\\d$NUM_CHARS]{1,3})分?))?$")

    /**
     * "七点", "7点半", "早上7:30", "下午三点十五", "晚上十点一刻", "中午12点" → 24h clock time.
     * Day words ("明天", "今天") must already be removed by the caller.
     */
    fun parseClock(input: String): ClockTime? {
        var s = input.replace(Regex("[\\s]"), "").removeSuffix("钟").removeSuffix("整")
        var period = -1
        for ((word, p) in PERIODS) {
            if (s.startsWith(word)) { period = p; s = s.substring(word.length); break }
        }
        var hour: Int
        var minute: Int
        val colon = CLOCK_COLON.find(s)
        if (colon != null) {
            hour = colon.groupValues[1].toInt()
            minute = colon.groupValues[2].toInt()
        } else {
            val m = CLOCK_CN.find(s) ?: return null
            hour = parseInt(m.groupValues[1]) ?: return null
            minute = when {
                m.groupValues[2].isNotEmpty() -> 30
                m.groupValues[3].isNotEmpty() -> 15
                m.groupValues[4].isNotEmpty() -> 45
                m.groupValues[5].isNotEmpty() -> parseInt(m.groupValues[5]) ?: return null
                else -> 0
            }
        }
        if (hour !in 0..24 || minute !in 0..59) return null
        if (hour == 24) hour = 0
        when (period) {
            0 -> if (hour == 12) hour = 0                    // 凌晨十二点
            1 -> if (hour == 12) hour = 12                   // 上午十二点 = noon, as people mean it
            2 -> if (hour in 1..10) hour += 12               // 中午一点 = 13:00, 中午12点 = 12:00
            3 -> if (hour in 1..11) hour += 12
            4 -> if (hour in 1..11) hour += 12 else if (hour == 12) hour = 0
            5 -> if (hour == 12) hour = 0
        }
        return ClockTime(hour, minute, period >= 0)
    }

    /**
     * Without "上午/下午", "三点" may be 03:00 or 15:00: take whichever comes next from [nowMinutes]
     * (minutes since midnight), the way people mean it when setting an alarm.
     */
    fun resolveAmbiguous(t: ClockTime, nowMinutes: Int): ClockTime {
        if (t.periodGiven || t.hour == 0 || t.hour >= 12) return t
        val am = t.hour * 60 + t.minute
        val pm = am + 12 * 60
        fun until(target: Int) = ((target - nowMinutes) % 1440 + 1440) % 1440
        return if (until(pm) < until(am)) t.copy(hour = t.hour + 12) else t
    }
}
