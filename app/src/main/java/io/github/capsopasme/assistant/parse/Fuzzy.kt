package io.github.capsopasme.assistant.parse

/**
 * Fuzzy matching for app labels and contact names, tolerant of what speech recognition does to
 * them: extra or missing characters, homophones (compared through pinyin), spaces and case.
 */
object Fuzzy {

    private val STRIP = Regex("[\\s\\p{Punct}·•・\\-_'’“”\"（）()【】\\[\\]《》<>，。！？、：；~～]")

    fun normalize(s: String): String = STRIP.replace(s.lowercase(), "")

    /** Similarity in 0..1 between two normalized strings. */
    fun score(query: String, candidate: String): Double {
        if (query.isEmpty() || candidate.isEmpty()) return 0.0
        if (query == candidate) return 1.0
        val shorter = minOf(query.length, candidate.length).toDouble()
        val longer = maxOf(query.length, candidate.length).toDouble()
        val ratio = shorter / longer
        if (candidate.startsWith(query)) return 0.86 + 0.1 * ratio
        if (candidate.contains(query)) return 0.8 + 0.1 * ratio
        // the user said more than the label: "打开微信app" → "微信app" vs "微信"
        if (query.contains(candidate) && candidate.length >= 2) return 0.78 + 0.1 * ratio
        if (isSubsequence(query, candidate) && query.length >= 2) return 0.7 + 0.1 * ratio
        val d = levenshtein(query, candidate)
        return (1.0 - d / longer).coerceAtLeast(0.0) * 0.8
    }

    /**
     * Best score over the forms of both sides (the text itself and, if given, its pinyin):
     * "斗鱼" and "抖音"-like homophone mistakes still match through pinyin, at a small discount.
     */
    fun bestScore(query: String, queryPinyin: String?, candidate: String, candidatePinyin: String?): Double {
        var best = score(query, candidate)
        if (best < 1.0 && queryPinyin != null && candidatePinyin != null &&
            queryPinyin.isNotEmpty() && candidatePinyin.isNotEmpty()
        ) {
            best = maxOf(best, score(queryPinyin, candidatePinyin) * 0.96)
        }
        return best
    }

    fun isSubsequence(needle: String, hay: String): Boolean {
        var i = 0
        for (c in hay) if (i < needle.length && needle[i] == c) i++
        return i == needle.length
    }

    fun levenshtein(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        var prev = IntArray(b.length + 1) { it }
        var cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = minOf(cur[j - 1] + 1, prev[j] + 1, prev[j - 1] + cost)
            }
            val t = prev; prev = cur; cur = t
        }
        return prev[b.length]
    }

    data class Match<T>(val item: T, val score: Double)

    /** Ranks [items] by [forms] (normalized text + optional pinyin) against the query. */
    fun <T> rank(
        query: String,
        queryPinyin: String?,
        items: List<T>,
        forms: (T) -> List<Pair<String, String?>>,
    ): List<Match<T>> {
        val q = normalize(query)
        if (q.isEmpty()) return emptyList()
        val qp = queryPinyin?.let { normalize(it) }
        return items.asSequence()
            .map { item ->
                Match(item, forms(item).maxOfOrNull { (text, py) -> bestScore(q, qp, text, py?.let { normalize(it) }) } ?: 0.0)
            }
            .filter { it.score > 0.3 }
            .sortedByDescending { it.score }
            .toList()
    }
}
