package io.github.capsopasme.assistant.action

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.icu.text.Transliterator
import io.github.capsopasme.assistant.parse.Fuzzy

/** Chinese → toneless pinyin through the ICU data built into Android, for homophone-tolerant matching. */
object Pinyin {
    private val hanzi = Regex("[\\u3400-\\u9fff]")
    private var transliterator: Transliterator? = null

    @Synchronized
    fun of(text: String): String? {
        if (!hanzi.containsMatchIn(text)) return null
        val t = transliterator ?: runCatching { Transliterator.getInstance("Han-Latin; Latin-ASCII; Lower") }.getOrNull()
            ?.also { transliterator = it } ?: return null
        return runCatching { t.transliterate(text).replace(" ", "") }.getOrNull()
    }
}

/**
 * Launchable apps, matched by label, pinyin and aliases. Rebuilt only when packages actually
 * changed (PackageManager's change sequence number), so a lookup is normally just a list scan.
 */
object AppIndex {

    data class App(val label: String, val pkg: String, val activity: String) {
        val norm = Fuzzy.normalize(label)
        val pinyin: String? = Pinyin.of(norm)
    }

    data class Found(val app: App?, val candidates: List<App>)

    /** Spoken names → packages in order of preference; the first one installed wins. */
    private val BUILTIN_ALIASES: Map<String, List<String>> = mapOf(
        "设置" to listOf("com.android.settings"),
        "系统设置" to listOf("com.android.settings"),
        "电话" to listOf("com.android.contacts", "com.google.android.dialer"),
        "拨号" to listOf("com.android.contacts", "com.google.android.dialer"),
        "联系人" to listOf("com.android.contacts"),
        "通讯录" to listOf("com.android.contacts"),
        "短信" to listOf("com.android.mms", "com.google.android.apps.messaging"),
        "信息" to listOf("com.android.mms", "com.google.android.apps.messaging"),
        "相机" to listOf("app.grapheneos.camera", "com.android.camera"),
        "照相机" to listOf("app.grapheneos.camera", "com.android.camera"),
        "相册" to listOf("deckers.thibault.aves.libre", "deckers.thibault.aves", "org.fossify.gallery", "com.miui.gallery"),
        "图库" to listOf("deckers.thibault.aves.libre", "deckers.thibault.aves", "org.fossify.gallery", "com.miui.gallery"),
        "照片" to listOf("deckers.thibault.aves.libre", "deckers.thibault.aves", "org.fossify.gallery", "com.miui.gallery"),
        "音乐" to listOf("org.akanework.gramophone", "com.miui.player"),
        "留声机" to listOf("org.akanework.gramophone"),
        "播放器" to listOf("xyz.mpv.rex", "dev.anilbeesetti.nextplayer"),
        "视频播放器" to listOf("xyz.mpv.rex", "dev.anilbeesetti.nextplayer"),
        "地图" to listOf("app.comaps.fdroid", "com.autonavi.minimap"),
        "高德" to listOf("com.autonavi.minimap"),
        "浏览器" to listOf("org.mozilla.fennec_fdroid", "org.cromite.cromite", "com.android.browser"),
        "火狐" to listOf("org.mozilla.fennec_fdroid"),
        "计算器" to listOf("com.sadellie.unitto", "com.miui.calculator"),
        "单位换算" to listOf("com.sadellie.unitto"),
        "日历" to listOf("com.android.calendar"),
        "时钟" to listOf("com.android.deskclock"),
        "文件" to listOf("me.zhanghai.android.files", "com.android.fileexplorer"),
        "文件管理" to listOf("me.zhanghai.android.files", "com.android.fileexplorer"),
        "文件管理器" to listOf("me.zhanghai.android.files", "com.android.fileexplorer"),
        "笔记" to listOf("com.streetwriters.notesnook", "com.miui.notes"),
        "便签" to listOf("com.streetwriters.notesnook", "com.miui.notes"),
        "录音" to listOf("org.fossify.voicerecorder", "com.android.soundrecorder"),
        "录音机" to listOf("org.fossify.voicerecorder", "com.android.soundrecorder"),
        "天气" to listOf("org.breezyweather", "com.miui.weather2"),
        "b站" to listOf("com.example.piliplus", "tv.danmaku.bili"),
        "哔哩哔哩" to listOf("com.example.piliplus", "tv.danmaku.bili"),
        "bilibili" to listOf("com.example.piliplus", "tv.danmaku.bili"),
        "微信" to listOf("com.tencent.mm"),
        "支付宝" to listOf("com.eg.android.AlipayGphone"),
        "12306" to listOf("com.MobileTicket"),
        "铁路12306" to listOf("com.MobileTicket"),
        "火车票" to listOf("com.MobileTicket"),
        "闲鱼" to listOf("com.taobao.idlefish"),
        "小红书" to listOf("com.xingin.xhs"),
        "拼多多" to listOf("com.xunmeng.pinduoduo"),
        "知乎" to listOf("com.github.zly2006.zhplus.lite", "com.zhihu.android"),
        "电报" to listOf("tw.nekomimi.nekogram", "org.telegram.messenger"),
        "telegram" to listOf("tw.nekomimi.nekogram", "org.telegram.messenger"),
        "纸飞机" to listOf("tw.nekomimi.nekogram", "org.telegram.messenger"),
        "终端" to listOf("com.termux"),
        "阅读" to listOf("com.anxcye.anx_reader"),
        "安读" to listOf("com.anxcye.anx_reader"),
        "读书" to listOf("com.anxcye.anx_reader"),
        "电子书" to listOf("com.anxcye.anx_reader"),
        "密码" to listOf("keepass2android.keepass2android_nonet"),
        "密码本" to listOf("keepass2android.keepass2android_nonet"),
        "验证器" to listOf("com.beemdevelopment.aegis"),
        "二步验证" to listOf("com.beemdevelopment.aegis"),
        "雪球" to listOf("com.xueqiu.android"),
        "boss" to listOf("com.hpbr.bosszhipin"),
        "boss直聘" to listOf("com.hpbr.bosszhipin"),
        "工商银行" to listOf("com.icbc"),
        "工行" to listOf("com.icbc"),
        "录屏" to listOf("com.miui.screenrecorder"),
        "手机管家" to listOf("com.miui.securitycenter"),
        "安全中心" to listOf("com.miui.securitycenter"),
        "应用商店" to listOf("com.aurora.store.nightly", "com.xiaomi.market"),
        "指南针" to listOf("com.mubarak.mbcompass", "com.miui.compass"),
        "扫描" to listOf("org.fairscan.app"),
        "扫描仪" to listOf("org.fairscan.app"),
        "邮箱" to listOf("ch.protonmail.android", "de.tutao.tutanota"),
        "邮件" to listOf("ch.protonmail.android", "de.tutao.tutanota"),
        "遥控器" to listOf("com.duokan.phone.remotecontroller"),
        "万能遥控" to listOf("com.duokan.phone.remotecontroller"),
        "涂鸦跳跃" to listOf("io.github.capsopasme.tuya"),
        "代理" to listOf("moe.nb4a"),
        "vpn" to listOf("moe.nb4a"),
        "冻结" to listOf("com.aistra.hail"),
        "传文件" to listOf("org.localsend.localsend_app"),
        "物理工坊" to listOf("de.rwth_aachen.phyphox"),
        "解压" to listOf("com.wirelessalien.zipxtract"),
        "图片工具" to listOf("ru.tech.imageresizershrinker"),
        "pdf" to listOf("com.gitlab.mudlej.MjPdfReader"),
        "下载器" to listOf("com.junkfood.seal"),
        "直播" to listOf("com.mystyle.purelive"),
    )

    private var apps: List<App> = emptyList()
    private var sequence = 0
    private var built = false

    @Synchronized
    fun apps(context: Context): List<App> {
        val pm = context.packageManager
        val changed = pm.getChangedPackages(sequence)
        if (!built || changed != null) {
            changed?.let { sequence = it.sequenceNumber }
            val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            apps = pm.queryIntentActivities(intent, PackageManager.MATCH_ALL)
                .filter { it.activityInfo.packageName != context.packageName }
                .map { App(it.loadLabel(pm).toString().trim(), it.activityInfo.packageName, it.activityInfo.name) }
                .distinctBy { it.pkg + "/" + it.activity }
            built = true
        }
        return apps
    }

    /** "别名=包名" or "别名=应用名", one per line, from the settings. */
    private fun userAliases(text: String): Map<String, String> =
        text.lines().mapNotNull { line ->
            val i = line.indexOfAny(charArrayOf('=', '＝'))
            if (i <= 0) null else Fuzzy.normalize(line.substring(0, i)) to line.substring(i + 1).trim()
        }.filter { it.first.isNotEmpty() && it.second.isNotEmpty() }.toMap()

    fun find(context: Context, spoken: String, userAliasText: String, threshold: Double): Found {
        val all = apps(context)
        if (all.isEmpty()) return Found(null, emptyList())
        val query = Fuzzy.normalize(spoken)
        if (query.isEmpty()) return Found(null, emptyList())
        val byPkg = all.groupBy { it.pkg }

        // 1) exact aliases (user's first)
        userAliases(userAliasText)[query]?.let { target ->
            byPkg[target]?.firstOrNull()?.let { return Found(it, emptyList()) }
            val m = Fuzzy.rank(target, Pinyin.of(target), all) { listOf(it.norm to it.pinyin) }.firstOrNull()
            if (m != null && m.score >= 0.9) return Found(m.item, emptyList())
        }
        BUILTIN_ALIASES[query]?.firstNotNullOfOrNull { byPkg[it]?.firstOrNull() }?.let { return Found(it, emptyList()) }

        // 2) fuzzy over labels and over the aliases of installed apps
        val aliasForms = BUILTIN_ALIASES.entries.flatMap { (alias, pkgs) -> pkgs.map { it to alias } }
            .groupBy({ it.first }, { it.second })
        val ranked = Fuzzy.rank(spoken, Pinyin.of(query), all) { app ->
            buildList {
                add(app.norm to app.pinyin)
                aliasForms[app.pkg]?.forEach { a -> add(a to Pinyin.of(a)) }
            }
        }
        val best = ranked.firstOrNull()
        val candidates = ranked.take(5).map { it.item }
        return if (best != null && best.score >= threshold) Found(best.item, candidates) else Found(null, candidates)
    }

    fun launchIntent(app: App): Intent =
        Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            .setComponent(ComponentName(app.pkg, app.activity))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)

    fun isInstalled(context: Context, pkg: String): Boolean =
        runCatching { context.packageManager.getPackageInfo(pkg, 0); true }.getOrDefault(false)
}
