package io.github.capsopasme.assistant.action

import android.content.Context
import android.provider.ContactsContract.CommonDataKinds.Phone
import io.github.capsopasme.assistant.parse.Fuzzy

/** Finds who to call from a spoken name, tolerant of homophones and family terms. */
object Contacts {

    data class Person(val name: String, val number: String) {
        val norm = Fuzzy.normalize(name)
        val pinyin: String? = Pinyin.of(norm)
    }

    data class Found(val person: Person?, val candidates: List<Person>)

    private val RELATIONS = listOf(
        listOf("妈妈", "妈", "老妈", "母亲", "娘", "妈咪", "mom"),
        listOf("爸爸", "爸", "老爸", "父亲", "爹", "爸比", "dad"),
        listOf("老婆", "媳妇", "妻子", "夫人", "爱人"),
        listOf("老公", "丈夫", "先生", "爱人"),
        listOf("哥哥", "哥", "老哥"),
        listOf("姐姐", "姐", "老姐"),
        listOf("弟弟", "弟", "老弟"),
        listOf("妹妹", "妹", "老妹"),
        listOf("爷爷", "祖父"),
        listOf("奶奶", "祖母"),
        listOf("外公", "姥爷", "外祖父"),
        listOf("外婆", "姥姥", "外祖母"),
    )

    private val PHONE_NUMBER = Regex("^[+0-9\\-\\s()]{3,20}$")

    fun isNumber(s: String) = PHONE_NUMBER.matches(s) && s.count { it.isDigit() } >= 3

    fun normalizeNumber(s: String) = s.filter { it.isDigit() || it == '+' }

    fun load(context: Context): List<Person> {
        // one number per person: the primary one, else a mobile, else the first
        val best = LinkedHashMap<String, Pair<Int, Person>>()
        context.contentResolver.query(
            Phone.CONTENT_URI,
            arrayOf(Phone.DISPLAY_NAME, Phone.NUMBER, Phone.IS_SUPER_PRIMARY, Phone.TYPE),
            null, null, null,
        )?.use { c ->
            while (c.moveToNext()) {
                val name = c.getString(0)?.trim().orEmpty()
                val number = c.getString(1)?.trim().orEmpty()
                if (name.isEmpty() || number.isEmpty()) continue
                val rank = (if (c.getInt(2) != 0) 2 else 0) + (if (c.getInt(3) == Phone.TYPE_MOBILE) 1 else 0)
                val prev = best[name]
                if (prev == null || rank > prev.first) best[name] = rank to Person(name, number)
            }
        }
        return best.values.map { it.second }
    }

    fun find(people: List<Person>, spoken: String): Found {
        val query = Fuzzy.normalize(spoken)
        if (query.isEmpty() || people.isEmpty()) return Found(null, emptyList())
        people.firstOrNull { it.norm == query }?.let { return Found(it, emptyList()) }

        val variants = RELATIONS.firstOrNull { group -> query in group }?.let { listOf(query) + it } ?: listOf(query)
        val scores = people.map { p ->
            p to variants.maxOf { v -> Fuzzy.bestScore(v, Pinyin.of(v), p.norm, p.pinyin) }
        }.filter { it.second > 0.4 }.sortedByDescending { it.second }
        val best = scores.firstOrNull() ?: return Found(null, emptyList())
        val second = scores.getOrNull(1)
        val candidates = scores.take(5).map { it.first }
        // clear winner only: calling the wrong person is worse than asking
        val clear = best.second >= 0.8 && (second == null || best.second - second.second >= 0.06)
        return if (clear) Found(best.first, candidates) else Found(null, candidates)
    }
}
