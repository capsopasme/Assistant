package io.github.capsopasme.assistant.charge

import android.graphics.drawable.Icon
import android.os.SystemClock
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import io.github.capsopasme.assistant.R
import io.github.capsopasme.assistant.util.AppScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Quick-settings tile for ChgCtl's bypass power supply.
 *
 * Passive tile (bound while the panel is open), drawn from [ChargeState], which the daemon keeps
 * current with its pushes. Only when nothing is known (or it is old) does it read the state
 * through root. A tap sends an explicit "on"/"off" (never "toggle": repeated taps can't cancel
 * each other out), shows the expected state at once, and the daemon's push confirms it (or puts
 * it back, e.g. when the daemon refuses because no true-bypass switch is calibrated).
 */
class BypassTileService : TileService() {
    private var scope: CoroutineScope? = null
    private var watch: Job? = null

    override fun onStartListening() {
        super.onStartListening()
        val sc = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).also { scope = it }
        watch = sc.launch { ChargeState.flow.collect { render(it) } }
        if (ChargeState.current == null || ChargeState.age > STALE_MS) {
            sc.launch { withContext(Dispatchers.IO) { runCatching { ChgCtl.status() } } ; render(ChargeState.current, read = true) }
        }
    }

    override fun onStopListening() {
        watch?.cancel()
        scope?.cancel()
        scope = null
        super.onStopListening()
    }

    override fun onClick() {
        super.onClick()
        val s = ChargeState.current
        if (s == null || !s.running || s.proto < ChgCtl.PROTO) {
            // unknown or unavailable: read again; a tap never acts on a guess
            AppScope.launch { withContext(Dispatchers.IO) { runCatching { ChgCtl.status() } }; render(ChargeState.current, read = true) }
            return
        }
        val target = !s.bypass
        val t0 = SystemClock.elapsedRealtime()
        ChargeState.assumeBypass(target)
        // AppScope: the command must go out even if the panel closes and this service is unbound
        AppScope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching { ChgCtl.send(if (target) ChgCtl.Cmd.BYPASS_ON else ChgCtl.Cmd.BYPASS_OFF) }.getOrDefault(false)
            }
            // no push since the tap (the daemon refused, or the push was lost): read the real state
            delay(if (ok) 2_500 else 0)
            if (!ok || ChargeState.updatedAt < t0 || ChargeState.current?.bypass != target) {
                withContext(Dispatchers.IO) { runCatching { ChgCtl.status() } }
            }
        }
    }

    private fun render(s: ChargeState.Snapshot?, read: Boolean = false) {
        val tile = qsTile ?: return
        tile.icon = Icon.createWithResource(this, R.drawable.ic_tile_bypass)
        tile.label = getString(R.string.tile_bypass)
        val sub: String
        when {
            s == null -> {
                tile.state = Tile.STATE_UNAVAILABLE
                sub = if (read) "未安装 ChgCtl 或没有 root" else "读取中…"
            }
            !s.running -> { tile.state = Tile.STATE_UNAVAILABLE; sub = "守护进程未运行" }
            s.proto < ChgCtl.PROTO -> { tile.state = Tile.STATE_UNAVAILABLE; sub = "需要 ChgCtl ≥ v1.3.0" }
            else -> {
                tile.state = if (s.bypass) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
                val auto = if (s.autoOwned) "（自动）" else ""
                sub = when {
                    s.bypass && !s.plugged -> "待命·插电后生效"
                    s.bypass && s.mode == "floor" -> "先充到保底电量"
                    s.bypass && s.mode == "settling" -> "插电·稍候"
                    s.bypass -> "旁路中$auto"
                    s.mode == "paused" -> "已到上限·停充"
                    s.mode == "charging" -> "充电中"
                    s.mode == "settling" -> "插电·稍候"
                    s.mode == "suspended" -> "模块已停用"
                    else -> "关"
                }
            }
        }
        tile.subtitle = sub
        tile.stateDescription = sub
        tile.updateTile()
    }

    companion object {
        private const val STALE_MS = 5 * 60_000L
    }
}
