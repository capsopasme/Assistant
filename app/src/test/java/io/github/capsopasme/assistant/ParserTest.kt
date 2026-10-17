package io.github.capsopasme.assistant

import io.github.capsopasme.assistant.action.Action
import io.github.capsopasme.assistant.action.MediaCommand
import io.github.capsopasme.assistant.action.SwitchTarget
import io.github.capsopasme.assistant.action.TravelMode
import io.github.capsopasme.assistant.parse.ChineseNumber
import io.github.capsopasme.assistant.parse.Fuzzy
import io.github.capsopasme.assistant.parse.LocalParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class ParserTest {

    private val noon = 12 * 60

    private fun p(s: String, now: Int = noon) = LocalParser.parse(s, now)

    @Test fun numbers() {
        assertEquals(25.0, ChineseNumber.parse("二十五"))
        assertEquals(10.0, ChineseNumber.parse("十"))
        assertEquals(15.0, ChineseNumber.parse("十五"))
        assertEquals(120.0, ChineseNumber.parse("一百二"))
        assertEquals(105.0, ChineseNumber.parse("一百零五"))
        assertEquals(3500.0, ChineseNumber.parse("三千五"))
        assertEquals(2.0, ChineseNumber.parse("两"))
        assertEquals(2.5, ChineseNumber.parse("2.5"))
        assertEquals(25000.0, ChineseNumber.parse("两万五"))
        assertNull(ChineseNumber.parse("三四"))
        assertNull(ChineseNumber.parse("十百"))
        assertNull(ChineseNumber.parse("abc"))
    }

    @Test fun durations() {
        assertEquals(600, ChineseNumber.parseDurationSeconds("十分钟"))
        assertEquals(3600, ChineseNumber.parseDurationSeconds("一小时"))
        assertEquals(4800, ChineseNumber.parseDurationSeconds("1小时20分"))
        assertEquals(4800, ChineseNumber.parseDurationSeconds("一个小时二十分钟"))
        assertEquals(5400, ChineseNumber.parseDurationSeconds("一个半小时"))
        assertEquals(1800, ChineseNumber.parseDurationSeconds("半小时"))
        assertEquals(1800, ChineseNumber.parseDurationSeconds("半个小时"))
        assertEquals(9000, ChineseNumber.parseDurationSeconds("两个半小时"))
        assertEquals(330, ChineseNumber.parseDurationSeconds("5分半"))
        assertEquals(90, ChineseNumber.parseDurationSeconds("一分半钟"))
        assertEquals(900, ChineseNumber.parseDurationSeconds("一刻钟"))
        assertEquals(90, ChineseNumber.parseDurationSeconds("90秒"))
        assertEquals(330, ChineseNumber.parseDurationSeconds("五分三十秒"))
        assertEquals(7200, ChineseNumber.parseDurationSeconds("两个钟头"))
        assertEquals(3900, ChineseNumber.parseDurationSeconds("一小时零五分"))
        assertNull(ChineseNumber.parseDurationSeconds("十"))
        assertNull(ChineseNumber.parseDurationSeconds("十分钟一小时"))
        assertNull(ChineseNumber.parseDurationSeconds("明天"))
    }

    @Test fun clock() {
        assertEquals(ChineseNumber.ClockTime(7, 0, false), ChineseNumber.parseClock("七点"))
        assertEquals(ChineseNumber.ClockTime(7, 30, false), ChineseNumber.parseClock("7点半"))
        assertEquals(ChineseNumber.ClockTime(7, 15, false), ChineseNumber.parseClock("七点一刻"))
        assertEquals(ChineseNumber.ClockTime(8, 45, false), ChineseNumber.parseClock("八点三刻"))
        assertEquals(ChineseNumber.ClockTime(7, 5, false), ChineseNumber.parseClock("七点零五"))
        assertEquals(ChineseNumber.ClockTime(7, 30, true), ChineseNumber.parseClock("早上7:30"))
        assertEquals(ChineseNumber.ClockTime(15, 15, true), ChineseNumber.parseClock("下午三点十五"))
        assertEquals(ChineseNumber.ClockTime(22, 0, true), ChineseNumber.parseClock("晚上十点"))
        assertEquals(ChineseNumber.ClockTime(0, 0, true), ChineseNumber.parseClock("晚上12点"))
        assertEquals(ChineseNumber.ClockTime(12, 0, true), ChineseNumber.parseClock("中午12点"))
        assertEquals(ChineseNumber.ClockTime(13, 0, true), ChineseNumber.parseClock("中午一点"))
        assertEquals(ChineseNumber.ClockTime(2, 0, true), ChineseNumber.parseClock("凌晨两点"))
        assertNull(ChineseNumber.parseClock("七点钟以后"))
        // ambiguous hours pick the next occurrence
        val seven = ChineseNumber.ClockTime(7, 0, false)
        assertEquals(7, ChineseNumber.resolveAmbiguous(seven, 20 * 60).hour)
        assertEquals(19, ChineseNumber.resolveAmbiguous(seven, 10 * 60).hour)
        assertEquals(15, ChineseNumber.resolveAmbiguous(ChineseNumber.ClockTime(3, 0, false), 13 * 60).hour)
    }

    @Test fun timers() {
        assertEquals(Action.SetTimer(600), p("倒计时十分钟"))
        assertEquals(Action.SetTimer(600), p("十分钟倒计时"))
        assertEquals(Action.SetTimer(600), p("帮我设置一个十分钟的倒计时。"))
        assertEquals(Action.SetTimer(300), p("计时5分钟"))
        assertEquals(Action.SetTimer(1800, "喝水"), p("半小时后提醒我喝水"))
        assertEquals(Action.SetTimer(1200), p("二十分钟后叫我"))
        assertNull(p("开始计时"))
        assertNull(p("下班后提醒我买菜"))
    }

    @Test fun alarms() {
        assertEquals(Action.SetAlarm(7, 0), p("明天早上七点叫我", 22 * 60))
        assertEquals(Action.SetAlarm(7, 30), p("设个七点半的闹钟", 22 * 60))
        assertEquals(Action.SetAlarm(19, 30), p("设个七点半的闹钟", 15 * 60))
        assertEquals(Action.SetAlarm(6, 50, null, (Calendar.MONDAY..Calendar.FRIDAY).toList()), p("工作日早上6点50的闹钟"))
        assertEquals(Action.SetAlarm(8, 0, null, (Calendar.SUNDAY..Calendar.SATURDAY).toList()), p("每天早上八点叫醒我"))
        assertEquals(Action.ShowAlarms, p("打开闹钟"))
        assertNull(p("后天早上八点的闹钟"))
        assertNull(p("删除所有闹钟"))
    }

    @Test fun switches() {
        assertEquals(Action.Switch(SwitchTarget.BLUETOOTH, true), p("打开蓝牙"))
        assertEquals(Action.Switch(SwitchTarget.BLUETOOTH, false), p("把蓝牙关了"))
        assertEquals(Action.Switch(SwitchTarget.BLUETOOTH, true), p("开一下蓝牙"))
        assertEquals(Action.Switch(SwitchTarget.WIFI, false), p("关闭WiFi"))
        assertEquals(Action.Switch(SwitchTarget.AIRPLANE, true), p("请帮我打开飞行模式"))
        assertEquals(Action.Switch(SwitchTarget.DARK_MODE, true), p("切换到深色模式"))
        assertEquals(Action.Switch(SwitchTarget.DARK_MODE, false), p("关闭暗夜模式"))
        assertEquals(Action.Switch(SwitchTarget.EYE_CARE, true), p("开启护眼模式"))
        assertEquals(Action.Switch(SwitchTarget.FLASHLIGHT, true), p("手电筒打开"))
        assertEquals(Action.Switch(SwitchTarget.FLASHLIGHT, false), p("关掉手电筒"))
        assertEquals(Action.Switch(SwitchTarget.DND, true), p("打开勿扰模式"))
        assertEquals(Action.Switch(SwitchTarget.MOBILE_DATA, false), p("关闭移动数据"))
        assertNull(p("流量还剩多少"))
        assertNull(p("打开蓝牙然后放首歌"))
        assertEquals(Action.OpenApp("蓝牙设置"), p("打开蓝牙设置"))
    }

    @Test fun mediaAndMusic() {
        assertEquals(Action.Media(MediaCommand.PAUSE), p("暂停"))
        assertEquals(Action.Media(MediaCommand.NEXT), p("下一首"))
        assertEquals(Action.Media(MediaCommand.NEXT), p("播放下一首"))
        assertEquals(Action.Media(MediaCommand.PLAY), p("继续播放"))
        assertEquals(Action.PlayMusic(shuffle = true), p("随机播放音乐"))
        assertEquals(Action.PlayMusic(shuffle = true), p("放首歌"))
        assertEquals(Action.PlayMusic(query = "晴天"), p("播放晴天"))
        assertEquals(Action.PlayMusic(query = "晴天", artist = "周杰伦"), p("播放周杰伦的晴天"))
        assertEquals(Action.PlayMusic(query = "周杰伦", shuffle = true), p("我想听周杰伦的歌"))
        assertEquals(Action.PlayMusic(query = "晴天"), p("来一首《晴天》"))
        assertNull(p("播放b站视频"))
    }

    @Test fun levels() {
        assertEquals(Action.Volume(percent = 50), p("音量调到50"))
        assertEquals(Action.Volume(percent = 30), p("把音量调到百分之三十"))
        assertEquals(Action.Volume(percent = 30), p("音量开到三成"))
        assertEquals(Action.Volume(delta = 15), p("声音大一点"))
        assertEquals(Action.Volume(delta = -15), p("把音量调小一点"))
        assertEquals(Action.Volume(percent = 0), p("静音"))
        assertEquals(Action.Brightness(delta = 15), p("屏幕亮一点"))
        assertEquals(Action.Brightness(percent = 100), p("亮度调到最高"))
        assertEquals(Action.Brightness(percent = 40), p("亮度40%"))
        assertNull(p("屏幕截图"))
    }

    @Test fun callsNavigationSearchApps() {
        assertEquals(Action.Call("妈妈"), p("给妈妈打电话"))
        assertEquals(Action.Call("张三"), p("打电话给张三"))
        assertEquals(Action.Call("妈"), p("给我妈打个电话"))
        assertEquals(Action.Navigate("天安门"), p("导航到天安门"))
        assertEquals(Action.Navigate("西湖", TravelMode.WALKING), p("步行去西湖"))
        assertEquals(Action.Navigate("火车站"), p("去火车站怎么走"))
        assertEquals(Action.Navigate("家"), p("回家"))
        assertEquals(Action.WebSearch("今天的新闻"), p("搜索今天的新闻"))
        assertEquals(Action.OpenApp("微信"), p("打开微信"))
        assertEquals(Action.OpenApp("哔哩哔哩"), p("打开哔哩哔哩app"))
        assertEquals(Action.OpenApp("设置"), p("帮我打开设置吧"))
        assertNull(p("今天天气怎么样"))
        assertNull(p("讲个笑话"))
    }

    @Test fun fuzzy() {
        assertEquals(1.0, Fuzzy.score("微信", "微信"), 1e-9)
        assertTrue(Fuzzy.score("哔哩", "哔哩哔哩") > 0.85)
        assertTrue(Fuzzy.score("微信app", "微信") > 0.75)
        assertTrue(Fuzzy.score("高德", "高德地图") > 0.85)
        assertTrue(Fuzzy.score("支付包", "支付宝") < 0.75)
        assertTrue(Fuzzy.bestScore("支付包", "zhifubao", "支付宝", "zhifubao") > 0.9)
        assertEquals(1, Fuzzy.levenshtein("kitten", "sitten"))
    }
}
