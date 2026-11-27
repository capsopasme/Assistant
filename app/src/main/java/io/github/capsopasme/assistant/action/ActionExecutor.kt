package io.github.capsopasme.assistant.action

import android.Manifest
import android.app.SearchManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.AlarmClock
import android.provider.MediaStore
import io.github.capsopasme.assistant.system.ForeignPlayback
import io.github.capsopasme.assistant.system.SystemControl
import io.github.capsopasme.assistant.util.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Calendar

/** Asks the user for runtime permissions on behalf of an action; implemented by the panel. */
fun interface PermissionGate {
    suspend fun request(permissions: Array<String>): Boolean
}

/**
 * Carries out [Action]s. Must be used from an activity that is in the foreground: launching other
 * apps from the background is blocked on Android (and HyperOS' "后台弹出界面" on top of that).
 */
class ActionExecutor(
    private val context: Context,
    private val permissions: PermissionGate,
) {
    private val prefs = Prefs.get(context)
    private val system = SystemControl(context)

    /** [fromModel]: the model asked for this (looser app matching, failures go back to it). */
    suspend fun execute(action: Action, fromModel: Boolean = false): ActionResult = try {
        // The user takes over playback, or leaves the app under the panel: a player the panel
        // paused (see ForeignPlayback) must not start again behind their back.
        when (action) {
            is Action.Media -> ForeignPlayback.takeOver(context, resume = action.command == MediaCommand.PLAY)
            is Action.PlayMusic, is Action.OpenApp, is Action.Call,
            is Action.Navigate, is Action.WebSearch, is Action.ShowAlarms -> ForeignPlayback.takeOver(context, resume = false)
            else -> {}
        }
        when (action) {
            is Action.OpenApp -> openApp(action.name, fromModel)
            is Action.SetTimer -> setTimer(action)
            is Action.SetAlarm -> setAlarm(action)
            Action.ShowAlarms -> start(Intent(AlarmClock.ACTION_SHOW_ALARMS), "已打开闹钟列表")
            is Action.Call -> call(action.target)
            is Action.Navigate -> navigate(action)
            is Action.PlayMusic -> playMusic(action)
            is Action.Media -> system.media(action.command)
            is Action.Switch -> system.setSwitch(action.target, action.on)
            is Action.Volume -> system.setVolume(action.percent, action.delta)
            is Action.Brightness -> system.setBrightness(action.percent, action.delta)
            is Action.WebSearch -> webSearch(action.query)
        }
    } catch (e: SecurityException) {
        ActionResult(false, "系统拒绝了这个操作（${e.message?.take(80) ?: "权限不足"}）")
    } catch (e: Exception) {
        ActionResult(false, "执行出错：${e.message?.take(80) ?: e.javaClass.simpleName}")
    }

    private fun start(intent: Intent, message: String): ActionResult {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent)
            ActionResult(true, message, leftApp = true)
        } catch (e: ActivityNotFoundException) {
            ActionResult(false, "没有能处理这个操作的应用")
        }
    }

    private fun resolves(intent: Intent) =
        context.packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY) != null

    // --- apps -----------------------------------------------------------------------------------

    private suspend fun openApp(name: String, fromModel: Boolean): ActionResult {
        val found = withContext(Dispatchers.Default) {
            AppIndex.find(context, name, prefs.aliases, if (fromModel) 0.72 else 0.8)
        }
        val app = found.app
        if (app == null) {
            val names = found.candidates.map { it.label }
            return ActionResult(
                false,
                "没有找到叫「$name」的应用" + if (names.isNotEmpty()) "，相近的有：${names.joinToString("、")}" else "",
                candidates = names,
                fallbackToModel = !fromModel,
            )
        }
        return try {
            context.startActivity(AppIndex.launchIntent(app))
            ActionResult(true, "已打开${app.label}", leftApp = true)
        } catch (e: Exception) {
            context.packageManager.getLaunchIntentForPackage(app.pkg)?.let {
                it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                runCatching { context.startActivity(it) }.onSuccess { return ActionResult(true, "已打开${app.label}", leftApp = true) }
            }
            ActionResult(false, "打不开${app.label}（可能被冻结或停用了）")
        }
    }

    // --- clock ----------------------------------------------------------------------------------

    private fun setTimer(a: Action.SetTimer): ActionResult {
        val seconds = a.seconds.coerceIn(1, 24 * 3600 - 1)
        val text = formatDuration(seconds)
        val silent = Intent(AlarmClock.ACTION_SET_TIMER)
            .putExtra(AlarmClock.EXTRA_LENGTH, seconds)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            .apply { a.label?.let { putExtra(AlarmClock.EXTRA_MESSAGE, it) } }
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (resolves(silent)) {
            try {
                context.startActivity(silent)
                return ActionResult(true, "已开始 $text 倒计时" + (a.label?.let { "（$it）" } ?: ""))
            } catch (_: Exception) {
            }
        }
        // the clock app doesn't take silent timers: open it with the length filled in
        return start(Intent(AlarmClock.ACTION_SET_TIMER).putExtra(AlarmClock.EXTRA_LENGTH, seconds), "已在时钟里设置 $text 倒计时")
    }

    private fun setAlarm(a: Action.SetAlarm): ActionResult {
        val hour = a.hour.coerceIn(0, 23)
        val minute = a.minute.coerceIn(0, 59)
        val intent = Intent(AlarmClock.ACTION_SET_ALARM)
            .putExtra(AlarmClock.EXTRA_HOUR, hour)
            .putExtra(AlarmClock.EXTRA_MINUTES, minute)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        a.label?.takeIf { it.isNotBlank() }?.let { intent.putExtra(AlarmClock.EXTRA_MESSAGE, it) }
        a.days?.takeIf { it.isNotEmpty() }?.let { intent.putIntegerArrayListExtra(AlarmClock.EXTRA_DAYS, ArrayList(it.distinct().sorted())) }
        val time = "%02d:%02d".format(hour, minute)
        val repeat = describeDays(a.days)
        if (!resolves(intent)) return ActionResult(false, "没有可以设置闹钟的时钟应用")
        return try {
            context.startActivity(intent)
            ActionResult(true, "已设置 ${repeat}$time 的闹钟" + (a.label?.let { "（$it）" } ?: "") + whenText(hour, minute, a.days))
        } catch (e: Exception) {
            ActionResult(false, "设置闹钟失败")
        }
    }

    private fun whenText(hour: Int, minute: Int, days: List<Int>?): String {
        if (!days.isNullOrEmpty()) return ""
        val now = Calendar.getInstance()
        val mins = (hour * 60 + minute) - (now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE))
        val until = if (mins <= 0) mins + 1440 else mins
        return "，${if (mins <= 0) "明天" else "今天"}响，还有 ${formatDuration(until * 60)}"
    }

    private fun describeDays(days: List<Int>?): String {
        if (days.isNullOrEmpty()) return ""
        val set = days.toSet()
        return when (set) {
            (Calendar.SUNDAY..Calendar.SATURDAY).toSet() -> "每天 "
            (Calendar.MONDAY..Calendar.FRIDAY).toSet() -> "工作日 "
            setOf(Calendar.SATURDAY, Calendar.SUNDAY) -> "周末 "
            else -> "每周" + days.sorted().joinToString("、") { "日一二三四五六"[it - 1].toString() } + " "
        }
    }

    // --- calls ----------------------------------------------------------------------------------

    private suspend fun call(target: String): ActionResult {
        val number: String
        val who: String
        if (Contacts.isNumber(target)) {
            number = Contacts.normalizeNumber(target)
            who = number
        } else {
            if (!permissions.request(arrayOf(Manifest.permission.READ_CONTACTS))) {
                return ActionResult(false, "没有读取联系人的权限，无法按姓名拨号")
            }
            val people = withContext(Dispatchers.IO) { Contacts.load(context) }
            val found = withContext(Dispatchers.Default) { Contacts.find(people, target) }
            val person = found.person ?: return ActionResult(
                false,
                if (found.candidates.isEmpty()) "通讯录里没有找到「$target」"
                else "不确定是哪一位「$target」：${found.candidates.joinToString("、") { it.name }}",
                candidates = found.candidates.map { it.name },
            )
            number = person.number
            who = person.name
        }
        val uri = Uri.fromParts("tel", number, null)
        if (permissions.request(arrayOf(Manifest.permission.CALL_PHONE))) {
            try {
                context.startActivity(Intent(Intent.ACTION_CALL, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return ActionResult(true, "正在呼叫$who", leftApp = true)
            } catch (_: Exception) {
            }
        }
        return start(Intent(Intent.ACTION_DIAL, uri), "已打开拨号界面：$who")
    }

    // --- maps -----------------------------------------------------------------------------------

    private fun navigate(a: Action.Navigate): ActionResult {
        val dest = a.destination.trim()
        if (dest.isEmpty()) return ActionResult(false, "没有说要去哪里")
        val choice = prefs.mapApp
        if (choice == Prefs.MAP_AMAP && AppIndex.isInstalled(context, AMAP)) {
            val t = when (a.mode) {
                TravelMode.DRIVING -> 0
                TravelMode.TRANSIT -> 1
                TravelMode.WALKING -> 2
                TravelMode.BICYCLING -> 3
            }
            val uri = Uri.parse("amapuri://route/plan/?sourceApplication=assistant&dname=${Uri.encode(dest)}&dev=0&t=$t")
            val r = start(Intent(Intent.ACTION_VIEW, uri).setPackage(AMAP), "已在高德地图规划去「$dest」的路线")
            if (r.ok) return r
        }
        val geo = Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=${Uri.encode(dest)}"))
        if (choice == Prefs.MAP_COMAPS && AppIndex.isInstalled(context, COMAPS)) {
            val r = start(Intent(geo).setPackage(COMAPS), "已在 CoMaps 中搜索「$dest」，选中后点路线即可导航")
            if (r.ok) return r
        }
        return start(geo, "已打开地图搜索「$dest」")
    }

    // --- music ----------------------------------------------------------------------------------

    private fun playMusic(a: Action.PlayMusic): ActionResult {
        val gramophone = AppIndex.isInstalled(context, GRAMOPHONE)
        val query = a.query?.trim()?.takeIf { it.isNotEmpty() }
        val artist = a.artist?.trim()?.takeIf { it.isNotEmpty() }
        if (gramophone && (a.shuffle || query == null)) {
            // Gramophone's own shortcut action: shuffle everything, or what matches item_name
            val i = Intent("org.akanework.gramophone.action.SHUFFLE").setPackage(GRAMOPHONE)
                .putExtra("item_name", listOfNotNull(artist, query).joinToString(" "))
            return start(i, if (query == null && artist == null) "随机播放音乐" else "随机播放「${query ?: artist}」")
        }
        if (query == null) {
            return start(Intent(MediaStore.INTENT_ACTION_MUSIC_PLAYER), "已打开音乐")
        }
        val i = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH)
            .putExtra(SearchManager.QUERY, query)
            .putExtra(MediaStore.EXTRA_MEDIA_FOCUS, MediaStore.Audio.Media.ENTRY_CONTENT_TYPE)
            .putExtra(MediaStore.EXTRA_MEDIA_TITLE, query)
        artist?.let { i.putExtra(MediaStore.EXTRA_MEDIA_ARTIST, it) }
        if (gramophone) i.setPackage(GRAMOPHONE)
        return start(i, "正在播放「${artist?.let { "$it - " } ?: ""}$query」")
    }

    // --- web ------------------------------------------------------------------------------------

    private fun webSearch(query: String): ActionResult {
        val url = "https://www.bing.com/search?q=${Uri.encode(query)}"
        val view = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        if (AppIndex.isInstalled(context, FENNEC)) {
            val r = start(Intent(view).setPackage(FENNEC), "已在浏览器搜索「$query」")
            if (r.ok) return r
        }
        return start(view, "已在浏览器搜索「$query」")
    }

    companion object {
        const val GRAMOPHONE = "org.akanework.gramophone"
        const val COMAPS = "app.comaps.fdroid"
        const val AMAP = "com.autonavi.minimap"
        const val FENNEC = "org.mozilla.fennec_fdroid"

        fun formatDuration(seconds: Int): String {
            val h = seconds / 3600
            val m = seconds % 3600 / 60
            val s = seconds % 60
            return buildString {
                if (h > 0) append("${h}小时")
                if (m > 0) append("${m}分钟")
                if (s > 0 && h == 0) append("${s}秒")
                if (isEmpty()) append("0秒")
            }
        }
    }
}
