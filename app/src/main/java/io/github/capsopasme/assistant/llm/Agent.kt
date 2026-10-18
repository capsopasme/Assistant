package io.github.capsopasme.assistant.llm

import android.content.Context
import android.os.SystemClock
import io.github.capsopasme.assistant.action.Action
import io.github.capsopasme.assistant.action.ActionExecutor
import io.github.capsopasme.assistant.action.ActionResult
import io.github.capsopasme.assistant.action.MediaCommand
import io.github.capsopasme.assistant.action.SwitchTarget
import io.github.capsopasme.assistant.action.TravelMode
import io.github.capsopasme.assistant.util.Prefs
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * One request to the model with the phone's tools: the model answers, or calls tools which are
 * executed and reported back, for a few rounds at most.
 */
class Agent(private val context: Context, private val executor: ActionExecutor) {

    data class Outcome(val text: String?, val results: List<ActionResult>) {
        val leftApp get() = results.any { it.leftApp }
    }

    private val prefs = Prefs.get(context)

    suspend fun run(userText: String, onStep: (String) -> Unit): Outcome {
        val provider = prefs.provider
        val client = LlmClient(provider, prefs.apiKey(provider), prefs.model(provider), prefs.baseUrl(provider))
        History.expireIfStale()
        val messages = JSONArray().put(JSONObject().put("role", "system").put("content", systemPrompt()))
        for (m in History.snapshot()) messages.put(m)
        // This exchange goes into the history only once it is complete. A request cut short (the
        // panel closed while a tool ran, a network error) would otherwise leave a tool call
        // without its result behind, and every request of the next minutes would be refused (400).
        val turn = ArrayList<JSONObject>()
        fun add(m: JSONObject) {
            messages.put(m)
            turn += m
        }
        add(JSONObject().put("role", "user").put("content", userText))

        val results = mutableListOf<ActionResult>()
        repeat(MAX_ROUNDS) {
            val reply = client.chat(messages, Tools.specs)
            add(reply.message)
            if (reply.toolCalls.isEmpty()) {
                History.commit(turn)
                return Outcome(reply.text, results)
            }

            val round = mutableListOf<ActionResult>()
            for (call in reply.toolCalls) {
                val action = Tools.toAction(call)
                val result = when (action) {
                    null -> ActionResult(false, "无法识别的工具调用：${call.name}")
                    else -> {
                        onStep(Tools.describe(action))
                        executor.execute(action, fromModel = true)
                    }
                }
                results += result
                round += result
                val content = JSONObject().put("ok", result.ok).put("message", result.message)
                if (result.candidates.isNotEmpty()) content.put("candidates", JSONArray(result.candidates))
                add(JSONObject().put("role", "tool").put("tool_call_id", call.id).put("content", content.toString()))
            }
            // Everything worked: the results already say what happened (they are read back from
            // the phone), so a second round trip would only rephrase them and cost a second or
            // more. Only failures and ambiguities go back to the model.
            if (round.all { it.ok && it.candidates.isEmpty() }) {
                turn += JSONObject().put("role", "assistant").put("content", reply.text ?: round.joinToString("；") { it.message })
                History.commit(turn)
                // earlier failures were what this round retried
                return Outcome(reply.text, results.filter { it.ok })
            }
        }
        turn += JSONObject().put("role", "assistant").put("content", results.joinToString("；") { it.message })
        History.commit(turn)
        return Outcome(null, results)
    }

    /** The time goes last: everything before it stays the same between requests (prompt cache). */
    private fun systemPrompt(): String {
        val now = Date()
        val date = SimpleDateFormat("yyyy年M月d日 EEEE HH:mm", Locale.CHINA).format(now)
        return """
你是运行在用户手机（小米13，HyperOS 3，已 root）上的私人助手，用户通过文字或语音转文字向你下指令。

规则：
- 凡是手机能做的操作（闹钟、倒计时、打电话、导航、打开应用、放音乐、系统开关、音量、亮度、网页搜索），必须调用工具去做，不要只回答“好的”。一句话里有多个操作就在同一次回复里一起调用多个工具。
- 调用工具时 content 留空（执行结果会直接显示给用户）；只有用户同时还问了问题时，才在调用工具的同一次回复的 content 里直接回答那个问题。
- 闹钟用 24 小时制；“明早七点”就是 7:00；相对时间（“二十分钟后”）用倒计时。
- 工具返回失败或给出候选（candidates）时：能确定就重新调用一次，不能确定就简短地问用户。
- 做不到的事（例如在微信里发消息、点外卖、读屏幕内容）直接说明做不到，不要假装完成。
- 回答问题时用简体中文，简洁直接，一般不超过 150 字；不要用 Markdown 标题和表格。
- 语音转写可能有同音错字，按最合理的意思理解。

现在是 $date。
""".trim()
    }

    /** The last few exchanges, so follow-ups like "再大一点" work; forgotten after a few idle minutes. */
    private object History {
        private const val MAX = 16
        private const val TTL_MS = 5 * 60_000L
        private val messages = ArrayList<JSONObject>()
        private var lastUse = 0L

        @Synchronized fun snapshot(): List<JSONObject> = ArrayList(messages)

        /** Adds one complete exchange (user message … final assistant message). */
        @Synchronized fun commit(turn: List<JSONObject>) {
            messages += turn
            lastUse = SystemClock.elapsedRealtime()
            // trim from the front, always restarting at a user message so tool replies keep their calls
            if (messages.size > MAX) {
                var i = messages.size - MAX
                while (i < messages.size && messages[i].optString("role") != "user") i++
                if (i < messages.size) messages.subList(0, i).clear()
            }
        }

        @Synchronized fun expireIfStale() {
            if (SystemClock.elapsedRealtime() - lastUse > TTL_MS) messages.clear()
        }

        @Synchronized fun clear() = messages.clear()
    }

    fun clearHistory() = History.clear()

    companion object {
        private const val MAX_ROUNDS = 4
    }
}

/** Tool definitions for the model and their translation into [Action]s. */
object Tools {

    private fun fn(name: String, description: String, properties: JSONObject, required: List<String> = emptyList()) =
        JSONObject().put("type", "function").put(
            "function", JSONObject()
                .put("name", name)
                .put("description", description)
                .put("parameters", JSONObject().put("type", "object").put("properties", properties).put("required", JSONArray(required)))
        )

    private fun str(desc: String, enum: List<String>? = null) = JSONObject().put("type", "string").put("description", desc)
        .apply { enum?.let { put("enum", JSONArray(it)) } }

    private fun int(desc: String) = JSONObject().put("type", "integer").put("description", desc)

    val specs: JSONArray by lazy {
        JSONArray()
            .put(fn("open_app", "打开手机上的应用", JSONObject().put("name", str("应用名，例如 微信、设置、相机")), listOf("name")))
            .put(fn("set_alarm", "设置闹钟", JSONObject()
                .put("hour", int("0-23，24 小时制"))
                .put("minute", int("0-59"))
                .put("label", str("闹钟备注，可省略"))
                .put("repeat", JSONObject().put("type", "array").put("description", "重复的星期，可省略表示只响一次")
                    .put("items", str("星期", listOf("mon", "tue", "wed", "thu", "fri", "sat", "sun")))),
                listOf("hour", "minute")))
            .put(fn("set_timer", "开始倒计时（计时器）", JSONObject()
                .put("seconds", int("倒计时总秒数"))
                .put("label", str("备注，可省略")), listOf("seconds")))
            .put(fn("show_alarms", "打开闹钟列表（查看、删除或修改闹钟只能让用户自己操作）", JSONObject()))
            .put(fn("make_call", "打电话", JSONObject().put("target", str("联系人姓名（按通讯录里的写法）或电话号码")), listOf("target")))
            .put(fn("navigate", "用地图导航或规划路线", JSONObject()
                .put("destination", str("目的地名称"))
                .put("mode", str("出行方式，默认 driving", listOf("driving", "walking", "transit", "bicycling"))), listOf("destination")))
            .put(fn("play_music", "用本地音乐播放器放歌", JSONObject()
                .put("query", str("歌名；随机播放某歌手时填歌手名；随机播放全部时省略"))
                .put("artist", str("歌手，可省略"))
                .put("shuffle", JSONObject().put("type", "boolean").put("description", "是否随机播放"))))
            .put(fn("media_control", "控制正在播放的音乐/视频", JSONObject()
                .put("action", str("操作", listOf("play", "pause", "next", "previous"))), listOf("action")))
            .put(fn("set_switch", "打开或关闭系统开关", JSONObject()
                .put("name", str("开关", SwitchTarget.entries.map { it.toolName }))
                .put("state", str("on 打开 / off 关闭 / toggle 切换", listOf("on", "off", "toggle"))), listOf("name", "state")))
            .put(fn("set_volume", "调节媒体音量，percent 与 delta 二选一", JSONObject()
                .put("percent", int("目标音量 0-100"))
                .put("delta", int("相对调整，例如 +15 或 -15"))))
            .put(fn("set_brightness", "调节屏幕亮度，percent 与 delta 二选一", JSONObject()
                .put("percent", int("目标亮度 1-100"))
                .put("delta", int("相对调整，例如 +15 或 -15"))))
            .put(fn("web_search", "在浏览器里搜索网页（用户想自己看搜索结果时用）", JSONObject().put("query", str("搜索词")), listOf("query")))
    }

    private val DAY = mapOf(
        "sun" to Calendar.SUNDAY, "mon" to Calendar.MONDAY, "tue" to Calendar.TUESDAY, "wed" to Calendar.WEDNESDAY,
        "thu" to Calendar.THURSDAY, "fri" to Calendar.FRIDAY, "sat" to Calendar.SATURDAY,
    )

    fun toAction(call: ToolCall): Action? {
        val a = runCatching { JSONObject(call.arguments) }.getOrElse { JSONObject() }
        fun s(k: String) = a.optString(k).trim().takeIf { it.isNotEmpty() && it != "null" }
        fun i(k: String): Int? = if (a.has(k) && !a.isNull(k)) a.optDouble(k).takeIf { !it.isNaN() }?.toInt() else null
        return when (call.name) {
            "open_app" -> s("name")?.let { Action.OpenApp(it) }
            "set_alarm" -> {
                val h = i("hour") ?: return null
                val m = i("minute") ?: 0
                val days = a.optJSONArray("repeat")?.let { arr -> (0 until arr.length()).mapNotNull { DAY[arr.optString(it).lowercase().take(3)] } }
                Action.SetAlarm(h, m, s("label"), days?.takeIf { it.isNotEmpty() })
            }
            "set_timer" -> i("seconds")?.takeIf { it > 0 }?.let { Action.SetTimer(it, s("label")) }
            "show_alarms" -> Action.ShowAlarms
            "make_call" -> s("target")?.let { Action.Call(it) }
            "navigate" -> s("destination")?.let {
                Action.Navigate(it, when (s("mode")) {
                    "walking" -> TravelMode.WALKING
                    "transit" -> TravelMode.TRANSIT
                    "bicycling" -> TravelMode.BICYCLING
                    else -> TravelMode.DRIVING
                })
            }
            "play_music" -> Action.PlayMusic(s("query"), s("artist"), a.optBoolean("shuffle", false))
            "media_control" -> when (s("action")) {
                "play" -> Action.Media(MediaCommand.PLAY)
                "pause" -> Action.Media(MediaCommand.PAUSE)
                "next" -> Action.Media(MediaCommand.NEXT)
                "previous" -> Action.Media(MediaCommand.PREVIOUS)
                else -> null
            }
            "set_switch" -> {
                val target = s("name")?.let { SwitchTarget.fromTool(it) } ?: return null
                Action.Switch(target, when (s("state")) { "on" -> true; "off" -> false; else -> null })
            }
            "set_volume" -> Action.Volume(i("percent"), i("delta")).takeIf { it.percent != null || it.delta != null }
            "set_brightness" -> Action.Brightness(i("percent"), i("delta")).takeIf { it.percent != null || it.delta != null }
            "web_search" -> s("query")?.let { Action.WebSearch(it) }
            else -> null
        }
    }

    /** Short progress line while a tool runs. */
    fun describe(a: Action): String = when (a) {
        is Action.OpenApp -> "打开 ${a.name}"
        is Action.SetTimer -> "倒计时 ${ActionExecutor.formatDuration(a.seconds)}"
        is Action.SetAlarm -> "设置 %02d:%02d 闹钟".format(a.hour, a.minute)
        Action.ShowAlarms -> "打开闹钟"
        is Action.Call -> "呼叫 ${a.target}"
        is Action.Navigate -> "导航到 ${a.destination}"
        is Action.PlayMusic -> if (a.query == null) "随机播放" else "播放 ${a.query}"
        is Action.Media -> a.command.label
        is Action.Switch -> "${when (a.on) { true -> "打开"; false -> "关闭"; null -> "切换" }}${a.target.label}"
        is Action.Volume -> "调节音量"
        is Action.Brightness -> "调节亮度"
        is Action.WebSearch -> "搜索 ${a.query}"
    }
}
