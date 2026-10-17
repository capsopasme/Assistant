package io.github.capsopasme.assistant.parse

import io.github.capsopasme.assistant.action.Action
import io.github.capsopasme.assistant.action.MediaCommand
import io.github.capsopasme.assistant.action.SwitchTarget
import io.github.capsopasme.assistant.action.TravelMode
import java.util.Calendar

/**
 * Common commands handled on the phone without the model: instant, offline, free, and predictable.
 * Every rule must match the WHOLE sentence; anything unusual (compound sentences, extra
 * conditions) returns null and goes to the model instead of being half-understood here.
 */
object LocalParser {

    fun parse(raw: String, nowMinutes: Int): Action? {
        val t = clean(raw)
        if (t.isEmpty() || t.length > 40) return null
        // several commands in one sentence: the model sequences them
        if (Regex("然后|并且|接着|同时|之后再|[，,；;]").containsMatchIn(t)) return null

        return media(t)
            ?: shuffleAll(t)
            ?: switch(t)
            ?: timer(t)
            ?: alarm(t, nowMinutes)
            ?: volume(t)
            ?: brightness(t)
            ?: call(t)
            ?: navigate(t)
            ?: search(t)
            ?: playMusic(t)
            ?: openApp(t)
    }

    // ---------------------------------------------------------------------------------------------

    private val LEADING = listOf(
        "麻烦你", "麻烦", "请你", "请", "帮我", "帮忙", "你帮我", "能不能", "可不可以", "可以", "你能",
        "嗯", "那个", "小助手", "助手", "我想要", "我想", "我要", "给我",
    )
    private val TRAILING = listOf("谢谢你", "谢谢", "好吗", "好不好", "可以吗", "行吗", "吗", "吧", "啊", "呀", "哦", "嘛", "呢", "了吧")
    private val AFTER_GEI_WO = Regex("^(打开|关闭|关掉|开|关|放|播放|设|定|来|调|导航|搜索|搜|启动|切换)")

    /** Strips punctuation and politeness so that rules only see the command itself. */
    fun clean(raw: String): String {
        var t = raw.trim().lowercase()
            .replace(Regex("^[\\s\\p{Punct}，。！？、：；~～“”‘’]+|[\\s\\p{Punct}，。！？、：；~～“”‘’]+$"), "")
            .replace(Regex("\\s+"), "")
        var changed = true
        while (changed && t.isNotEmpty()) {
            changed = false
            for (w in LEADING) {
                if (t.startsWith(w) && t.length > w.length) {
                    val rest = t.substring(w.length)
                    // "给我妈打电话" is not "给我" + "妈打电话"
                    if (w == "给我" && !AFTER_GEI_WO.containsMatchIn(rest)) continue
                    // "我要去…" / "我想听…" are handled by the rules themselves
                    if ((w == "我要" || w == "我想" || w == "我想要") && (rest.startsWith("去") || rest.startsWith("听"))) continue
                    t = rest; changed = true; break
                }
            }
            for (w in TRAILING) {
                if (t.endsWith(w) && t.length > w.length) { t = t.dropLast(w.length); changed = true; break }
            }
        }
        return t
    }

    private fun strip(t: String, vararg words: String): String {
        var s = t
        for (w in words.sortedByDescending { it.length }) s = s.replace(w, "")
        return s
    }

    // --- media keys -----------------------------------------------------------------------------

    private val MEDIA = mapOf(
        MediaCommand.PAUSE to setOf(
            "暂停", "暂停播放", "暂停音乐", "暂停一下", "停止播放", "停止音乐", "停一下", "停", "别放了", "不听了",
            "关掉音乐", "关闭音乐", "音乐暂停", "暂停歌曲",
        ),
        MediaCommand.PLAY to setOf("继续", "继续播放", "接着放", "接着播放", "接着听", "恢复播放", "继续放", "继续听", "继续音乐"),
        MediaCommand.NEXT to setOf(
            "下一首", "下一曲", "下首", "切歌", "换一首", "换首歌", "换一首歌", "下一个", "播放下一首", "放下一首", "下一首歌",
        ),
        MediaCommand.PREVIOUS to setOf("上一首", "上一曲", "上首", "上一个", "播放上一首", "放上一首", "上一首歌"),
    )

    private fun media(t: String): Action? {
        val s = t.removeSuffix("一下")
        for ((cmd, words) in MEDIA) if (t in words || s in words) return Action.Media(cmd)
        return null
    }

    // --- music ----------------------------------------------------------------------------------

    private val SHUFFLE_ALL = setOf(
        "随机播放", "随机播放音乐", "随机播放歌曲", "随便放点歌", "随便放首歌", "随便放点音乐", "随便听听", "放歌", "放首歌",
        "放点歌", "放点音乐", "放音乐", "来点音乐", "来首歌", "来点歌", "来一首歌", "播放音乐", "播放歌曲", "听歌", "听音乐",
        "我想听歌", "我要听歌", "我想听音乐", "我要听音乐", "放一首歌", "播放点音乐", "随机来一首", "随便来一首",
    )

    private fun shuffleAll(t: String): Action? =
        if (t in SHUFFLE_ALL) Action.PlayMusic(shuffle = true) else null

    private val MUSIC_PREFIX = Regex("^(随机播放|播放一首|播放一下|播放|放一首|放首|放一下|来一首|来首|我想听|我要听|想听|听一下)(.+)$")
    private val NOT_MUSIC = Regex("视频|电影|电视|节目|综艺|动漫|番剧|直播|新闻|播客|广播|电台|小说|有声书|课程|下一|上一|模式|设置")

    private fun playMusic(t: String): Action? {
        val m = MUSIC_PREFIX.find(t) ?: return null
        val verb = m.groupValues[1]
        var x = m.groupValues[2].trim('《', '》', '“', '”', '"', '\'', '「', '」')
        if (x.isEmpty() || x.length > 30 || NOT_MUSIC.containsMatchIn(x)) return null
        x = x.removeSuffix("这首歌").removeSuffix("这首").removeSuffix("这一首")
        if (x in setOf("歌", "音乐", "歌曲", "点歌", "点音乐", "首歌")) return Action.PlayMusic(shuffle = true)
        val shuffle = verb == "随机播放"
        for (suffix in listOf("的歌曲", "的歌", "的音乐", "的专辑", "的所有歌", "的全部歌曲")) {
            if (x.endsWith(suffix) && x.length > suffix.length) {
                return Action.PlayMusic(query = x.dropLast(suffix.length), shuffle = true)
            }
        }
        val de = x.indexOf('的')
        if (!shuffle && de in 1 until x.length - 1) {
            return Action.PlayMusic(query = x.substring(de + 1), artist = x.substring(0, de))
        }
        return Action.PlayMusic(query = x, shuffle = shuffle)
    }

    // --- switches -------------------------------------------------------------------------------

    private val SWITCH_WORDS: List<Pair<String, SwitchTarget>> = listOf(
        "蓝牙" to SwitchTarget.BLUETOOTH,
        "wifi" to SwitchTarget.WIFI, "wi-fi" to SwitchTarget.WIFI, "wlan" to SwitchTarget.WIFI,
        "无线局域网" to SwitchTarget.WIFI, "无线网络" to SwitchTarget.WIFI, "无线网" to SwitchTarget.WIFI,
        "飞行模式" to SwitchTarget.AIRPLANE, "飞航模式" to SwitchTarget.AIRPLANE,
        "移动数据" to SwitchTarget.MOBILE_DATA, "数据流量" to SwitchTarget.MOBILE_DATA, "蜂窝数据" to SwitchTarget.MOBILE_DATA,
        "流量" to SwitchTarget.MOBILE_DATA, "数据网络" to SwitchTarget.MOBILE_DATA, "移动网络" to SwitchTarget.MOBILE_DATA,
        "深色模式" to SwitchTarget.DARK_MODE, "暗色模式" to SwitchTarget.DARK_MODE, "黑夜模式" to SwitchTarget.DARK_MODE,
        "夜间模式" to SwitchTarget.DARK_MODE, "暗夜模式" to SwitchTarget.DARK_MODE, "暗黑模式" to SwitchTarget.DARK_MODE,
        "黑暗模式" to SwitchTarget.DARK_MODE, "深色主题" to SwitchTarget.DARK_MODE, "黑色模式" to SwitchTarget.DARK_MODE,
        "护眼模式" to SwitchTarget.EYE_CARE, "护眼" to SwitchTarget.EYE_CARE, "阅读模式" to SwitchTarget.EYE_CARE,
        "纸质模式" to SwitchTarget.EYE_CARE,
        "手电筒" to SwitchTarget.FLASHLIGHT, "手电" to SwitchTarget.FLASHLIGHT, "闪光灯" to SwitchTarget.FLASHLIGHT,
        "勿扰模式" to SwitchTarget.DND, "免打扰模式" to SwitchTarget.DND, "免打扰" to SwitchTarget.DND,
        "请勿打扰" to SwitchTarget.DND, "勿扰" to SwitchTarget.DND,
        "定位服务" to SwitchTarget.LOCATION, "位置服务" to SwitchTarget.LOCATION, "位置信息" to SwitchTarget.LOCATION,
        "定位" to SwitchTarget.LOCATION, "gps" to SwitchTarget.LOCATION,
        "自动旋转" to SwitchTarget.AUTO_ROTATE, "屏幕旋转" to SwitchTarget.AUTO_ROTATE, "自动转屏" to SwitchTarget.AUTO_ROTATE,
        "nfc" to SwitchTarget.NFC,
    ).sortedByDescending { it.first.length }

    private val ON_WORDS = setOf(
        "打开", "开启", "开", "启用", "开了", "打开了", "开开", "进入", "切换到", "切到", "换成", "调成", "换到", "使用", "用",
        "切换成", "开起来", "亮起来", "点亮",
    )
    private val OFF_WORDS = setOf(
        "关闭", "关掉", "关", "关了", "关上", "禁用", "停用", "退出", "取消", "关闭了", "关掉了", "灭掉", "熄灭", "不要",
    )
    private val TOGGLE_WORDS = setOf("切换", "开关")

    private fun switch(t: String): Action? {
        val (word, target) = SWITCH_WORDS.firstOrNull { t.contains(it.first) } ?: return null
        // the word must appear once, and what's left must be just the verb
        var rest = t.replaceFirst(word, "")
        if (rest.contains(word)) return null
        rest = strip(rest, "一下", "把", "帮我", "给我", "吧", "的", "手机", "请")
        return when (rest) {
            in ON_WORDS -> Action.Switch(target, true)
            in OFF_WORDS -> Action.Switch(target, false)
            in TOGGLE_WORDS -> Action.Switch(target, null)
            else -> null
        }
    }

    // --- timer ----------------------------------------------------------------------------------

    private val TIMER_SUFFIX = Regex("^(?:设置|设定|设|定|开|来|开始|启动)?(?:一个|个)?(.+?)(?:的)?(?:倒计时|计时器|计时|定时器)$")
    private val TIMER_PREFIX = Regex("^(?:倒计时|计时|定时|开始倒计时|开始计时|倒数)(.+)$")
    private val REMIND_AFTER = Regex("^(.+?)(?:之后|以后|后)(?:提醒我|叫我|喊我|提醒一下我|提醒|通知我)(.*)$")

    private fun timer(t: String): Action? {
        TIMER_SUFFIX.find(t)?.let { m -> ChineseNumber.parseDurationSeconds(m.groupValues[1])?.let { return Action.SetTimer(it) } }
        TIMER_PREFIX.find(t)?.let { m -> ChineseNumber.parseDurationSeconds(m.groupValues[1])?.let { return Action.SetTimer(it) } }
        REMIND_AFTER.find(t)?.let { m ->
            val seconds = ChineseNumber.parseDurationSeconds(m.groupValues[1]) ?: return null
            val label = m.groupValues[2].removePrefix("去").removePrefix("要").removePrefix("该").trim().takeIf { it.isNotEmpty() }
            return Action.SetTimer(seconds, label)
        }
        return null
    }

    // --- alarm ----------------------------------------------------------------------------------

    private val SHOW_ALARMS = setOf(
        "查看闹钟", "我的闹钟", "看看闹钟", "看一下闹钟", "看下闹钟", "闹钟列表", "有哪些闹钟", "打开闹钟", "所有闹钟", "显示闹钟",
    )
    private val ALARM_HINT = Regex("闹钟|闹铃|叫醒我|叫我起床|喊我起床|叫我$|喊我$")

    private fun alarm(t: String, nowMinutes: Int): Action? {
        if (t in SHOW_ALARMS) return Action.ShowAlarms
        if (!ALARM_HINT.containsMatchIn(t)) return null
        var s = t
        var days: List<Int>? = null
        when {
            Regex("每天|每日|天天").containsMatchIn(s) -> days = (Calendar.SUNDAY..Calendar.SATURDAY).toList()
            Regex("工作日|周一到周五|星期一到星期五").containsMatchIn(s) -> days = (Calendar.MONDAY..Calendar.FRIDAY).toList()
            Regex("周末|星期六和星期天|周六周日|周六和周日").containsMatchIn(s) -> days = listOf(Calendar.SATURDAY, Calendar.SUNDAY)
        }
        if (Regex("后天|大后天|下周|星期[一二三四五六日天]|周[一二三四五六日天]").containsMatchIn(s.replace(Regex("周一到周五|星期一到星期五|周六周日|周六和周日|星期六和星期天"), ""))) return null
        s = s.replace("明早", "早上").replace("明晚", "晚上")
        s = strip(
            s, "每天", "每日", "天天", "每个工作日", "工作日", "周一到周五", "星期一到星期五", "每周末", "周末", "周六周日", "周六和周日",
            "星期六和星期天", "明天", "今天", "设置", "设定", "设", "定", "订", "定一个", "一个", "个", "的", "闹钟", "闹铃",
            "叫醒我", "叫我起床", "喊我起床", "起床", "叫我", "喊我", "提醒我", "在", "把", "调到",
        )
        val clock = ChineseNumber.parseClock(s) ?: return null
        val resolved = ChineseNumber.resolveAmbiguous(clock, nowMinutes)
        return Action.SetAlarm(resolved.hour, resolved.minute, null, days)
    }

    // --- volume / brightness -------------------------------------------------------------------

    private val NUMBER_LEVEL = Regex("^([\\d${ChineseNumber.NUM_CHARS}.]+)(%|％|成|percent)?$")
    private val UP = setOf(
        "大一点", "大点", "调大", "调大一点", "加大", "增大", "大些", "大一些", "高一点", "调高", "调高一点", "高点", "放大", "大",
        "高", "加", "提高", "开大", "开大点", "开大一点",
    )
    private val DOWN = setOf(
        "小一点", "小点", "调小", "调小一点", "减小", "降低", "小些", "小一些", "低一点", "调低", "调低一点", "低点", "小", "低",
        "减", "关小", "关小点", "关小一点",
    )
    private val LIGHT_UP = setOf("亮一点", "亮点", "调亮", "调亮一点", "亮一些", "亮些", "高一点", "调高", "调高一点", "高点", "大一点", "调大", "亮")
    private val LIGHT_DOWN = setOf("暗一点", "暗点", "调暗", "调暗一点", "暗一些", "暗些", "低一点", "调低", "调低一点", "低点", "小一点", "调小", "暗")

    private fun level(rest: String): Int? {
        when (rest) {
            "最大", "最高", "最响", "百分之百", "满" -> return 100
            "最小", "最低", "零" -> return 0
            "一半", "中间", "百分之五十" -> return 50
        }
        if (rest.startsWith("百分之")) return ChineseNumber.parseInt(rest.removePrefix("百分之"))?.takeIf { it in 0..100 }
        val m = NUMBER_LEVEL.find(rest) ?: return null
        val n = ChineseNumber.parse(m.groupValues[1]) ?: return null
        val v = if (m.groupValues[2] == "成") n * 10 else n
        return v.toInt().takeIf { it in 0..100 }
    }

    private fun volume(t: String): Action? {
        if (t in setOf("静音", "媒体静音", "音乐静音", "关闭声音", "把声音关掉")) return Action.Volume(percent = 0)
        if (t in setOf("最大音量", "音量最大", "声音最大", "音量开到最大")) return Action.Volume(percent = 100)
        if (!Regex("音量|声音").containsMatchIn(t)) return null
        val rest = strip(t, "媒体音量", "音乐音量", "音量", "声音", "把", "调到", "调成", "调至", "设为", "设置为", "设置成", "设成", "开到",
            "改成", "改为", "一下", "的", "给", "设置").let { if (it.startsWith("到") || it.startsWith("为")) it.drop(1) else it }
        level(rest)?.let { return Action.Volume(percent = it) }
        if (rest in UP) return Action.Volume(delta = 15)
        if (rest in DOWN) return Action.Volume(delta = -15)
        return null
    }

    private fun brightness(t: String): Action? {
        if (!Regex("亮度|屏幕|调亮|调暗").containsMatchIn(t)) return null
        val rest = strip(t, "屏幕亮度", "亮度", "屏幕", "把", "调到", "调成", "调至", "设为", "设置为", "设置成", "设成", "开到",
            "改成", "改为", "一下", "的", "给", "设置").let { if (it.startsWith("到") || it.startsWith("为")) it.drop(1) else it }
        if (rest.isEmpty()) return null
        level(rest)?.let { return Action.Brightness(percent = it) }
        if (rest in LIGHT_UP) return Action.Brightness(delta = 15)
        if (rest in LIGHT_DOWN) return Action.Brightness(delta = -15)
        return null
    }

    // --- calls ----------------------------------------------------------------------------------

    private val CALL_PREFIX = Regex("^(?:打电话给|打个电话给|打一个电话给|拨个电话给|拨电话给|打给|拨打|呼叫|拨号给|拨通)(.+)$")
    private val CALL_GEI = Regex("^给(.+?)(?:打电话|打个电话|打一个电话|拨电话|拨个电话|拨打电话|去个电话|打过去|打一个|打个)$")

    private fun call(t: String): Action? {
        val target = (CALL_PREFIX.find(t) ?: CALL_GEI.find(t))?.groupValues?.get(1)
            ?.removeSuffix("的电话")?.removeSuffix("电话")?.trim()
            ?.let { if (it.length > 1 && it.startsWith("我")) it.drop(1) else it } ?: return null
        if (target.isEmpty() || target.length > 20) return null
        return Action.Call(target)
    }

    // --- navigation -----------------------------------------------------------------------------

    private val NAV_PREFIX = Regex("^(导航到|导航去|导航至|导航|带我去|开车去|步行去|走路去|骑车去|骑行去|坐地铁去|坐公交去|路线去)(.+)$")
    private val NAV_HOW = Regex("^(?:去|到)(.+?)怎么(走|去)$")

    private fun navigate(t: String): Action? {
        if (t == "回家" || t == "导航回家" || t == "带我回家") return Action.Navigate("家")
        NAV_PREFIX.find(t)?.let { m ->
            val dest = m.groupValues[2].removePrefix("到").trim()
            if (dest.isEmpty() || dest.length > 30) return null
            val mode = when {
                m.groupValues[1].startsWith("步行") || m.groupValues[1].startsWith("走路") -> TravelMode.WALKING
                m.groupValues[1].startsWith("骑") -> TravelMode.BICYCLING
                m.groupValues[1].startsWith("坐") -> TravelMode.TRANSIT
                else -> TravelMode.DRIVING
            }
            return Action.Navigate(if (dest == "回家") "家" else dest, mode)
        }
        NAV_HOW.find(t)?.let { m -> return Action.Navigate(m.groupValues[1]) }
        return null
    }

    // --- web search -----------------------------------------------------------------------------

    private val SEARCH_PREFIX = Regex("^(?:搜索一下|搜索|搜一下|搜一搜|百度一下|百度|在网上搜一下|在网上搜|网上搜一下|网上搜|上网搜一下|上网搜|谷歌一下)(.+)$")

    private fun search(t: String): Action? {
        val q = SEARCH_PREFIX.find(t)?.groupValues?.get(1)?.trim() ?: return null
        return if (q.isEmpty()) null else Action.WebSearch(q)
    }

    // --- apps -----------------------------------------------------------------------------------

    private val OPEN_PREFIX = Regex("^(?:打开一下|启动一下|打开|启动|开启|运行|进入|切换到|切到)(.+)$")

    private fun openApp(t: String): Action? {
        var name = OPEN_PREFIX.find(t)?.groupValues?.get(1) ?: return null
        for (suffix in listOf("这个应用", "这个软件", "应用程序", "应用", "软件", "app", "程序")) {
            if (name.endsWith(suffix) && name.length > suffix.length) { name = name.dropLast(suffix.length); break }
        }
        name = name.removePrefix("一下")
        if (name.isEmpty() || name.length > 20) return null
        return Action.OpenApp(name)
    }
}
