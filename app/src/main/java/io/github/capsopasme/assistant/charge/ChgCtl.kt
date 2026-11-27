package io.github.capsopasme.assistant.charge

import io.github.capsopasme.assistant.system.RootShell
import org.json.JSONObject

/**
 * Client of the ChgCtl KernelSU module (charge limit + bypass power supply).
 *
 * The module's daemon is the only process that ever writes the charging nodes (two writers have
 * been seen to dead-lock the vendor charger driver). This app therefore only
 *  - drops commands into the daemon's queue file (on / off / screen on|off / status),
 *  - changes settings through the module's own CLI (atomic, locked, hot-reloaded),
 *  - reads the state through the CLI.
 * The settings live in the module's config.conf, not in this app, so WebUI / CLI edits never
 * disagree with what the app shows.
 */
object ChgCtl {
    const val MODDIR = "/data/adb/modules/chgctl"
    const val CLI = "$MODDIR/bin/chgctl"
    const val CTL = "/data/local/tmp/chgctl/ctl"
    const val ICON_CONF = "/data/adb/chgctl/icon.conf"

    /** state/command protocol this app speaks (ChgCtl v1.3.0) */
    const val PROTO = 2

    /** Commands the daemon understands; nothing else is ever written to its queue. */
    enum class Cmd(val line: String) {
        BYPASS_ON("on"), BYPASS_OFF("off"), SCREEN_ON("screen on"), SCREEN_OFF("screen off"), STATUS("status")
    }

    data class Status(
        val version: String,
        val proto: Int,
        val running: Boolean,
        val soc: Int,
        val sysStatus: String,
        val currentMa: Int,
        val tempC: String,
        val usb: Boolean,
        val wireless: Boolean,
        val mode: String,
        val bypass: Boolean,
        val switch: String,
        val switchType: String,
        val calibrated: Boolean,
        val warn: String,
        val limit: Int,
        val resume: Int,
        val floor: Int,
        val offOnUnplug: Boolean,
        val persist: Boolean,
        val auto: String,
        val autoSoc: Int,
        val autoOffMin: Int,
        val autoOwned: Boolean,
        val override: Boolean,
        val screen: Boolean?,
        val iconCfg: Boolean,
        val iconOn: Boolean,
        val iconOk: Boolean,
        val vibrate: Boolean,
        val nightCharging: Boolean,
    ) {
        val plugged get() = usb || wireless
        val supported get() = proto >= PROTO

        fun toSnapshot() = ChargeState.Snapshot(
            proto = proto, running = running, mode = if (running) mode else "stopped", bypass = bypass,
            plugged = plugged, auto = auto, autoOwned = autoOwned, override = override,
            screen = screen, warn = warn, icon = iconOn, iconOk = iconOk,
        )

        companion object {
            fun parse(json: String): Status? = runCatching {
                val o = JSONObject(json)
                fun b(k: String) = o.optInt(k, 0) != 0
                Status(
                    version = o.optString("version"),
                    proto = o.optInt("proto", 0),
                    running = b("running"),
                    soc = o.optInt("soc", -1),
                    sysStatus = o.optString("status"),
                    currentMa = o.optInt("current_ma", 0),
                    tempC = o.optString("temp"),
                    usb = b("usb"),
                    wireless = b("wls"),
                    mode = o.optString("mode", "stopped").ifEmpty { "stopped" },
                    bypass = b("bypass"),
                    switch = o.optString("switch"),
                    switchType = o.optString("switch_type"),
                    calibrated = b("calibrated"),
                    warn = o.optString("warn"),
                    limit = o.optInt("limit", 100),
                    resume = o.optInt("resume", 95),
                    floor = o.optInt("floor", 20),
                    offOnUnplug = b("off_on_unplug"),
                    persist = b("persist"),
                    auto = o.optString("auto", "off").ifEmpty { "off" },
                    autoSoc = o.optInt("auto_soc", 50),
                    autoOffMin = o.optInt("auto_off_min", 10),
                    autoOwned = b("auto_owned"),
                    override = b("override"),
                    screen = when (o.optString("screen")) { "1" -> true; "0" -> false; else -> null },
                    iconCfg = b("icon_cfg"),
                    iconOn = b("icon"),
                    iconOk = b("icon_ok"),
                    vibrate = b("vibrate"),
                    nightCharging = o.optString("night_charging") == "1",
                )
            }.getOrNull()
        }
    }

    /** Whether the module is installed (and not marked for removal). Needs root. */
    suspend fun installed(): Boolean =
        RootShell.run("[ -x $CLI ] && [ ! -f $MODDIR/remove ] && [ ! -f $MODDIR/disable ]", 6_000).ok

    /** null = no root, module missing, or unreadable. */
    suspend fun status(): Status? {
        val r = RootShell.run("[ -x $CLI ] && $CLI status -j", 8_000)
        if (!r.ok) return null
        val line = r.out.lineSequence().map { it.trim() }.lastOrNull { it.startsWith("{") } ?: return null
        return Status.parse(line)?.also { ChargeState.fromStatus(it) }
    }

    /** Queues commands for the daemon (one write, several lines). Fails if the daemon is not running. */
    suspend fun send(vararg cmds: Cmd): Boolean {
        if (cmds.isEmpty()) return true
        val lines = cmds.joinToString(" ") { RootShell.q(it.line) }
        val pidf = "/dev/chgctl/daemon.pid"
        val r = RootShell.run(
            "read -r p < $pidf 2>/dev/null && [ -n \"\$p\" ] && kill -0 \"\$p\" 2>/dev/null && " +
                "[ -d ${CTL.substringBeforeLast('/')} ] && printf '%s\\n' $lines >> $CTL",
            6_000,
        )
        return r.ok
    }

    /** chgctl set KEY VALUE. Returns the CLI's message. */
    suspend fun set(key: String, value: String): RootShell.Result {
        require(key.all { it.isUpperCase() || it == '_' }) { "bad key" }
        return RootShell.run("$CLI set $key ${RootShell.q(value)}", 10_000)
    }

    /** limit = 100 removes the charge limit. */
    suspend fun limit(limit: Int, resume: Int): RootShell.Result =
        if (limit >= 100) RootShell.run("$CLI limit off", 10_000)
        else RootShell.run("$CLI limit $limit ${resume.coerceIn(10, limit - 1)}", 10_000)

    suspend fun log(lines: Int = 120): String = RootShell.run("$CLI log $lines", 8_000).out

    suspend fun calibrate(): RootShell.Result = RootShell.run("$CLI calibrate 2>&1", 240_000)

    suspend fun restart(): RootShell.Result = RootShell.run("$CLI restart 2>&1", 20_000)

    /** Human text for a daemon mode. */
    fun modeText(mode: String): String = when (mode) {
        "charging" -> "充电中"
        "paused" -> "已到上限·停充"
        "bypass" -> "旁路供电中"
        "floor" -> "旁路·先充到保底电量"
        "unplugged" -> "未插电"
        "settling" -> "插电·等待握手稳定"
        "suspended" -> "模块已停用"
        "stopped" -> "守护进程未运行"
        "init" -> "启动中"
        else -> mode
    }
}
