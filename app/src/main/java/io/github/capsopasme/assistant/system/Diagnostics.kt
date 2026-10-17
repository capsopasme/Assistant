package io.github.capsopasme.assistant.system

import android.content.Context
import io.github.capsopasme.assistant.BuildConfig
import io.github.capsopasme.assistant.util.Marks
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Collects what's needed to tell why a power-key press didn't open the panel: the assistant
 * settings, whether the system has our service bound, whether our process is frozen, system apps
 * that were disabled or uninstalled, and the relevant recent log lines. Text only, for pasting.
 */
object Diagnostics {

    suspend fun collect(context: Context): String {
        val pkg = context.packageName
        val fmt = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US)
        fun t(ms: Long) = if (ms == 0L) "从未" else fmt.format(Date(ms))
        val out = StringBuilder()
        out.append("助手 ").append(BuildConfig.VERSION_NAME).append('\n')
        out.append("最近一次收到长按：").append(t(Marks.read(context, Marks.SHOW)))
            .append("；面板显示：").append(t(Marks.read(context, Marks.PANEL))).append('\n')
        out.append("现在：").append(fmt.format(Date())).append("\n\n")

        if (!RootShell.available()) {
            out.append("（没有 root，无法收集更多信息）\n")
            return out.toString()
        }
        val script = """
            echo "== 系统"; getprop ro.mi.os.version.incremental; getprop ro.build.version.incremental
            echo "== 设置"
            for k in long_press_power_key; do echo "system/${'$'}k=${'$'}(settings get system ${'$'}k)"; done
            for k in voice_interaction_service assistant voice_recognition_service assist_structure_enabled; do echo "secure/${'$'}k=${'$'}(settings get secure ${'$'}k)"; done
            echo "global/power_button_long_press=${'$'}(settings get global power_button_long_press)"
            echo "role=${'$'}(cmd role get-role-holders android.app.role.ASSISTANT)"
            echo "== 权限 / 省电"
            for op in 10021 10020 10008 RUN_ANY_IN_BACKGROUND SYSTEM_ALERT_WINDOW; do echo "${'$'}op: ${'$'}(appops get $pkg ${'$'}op 2>&1 | head -1)"; done
            echo "deviceidle: ${'$'}(dumpsys deviceidle whitelist | grep -c $pkg)"
            echo "standby: ${'$'}(am get-standby-bucket $pkg)"
            echo "== 进程"
            for p in ${'$'}(pidof $pkg); do
              echo "pid ${'$'}p oom_score_adj=${'$'}(cat /proc/${'$'}p/oom_score_adj)"
              for f in /sys/fs/cgroup/uid_*/pid_${'$'}p/cgroup.freeze; do [ -f "${'$'}f" ] && echo "  cgroup.freeze=${'$'}(cat ${'$'}f)"; done
              grep -h "^State" /proc/${'$'}p/status
            done
            dumpsys activity processes $pkg 2>/dev/null | grep -E "oom:|procState|frozen|isFreezeExempt|mFrozen" | head -8
            dumpsys activity services $pkg 2>/dev/null | grep -E "ServiceRecord|isForeground" | head -6
            echo "== 语音交互服务"
            dumpsys voiceinteraction 2>/dev/null | grep -E -i "mComponent|mBound|mValid|mDisabledShowContext|mShown|ActiveSession|mSession|Interactor" | head -20
            echo "== 停用 / 卸载的小米系统应用"
            pm list packages -d 2>/dev/null | grep -i -E "miui|xiaomi|mi\.|voiceassist|mibrain|powerkeeper|securitycenter|millet" | sed 's/package://'
            pm list packages -u 2>/dev/null | sed 's/package://' | sort > /data/local/tmp/.assist_all
            pm list packages 2>/dev/null | sed 's/package://' | sort > /data/local/tmp/.assist_inst
            echo "-- 已卸载："; comm -23 /data/local/tmp/.assist_all /data/local/tmp/.assist_inst | grep -i -E "miui|xiaomi|voiceassist|mibrain|powerkeeper|securitycenter|millet|systemui|input"
            rm -f /data/local/tmp/.assist_all /data/local/tmp/.assist_inst
            echo "== 日志"
            logcat -d -b main,system,events -t 4000 2>/dev/null | grep -i -E "VoiceInteract|capsopasme|assistant|launch_google|long_press|longPress|PowerKey|ShortCut|voiceassist|Greeze|Millet|freez" | grep -v -i "chatty" | tail -120
        """.trimIndent()
        val r = RootShell.run(script, 20_000)
        out.append(r.out.ifBlank { "（命令没有输出，code=${r.code}）" })
        return out.toString()
    }
}
