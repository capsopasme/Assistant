package io.github.capsopasme.assistant.charge

import android.content.Context
import android.os.Build
import android.util.Log
import io.github.capsopasme.assistant.system.RootShell
import io.github.capsopasme.assistant.util.Prefs
import kotlinx.coroutines.delay

/**
 * Produces ChgCtl's icon.conf: everything the daemon needs to show / remove the bypass icon with a
 * plain `service call statusbar`, resolved by name on the system actually running:
 *  - the drawable: SystemUI's stat_sys_handle_battery_charging_5, redrawn as a plug by the
 *    sbicons_hyperos overlay (its resource id changes whenever SystemUI is rebuilt);
 *  - the binder transaction codes of setIcon / setIconVisibility / removeIcon, read from the
 *    framework's own IStatusBarService stub by [io.github.capsopasme.assistant.root.IconTool]
 *    running as root through app_process.
 * The file carries the system build; the daemon ignores a file from another build, and this is
 * re-run on boot and whenever SystemUI is updated, so a stale id is never shown.
 */
object IconSetup {
    private const val TAG = "IconSetup"
    const val SLOT = "chgctl_bypass"
    private const val TEST_SLOT = "chgctl_selftest"
    private const val SYSUI = "com.android.systemui"
    private const val DRAWABLE = "stat_sys_handle_battery_charging_5"
    private const val OVERLAY = "com.capsopasme.sbicons.overlay"
    private const val TOOL = "io.github.capsopasme.assistant.root.IconTool"

    data class Result(val ok: Boolean, val report: String)

    private fun resId(ctx: Context): Int = runCatching {
        ctx.packageManager.getResourcesForApplication(SYSUI).getIdentifier(DRAWABLE, "drawable", SYSUI)
    }.getOrDefault(0)

    private suspend fun tool(ctx: Context, vararg args: String): Map<String, String> {
        val apk = ctx.applicationInfo.sourceDir
        val cmd = "CLASSPATH=${RootShell.q(apk)} /system/bin/app_process /system/bin $TOOL " +
            args.joinToString(" ") { RootShell.q(it) }
        val r = RootShell.run(cmd, 25_000)
        val m = HashMap<String, String>()
        for (line in r.out.lineSequence()) {
            val i = line.indexOf('=')
            if (i > 0) m[line.substring(0, i).trim()] = line.substring(i + 1).trim()
        }
        if (!r.ok && "error" !in m) m["error"] = r.out.lines().lastOrNull { it.isNotBlank() } ?: "退出码 ${r.code}"
        return m
    }

    private suspend fun overlayEnabled(): Boolean? {
        val r = RootShell.run("cmd overlay list $SYSUI 2>/dev/null | grep -F $OVERLAY", 8_000)
        if (r.code == -1) return null
        return r.out.contains("[x]")
    }

    /** Resolves and writes icon.conf. [quiet] = background re-run (boot / SystemUI update). */
    suspend fun generate(ctx: Context, quiet: Boolean = false): Result {
        val lines = StringBuilder()
        fun fail(msg: String): Result {
            Log.w(TAG, msg)
            return Result(false, (lines.toString() + "✗ " + msg).trim())
        }
        if (!RootShell.available()) return fail("没有 root 权限")
        if (!ChgCtl.installed()) return fail("没有安装 ChgCtl 模块（或已停用）")

        val id = resId(ctx)
        if (id == 0) return fail("系统界面里找不到图标资源 $DRAWABLE")
        lines.append("✓ 图标资源 $DRAWABLE = 0x${Integer.toHexString(id)}\n")

        when (overlayEnabled()) {
            true -> lines.append("✓ 状态栏图标覆盖包（sbicons_hyperos）已启用\n")
            false -> return fail("状态栏图标覆盖包（sbicons_hyperos）没有启用：图标会显示成系统原来的样子。请安装/启用该模块并重启")
            null -> lines.append("? 无法确认覆盖包状态\n")
        }

        val t = tool(ctx, "resolve")
        val set = t["set_icon"]?.toIntOrNull()
        val vis = t["set_vis"]?.toIntOrNull()
        val rm = t["remove_icon"]?.toIntOrNull()
        if (set == null || rm == null) return fail("读取状态栏服务失败：${t["error"] ?: "无输出"}")
        lines.append("✓ 状态栏服务：setIcon=$set setIconVisibility=${vis ?: "-"} removeIcon=$rm\n")

        val conf = buildString {
            append("# 由 Assistant 生成，请勿手改；系统或系统界面更新后会自动重新生成\n")
            append("build=").append(Build.VERSION.INCREMENTAL).append('\n')
            append("slot=").append(SLOT).append('\n')
            append("pkg=").append(SYSUI).append('\n')
            append("id=").append(id).append('\n')
            append("set_icon=").append(set).append('\n')
            if (vis != null) append("set_vis=").append(vis).append('\n')
            append("remove_icon=").append(rm).append('\n')
            append("desc=旁路供电\n")
        }
        val path = ChgCtl.ICON_CONF
        val w = RootShell.run(
            // unchanged content is left alone: a rewrite makes the daemon reload (icon blinks once)
            "printf '%s' ${RootShell.q(conf)} > $path.tmp && chmod 0600 $path.tmp && " +
                "{ if cmp -s $path.tmp $path; then rm -f $path.tmp; else mv -f $path.tmp $path; fi; }",
            8_000,
        )
        if (!w.ok) return fail("写入 $path 失败：${w.out}")
        Prefs.get(ctx).chargeIconSetup = true

        // the daemon reloads it through inotify; check that it accepted the file
        delay(1_500)
        val st = ChgCtl.status()
        when {
            st == null -> lines.append("? 读取模块状态失败\n")
            st.proto < ChgCtl.PROTO -> return fail("ChgCtl 版本过旧（${st.version}），需要 v1.3.0 以上")
            !st.iconCfg -> lines.append("! 已生成，但模块里「状态栏旁路图标」是关闭的\n")
            st.iconOk -> lines.append("✓ ChgCtl 已载入图标配置（插着电且电池保持不充时显示）\n")
            else -> return fail("ChgCtl 没有接受图标配置，见模块日志")
        }
        if (!quiet) Log.i(TAG, lines.toString())
        return Result(true, lines.toString().trim())
    }

    /** Re-generates after boot / a SystemUI update, if the user set the icon up once. */
    suspend fun refreshIfSetUp(ctx: Context, why: String) {
        if (!Prefs.get(ctx).chargeIconSetup) return
        val r = runCatching { generate(ctx, quiet = true) }.getOrElse { Result(false, it.message.orEmpty()) }
        Log.i(TAG, "icon.conf ($why): ${if (r.ok) "ok" else r.report}")
    }

    /**
     * Shows a test icon (separate slot) for a few seconds through the framework's own stub,
     * then checks that the status bar service holds it, and removes it.
     */
    suspend fun selfTest(ctx: Context, showMs: Long = 4_000): Result {
        if (!RootShell.available()) return Result(false, "✗ 没有 root 权限")
        val id = resId(ctx)
        if (id == 0) return Result(false, "✗ 系统界面里找不到图标资源 $DRAWABLE")
        val show = tool(ctx, "show", TEST_SLOT, SYSUI, id.toString(), "旁路图标自检")
        if (show["ok"] != "1") return Result(false, "✗ 设置图标失败：${show["error"] ?: "无输出"}")
        val listed = RootShell.run("dumpsys statusbar 2>/dev/null | grep -c $TEST_SLOT", 10_000).out.trim().toIntOrNull() ?: 0
        delay(showMs)
        val hide = tool(ctx, "hide", TEST_SLOT)
        val sb = StringBuilder()
        sb.append("✓ 已用系统接口设置测试图标（${showMs / 1000} 秒）\n")
        sb.append(if (listed > 0) "✓ 状态栏服务里能查到它\n" else "? 状态栏服务的转储里没查到它（不一定是问题）\n")
        sb.append(if (hide["ok"] == "1") "✓ 已移除\n" else "✗ 移除失败：${hide["error"]}\n")
        sb.append("\n刚才状态栏里出现插头图标了吗？出现了说明 HyperOS 接受这种方式显示图标。")
        return Result(show["ok"] == "1" && hide["ok"] == "1", sb.toString())
    }
}
