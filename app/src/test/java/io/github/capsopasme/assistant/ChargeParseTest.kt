package io.github.capsopasme.assistant

import io.github.capsopasme.assistant.charge.ChargeState
import io.github.capsopasme.assistant.charge.ChgCtl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChargeParseTest {

    @Test
    fun snapshotFromDaemonStatus() {
        val text = """
            proto=2
            version=v1.3.0
            mode=bypass
            bypass=1
            applied=stop
            switch=smart
            warn=
            plugged=1
            auto=screen
            auto_soc=50
            auto_owned=1
            override=0
            screen=1
            icon=1
            icon_ok=1
            pid=1234
        """.trimIndent()
        val s = ChargeState.Snapshot.parse(text)
        assertEquals(2, s.proto)
        assertTrue(s.running)
        assertEquals("bypass", s.mode)
        assertTrue(s.bypass && s.plugged && s.autoOwned && s.icon && s.iconOk)
        assertFalse(s.override)
        assertEquals(true, s.screen)
        assertEquals("screen", s.auto)
    }

    @Test
    fun snapshotOldOrStopped() {
        val s = ChargeState.Snapshot.parse("mode=stopped\nbypass=0\n")
        assertFalse(s.running)
        assertEquals(0, s.proto)
        assertNull(s.screen)
        assertEquals("off", s.auto)
    }

    @Test
    fun statusJson() {
        // the exact shape of `chgctl status -j` (v1.3.0)
        val json = """{"version":"v1.3.0","running":1,"pid":"99","watch":"native","soc":72,"status":"Charging",""" +
            """"current_ma":-3,"voltage_mv":4100,"temp":31.5,"usb":1,"usb_mv":9000,"wls":0,"mode":"paused","bypass":0,""" +
            """"applied":"stop","switch":"smart","switch_kind":"smart","switch_type":"idle","calibrated":1,"warn":"",""" +
            """"limit":80,"resume":75,"floor":20,"off_on_unplug":1,"persist":0,"auto":"plug","auto_soc":60,"auto_off_min":10,""" +
            """"auto_owned":0,"override":1,"screen":"","plugged":1,"unplug_debounce":3,"plug_settle":5,"icon_cfg":1,""" +
            """"icon":1,"icon_ok":1,"notify":1,"proto":2,"vibrate":1,"update_desc":1,"log_level":1,"switch_cfg":"auto","night_charging":""}"""
        val s = ChgCtl.Status.parse(json)
        assertNotNull(s)
        s!!
        assertTrue(s.supported && s.running && s.plugged && s.override && s.iconOn)
        assertEquals(80, s.limit)
        assertEquals("plug", s.auto)
        assertEquals(60, s.autoSoc)
        assertEquals("31.5", s.tempC)
        assertNull(s.screen)
        assertEquals("paused", s.toSnapshot().mode)
    }

    @Test
    fun statusJsonOldModule() {
        // v1.2.2 has no proto: readable, but not supported
        val s = ChgCtl.Status.parse("""{"version":"v1.2.2","running":1,"mode":"charging","bypass":0,"limit":80}""")
        assertNotNull(s)
        assertFalse(s!!.supported)
    }
}
