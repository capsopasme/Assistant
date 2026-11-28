package io.github.capsopasme.assistant.memory

import kotlin.math.max
import kotlin.math.min

/** What the user asked the memory to do. */
sealed interface MemoryCommand {
    /** "记住我的车位在B2-117": [subject] "车位", [content] the whole statement as said */
    data class Remember(val subject: String, val content: String) : MemoryCommand
    /** "忘掉车位" / "清空记忆" ([all]) */
    data class Forget(val query: String, val all: Boolean = false) : MemoryCommand
    /** "我的车位在哪": answered locally only when a stored memory matches well (see [MemoryParser.score]) */
    data class Recall(val query: String) : MemoryCommand
}

/**
 * Explicit memory commands, parsed on the phone: storing and recalling must work without a
 * network (an underground car park has none). Only clear phrasings are taken here; anything else
 * goes to the model, which has remember / forget tools and sees the stored memories.
 */
object MemoryParser {

    private val PUNCT = Regex("[\\s\\p{Punct}，。！？、：；~～“”‘’…]+")
    private val EDGE = Regex("^[\\s\\p{Punct}，。！？、：；~～“”‘’…]+|[\\s\\p{Punct}，。！？、：；~～“”‘’…]+$")
    private val LEADING = listOf("麻烦你", "麻烦", "请你", "请", "你帮我", "帮我", "帮忙", "你", "给我", "嗯", "呃", "那个", "助手", "小助手")
    private val TRAILING = listOf("谢谢你", "谢谢", "好吗", "好不好", "可以吗", "行吗", "哈", "哦", "啊", "呀", "吧", "啦", "了啊")

    private val REMEMBER = Regex("^(?:记住|记一下|记下来|记下|记着|记好|记录一下|记录下|记录|备忘一下|备忘)(?:一下)?(?:哦|啊|呀)?[:：]?(.+)$")
    private val FORGET = Regex("^(?:忘掉|忘记|忘了|删掉|删除|清除|清空|去掉|不用记|别记)(?:关于)?(.*)$")
    private val ALL = setOf("", "全部", "所有", "所有的", "全部的", "一切", "记忆", "所有记忆", "全部记忆", "全部的记忆", "所有的记忆", "记住的东西", "所有记住的", "你记住的")
    private val QUESTION = Regex("(在哪|哪里|哪儿|哪了|哪呢|在什么地方|多少|是什么|是啥|是几|几号|几点|叫什么|叫啥|什么时候|记得|还记得|吗$|么$|呢$)")

    /** Questions that are about the world, not the user's own notes: never answered from memory. */
    private val NOT_PERSONAL = Regex("^(现在|今天|明天|附近|最近的|这附近)")

    fun parse(raw: String): MemoryCommand? {
        val t = clean(raw)
        if (t.isEmpty() || t.length > 80) return null
        REMEMBER.find(t)?.let { m ->
            val content = m.groupValues[1].replace(EDGE, "")
            // "记得我的车位在哪吗" is a question, not something to store
            if (content.isEmpty() || QUESTION.containsMatchIn(content) && isQuestion(content)) return Recall(content)
            return Remember(subjectOf(content), content)
        }
        FORGET.find(t)?.let { m ->
            var q = m.groupValues[1].replace(EDGE, "")
            q = q.removeSuffix("这条记忆").removeSuffix("的记忆").removeSuffix("记忆").removeSuffix("这件事").removeSuffix("吧")
            q = q.removePrefix("我的").removePrefix("关于").removePrefix("那个")
            // "忘了车停哪了" is someone asking, not an instruction
            if (isQuestion(q)) return Recall(q)
            if (q in ALL) return Forget("", all = true)
            return Forget(q)
        }
        // "车停哪了" / "我的车位在哪": only answered locally if a stored memory matches well
        if (isQuestion(t) && t.length <= 24 && !NOT_PERSONAL.containsMatchIn(t)) return Recall(t)
        return null
    }

    private fun isQuestion(t: String) = QUESTION.containsMatchIn(t)

    fun clean(raw: String): String {
        // case kept: what is remembered is shown back as it was said ("B2-117")
        var t = raw.trim().replace(Regex("\\s+"), " ")
        var changed = true
        while (changed && t.isNotEmpty()) {
            t = t.replace(EDGE, "")
            changed = false
            for (w in LEADING) if (t.startsWith(w) && t.length > w.length + 1) { t = t.substring(w.length); changed = true; break }
            for (w in TRAILING) if (t.endsWith(w) && t.length > w.length + 1) { t = t.dropLast(w.length); changed = true; break }
        }
        return t
    }

    /** separators between what something is and its value, longest first */
    private val SEPARATORS = listOf("放在了", "停在了", "放在", "停在", "停到", "放到", "存在", "藏在", "在", "是", "叫", "为", "等于", "：", ":")

    /** "我的车位在B2-117" → "车位"; "我把钥匙放在鞋柜里了" → "钥匙"; no separator → the statement itself */
    fun subjectOf(statement: String): String {
        val s = statement.replace(EDGE, "")
        var best = -1
        var sepLen = 0
        for (sep in SEPARATORS) {
            val i = s.indexOf(sep)
            if (i > 0 && (best < 0 || i < best)) { best = i; sepLen = sep.length }
        }
        val left = if (best > 0) s.substring(0, best) else s
        var subj = left
        for (w in listOf("我把", "我们的", "我的", "咱的", "咱们的", "我", "把")) subj = subj.removePrefix(w)
        subj = subj.removeSuffix("的").trim()
        if (subj.isEmpty()) subj = s.take(8)
        return canonical(subj.take(16))
    }

    /** a few everyday subjects said many ways */
    private val SYNONYMS: Map<String, String> = buildMap {
        for (w in listOf("车位", "停车位", "车", "车子", "我的车", "爱车", "停车", "车停", "车停的位置", "停车的位置", "停车位置", "车的位置")) put(w, "车位")
        for (w in listOf("钥匙", "家门钥匙", "门钥匙")) put(w, "钥匙")
        for (w in listOf("wifi", "wi-fi", "无线网", "wlan")) put(w, "wifi")
    }

    fun canonical(subject: String): String {
        val s = subject.lowercase().replace(PUNCT, "")
        return SYNONYMS[s] ?: s
    }

    private val QUESTION_WORDS = listOf(
        "还记得", "记得", "在什么地方", "在哪里", "在哪儿", "在哪", "哪里", "哪儿", "哪了", "哪呢", "哪", "是多少", "多少",
        "是什么", "是啥", "是几号", "几号", "是几", "叫什么", "叫啥", "什么时候", "告诉我", "你知道", "知道",
        "我的", "我们的", "咱的", "我把", "我", "吗", "么", "呢", "了", "放", "停", "存", "的",
    )

    /** the thing asked about: "我的车位在哪" → "车位", "车停哪了" → "车位" */
    fun queryCore(query: String): String {
        var s = query.lowercase().replace(PUNCT, "")
        for (w in QUESTION_WORDS) s = s.replace(w, "")
        return canonical(s)
    }

    /**
     * How well a question matches a stored memory, 0..1: the same subject (after synonyms) is 1,
     * one containing the other 0.85, else the overlap of character pairs.
     */
    fun score(query: String, subject: String, content: String): Double {
        val q = queryCore(query)
        if (q.isEmpty()) return 0.0
        val subj = canonical(subject)
        if (q == subj) return 1.0
        // one inside the other, if of similar length: "钥匙" ~ "车钥匙", but not "公司" ~ "公司wifi"
        if (q.length >= 2 && subj.length >= 2 && (subj.contains(q) || q.contains(subj)) &&
            min(q.length, subj.length) * 2 >= max(q.length, subj.length)
        ) return 0.8
        val c = content.lowercase().replace(PUNCT, "")
        if (q.length >= 3 && c.contains(q)) return 0.65
        return max(dice(q, subj), 0.8 * dice(q, c))
    }

    private fun grams(s: String): List<String> = if (s.length < 2) listOf(s) else (0 until s.length - 1).map { s.substring(it, it + 2) }

    fun dice(a: String, b: String): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val ga = grams(a)
        val gb = grams(b).toMutableList()
        var hit = 0
        for (g in ga) { val i = gb.indexOf(g); if (i >= 0) { hit++; gb.removeAt(i) } }
        return 2.0 * hit / (ga.size + grams(b).size)
    }

    /** Passwords, card and ID numbers: not kept here (plain text on the phone, and sent along to the model). */
    private val SECRET = Regex("(密码|口令|pin码|验证码|卡号|银行卡|信用卡|身份证|cvv|安全码|支付码)")

    fun looksSecret(text: String): Boolean = SECRET.containsMatchIn(text.lowercase())

    /** threshold for answering locally from memory */
    const val RECALL_MIN = 0.6

    /** the stored text shortened for a bubble */
    fun short(s: String, n: Int = 40) = if (s.length <= n) s else s.take(min(n, s.length)) + "…"
}
