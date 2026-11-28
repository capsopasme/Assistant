package io.github.capsopasme.assistant.llm

import android.content.Context
import android.os.SystemClock
import io.github.capsopasme.assistant.action.Action
import io.github.capsopasme.assistant.action.ActionExecutor
import io.github.capsopasme.assistant.action.ActionResult
import io.github.capsopasme.assistant.action.MediaCommand
import io.github.capsopasme.assistant.action.SwitchTarget
import io.github.capsopasme.assistant.action.TravelMode
import io.github.capsopasme.assistant.memory.MemoryParser
import io.github.capsopasme.assistant.memory.MemoryStore
import io.github.capsopasme.assistant.screen.ScreenContent
import io.github.capsopasme.assistant.share.Attachment
import io.github.capsopasme.assistant.util.Prefs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** Lets the model look at the screen (the read_screen tool); null result: not possible right now. */
typealias ScreenSource = suspend () -> ScreenContent?

/**
 * One request to the model with the phone's tools: the model answers, or calls tools which are
 * executed and reported back, for a few rounds at most.
 *
 * Context sent with every request, in this order (so that the unchanging part stays a prefix and
 * hits DeepSeek's context cache): the system prompt with the user's memories, files the user
 * shared (pinned: never trimmed away by the history limit), the screen the user asked about
 * (pinned likewise), the recent exchanges, and the new request.
 */
class Agent(private val context: Context, private val executor: ActionExecutor) {

    data class Outcome(val text: String?, val results: List<ActionResult>) {
        val leftApp get() = results.any { it.leftApp }
    }

    private val prefs = Prefs.get(context)
    private val memory = MemoryStore.get(context)

    suspend fun run(userText: String, screen: ScreenSource?, onStep: (String) -> Unit): Outcome {
        val provider = prefs.provider
        val images = provider.supportsImages
        val client = LlmClient(provider, prefs.apiKey(provider), prefs.model(provider), prefs.baseUrl(provider))
        History.expireIfStale()
        val system = withContext(Dispatchers.IO) { systemPrompt(userText) }
        val messages = JSONArray().put(JSONObject().put("role", "system").put("content", system))
        History.files?.let { files ->
            messages.put(JSONObject().put("role", "user").put("content", filesContent(files, images)))
            messages.put(JSONObject().put("role", "assistant").put("content", "好的，这些内容我已经看过了。"))
        }
        History.screen?.let { sc ->
            messages.put(JSONObject().put("role", "user").put("content", screenContent(sc, images, "这是我刚才看着的屏幕。")))
            messages.put(JSONObject().put("role", "assistant").put("content", "好的，我看到了你的屏幕。"))
        }
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
        // summaries of documents and screens need room
        val maxTokens = if (History.files != null || History.screen != null) 2048 else 1024

        val results = mutableListOf<ActionResult>()
        repeat(MAX_ROUNDS) {
            val reply = client.chat(messages, Tools.specs, maxTokens)
            add(reply.message)
            if (reply.toolCalls.isEmpty()) {
                History.commit(turn)
                return Outcome(reply.text, results)
            }

            val round = mutableListOf<ActionResult>()
            var needsModel = false
            for (call in reply.toolCalls) {
                when (call.name) {
                    "read_screen" -> {
                        needsModel = true
                        onStep("看屏幕")
                        val sc = try { screen?.invoke() } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
                        val content: Any = when {
                            sc == null -> JSONObject().put("ok", false).put("message", "现在看不了屏幕（锁屏、没有 root 或不是默认助理）").toString()
                            images && sc.jpeg != null -> screenContent(sc, true, "屏幕内容：")
                            else -> sc.describe()
                        }
                        add(JSONObject().put("role", "tool").put("tool_call_id", call.id).put("content", content))
                        continue
                    }
                }
                val result = withContext(Dispatchers.IO) { memoryTool(call) } ?: run {
                    val action = Tools.toAction(call)
                    when (action) {
                        null -> ActionResult(false, "无法识别的工具调用：${call.name}")
                        else -> {
                            onStep(Tools.describe(action))
                            executor.execute(action, fromModel = true)
                        }
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
            // more. Only failures, ambiguities and a look at the screen go back to the model.
            if (!needsModel && round.all { it.ok && it.candidates.isEmpty() }) {
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

    /** remember / forget: the user's notes, kept on the phone */
    private fun memoryTool(call: ToolCall): ActionResult? {
        val a = runCatching { JSONObject(call.arguments) }.getOrElse { JSONObject() }
        fun s(k: String) = a.optString(k).trim().takeIf { it.isNotEmpty() && it != "null" }
        return when (call.name) {
            "remember" -> {
                val content = s("content") ?: return ActionResult(false, "没有要记住的内容")
                if (MemoryParser.looksSecret(content)) return ActionResult(false, "密码、卡号、证件号这类不记在助手里（以明文存在手机上，提问时还会发给大模型）")
                val subject = s("subject")?.let { MemoryParser.canonical(it) } ?: MemoryParser.subjectOf(content)
                val (_, old) = memory.remember(subject, content)
                ActionResult(true, "记住了：$content" + if (old != null && old.content != content) "（替换了之前的「${MemoryParser.short(old.content, 24)}」）" else "")
            }
            "forget" -> {
                val q = s("subject") ?: return ActionResult(false, "没说要忘掉什么")
                val gone = memory.forget(q)
                if (gone.isEmpty()) ActionResult(false, "没有找到关于「$q」的记忆")
                else ActionResult(true, "已忘掉：" + gone.joinToString("；") { MemoryParser.short(it.content, 24) })
            }
            else -> null
        }
    }

    private fun parts(text: String, images: List<String>): Any {
        if (images.isEmpty()) return text
        val arr = JSONArray().put(JSONObject().put("type", "text").put("text", text))
        for (url in images) arr.put(JSONObject().put("type", "image_url").put("image_url", JSONObject().put("url", url)))
        return arr
    }

    private fun screenContent(sc: ScreenContent, images: Boolean, lead: String): Any =
        parts(lead + "\n" + sc.describe(), if (images) listOfNotNull(sc.dataUrl()) else emptyList())

    private fun filesContent(files: List<Attachment>, images: Boolean): Any {
        val sb = StringBuilder("我分享给你下面这些内容，接下来的问题基于它们：\n")
        val urls = ArrayList<String>()
        files.forEachIndexed { i, f ->
            sb.append("\n【${i + 1}. ${f.name}（${f.kind}，${f.note}）】\n")
            if (f.text != null) sb.append(f.text).append('\n')
            if (images && f.images.isNotEmpty()) {
                sb.append("（见第 ${urls.size + 1}").append(if (f.images.size > 1) "–${urls.size + f.images.size}" else "").append(" 张图片）\n")
                for (img in f.images) urls += "data:image/jpeg;base64," + android.util.Base64.encodeToString(img, android.util.Base64.NO_WRAP)
            }
            if (!f.usable) sb.append("（这个文件读不了：${f.note}）\n")
        }
        return parts(sb.toString(), urls)
    }

    /** The time goes last: everything before it stays the same between requests (prompt cache). */
    private fun systemPrompt(userText: String): String {
        val now = Date()
        val date = SimpleDateFormat("yyyy年M月d日 EEEE HH:mm", Locale.CHINA).format(now)
        return """
你是运行在用户手机（小米13，HyperOS 3，已 root）上的私人助手，用户通过文字或语音转文字向你下指令。

规则：
- 凡是手机能做的操作（闹钟、倒计时、打电话、导航、打开应用、放音乐、系统开关、音量、亮度、网页搜索），必须调用工具去做，不要只回答“好的”。一句话里有多个操作就在同一次回复里一起调用多个工具。
- 调用工具时 content 留空（执行结果会直接显示给用户）；只有用户同时还问了问题时，才在调用工具的同一次回复的 content 里直接回答那个问题。
- 闹钟用 24 小时制；“明早七点”就是 7:00；相对时间（“二十分钟后”）用倒计时。
- 工具返回失败或给出候选（candidates）时：能确定就重新调用一次，不能确定就简短地问用户。
- 做不到的事（例如在微信里发消息、点外卖）直接说明做不到，不要假装完成。
- 用户说“这个”“这段”“屏幕上”“这页”等指代他正看着的内容、而对话里还没有屏幕内容时，调用 read_screen 看屏幕。屏幕内容来自截图和本机文字识别：看不清、没识别到的就直说，绝不编造屏幕上没有的内容。
- 用户分享了文件时，文件内容在对话开头，回答以文件内容为准。
- 记忆：用户明确要你记住某件事时调用 remember（subject 用两三个字概括主题，content 写完整的原话），要你忘掉时调用 forget。不要主动记住用户没要求记的事。下面列出的是用户让你记住的事，相关时直接引用并说是什么时候记的。
- 回答问题用简体中文，简洁直接，一般不超过 150 字；总结文件或屏幕内容时可以长一些，但要分点、精炼。不要用 Markdown 标题和表格。
- 语音转写可能有同音错字，按最合理的意思理解。

${memoryBlock(userText)}
现在是 $date。
""".trim()
    }

    /** the user's memories: all of them while they fit, else the related ones and the newest */
    private fun memoryBlock(userText: String): String {
        val all = runCatching { memory.all() }.getOrDefault(emptyList())
        if (all.isEmpty()) return "用户让你记住的事：（暂无）\n"
        val chosen = if (all.size <= 40 && all.sumOf { it.content.length } <= 3000) all
        else (memory.related(userText, 15) + all.take(10)).distinctBy { it.id }
        val sb = StringBuilder("用户让你记住的事（新的在前）：\n")
        for (m in chosen) sb.append("- [").append(m.whenText()).append("] ").append(m.content.take(200)).append('\n')
        if (chosen.size < all.size) sb.append("（另有 ${all.size - chosen.size} 条与这次无关，未列出）\n")
        return sb.toString()
    }

    /** Pins files the user shared: sent with every request until replaced (or after a long idle). */
    fun pinFiles(files: List<Attachment>) = History.pinFiles(files)

    /** Pins the screen the user asked about. */
    fun pinScreen(sc: ScreenContent) = History.pinScreen(sc)

    val pinnedScreen: ScreenContent? get() = History.screen

    /** forgets the pinned screen if it was looked at more than [ms] ago */
    fun dropScreenOlderThan(ms: Long) = History.dropScreenOlderThan(ms)
    val hasFiles: Boolean get() = History.files != null

    /** The last few exchanges, so follow-ups like "再大一点" work; forgotten after a few idle minutes. */
    private object History {
        private const val MAX = 16
        private const val TTL_MS = 5 * 60_000L
        /** pinned context lives longer: reading a document takes a while */
        private const val PIN_TTL_MS = 15 * 60_000L
        private val messages = ArrayList<JSONObject>()
        private var lastUse = 0L
        var files: List<Attachment>? = null
            private set
        var screen: ScreenContent? = null
            private set

        @Synchronized fun snapshot(): List<JSONObject> = ArrayList(messages)

        @Synchronized fun pinFiles(f: List<Attachment>) {
            files = f
            lastUse = SystemClock.elapsedRealtime()
        }

        @Synchronized fun pinScreen(sc: ScreenContent) {
            screen = sc
            lastUse = SystemClock.elapsedRealtime()
        }

        /** Adds one complete exchange (user message … final assistant message). */
        @Synchronized fun commit(turn: List<JSONObject>) {
            // a screenshot returned by read_screen earlier is not sent again: only the newest stays
            if (turn.any { hasImage(it) }) for (m in messages) stripImages(m)
            messages += turn
            lastUse = SystemClock.elapsedRealtime()
            // trim from the front, always restarting at a user message so tool replies keep their calls
            if (messages.size > MAX) {
                var i = messages.size - MAX
                while (i < messages.size && messages[i].optString("role") != "user") i++
                if (i < messages.size) messages.subList(0, i).clear()
            }
        }

        private fun hasImage(m: JSONObject): Boolean {
            val c = m.opt("content") as? JSONArray ?: return false
            return (0 until c.length()).any { c.optJSONObject(it)?.optString("type") == "image_url" }
        }

        private fun stripImages(m: JSONObject) {
            val c = m.opt("content") as? JSONArray ?: return
            val kept = JSONArray()
            var dropped = false
            for (i in 0 until c.length()) {
                val part = c.optJSONObject(i) ?: continue
                if (part.optString("type") == "image_url") dropped = true else kept.put(part)
            }
            if (!dropped) return
            kept.put(JSONObject().put("type", "text").put("text", "（之前的截图已省略）"))
            m.put("content", kept)
        }

        @Synchronized fun dropScreenOlderThan(ms: Long) {
            val sc = screen ?: return
            if (SystemClock.elapsedRealtime() - sc.at > ms) screen = null
        }

        @Synchronized fun expireIfStale() {
            val idle = SystemClock.elapsedRealtime() - lastUse
            if (idle > TTL_MS) messages.clear()
            if (idle > PIN_TTL_MS) { files = null; screen = null }
        }

        @Synchronized fun clear() {
            messages.clear(); files = null; screen = null
        }
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
            .put(fn("read_screen", "看用户手机当前屏幕上的内容（截图 + 识别出的文字）。用户指代他正看着的东西（这个、这段、屏幕上、这页、这张图）且对话里还没有屏幕内容时调用", JSONObject()))
            .put(fn("remember", "记住用户明确要求记住的事（长期保存在手机上）", JSONObject()
                .put("subject", str("两三个字的主题，例如 车位、钥匙、生日"))
                .put("content", str("要记住的完整内容，尽量用用户的原话")), listOf("content")))
            .put(fn("forget", "忘掉之前记住的某件事", JSONObject().put("subject", str("要忘掉的主题")), listOf("subject")))
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
