package io.github.capsopasme.assistant

import io.github.capsopasme.assistant.action.Action
import io.github.capsopasme.assistant.action.ActionExecutor
import io.github.capsopasme.assistant.action.MediaCommand
import io.github.capsopasme.assistant.action.SwitchTarget
import io.github.capsopasme.assistant.action.TravelMode
import io.github.capsopasme.assistant.llm.ToolCall
import io.github.capsopasme.assistant.llm.Tools
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class ToolsTest {
    private fun call(name: String, args: String) = Tools.toAction(ToolCall("1", name, args))

    @Test fun toolCallsBecomeActions() {
        assertEquals(Action.Switch(SwitchTarget.BLUETOOTH, true), call("set_switch", """{"name":"bluetooth","state":"on"}"""))
        assertEquals(Action.Switch(SwitchTarget.DND, null), call("set_switch", """{"name":"do_not_disturb","state":"toggle"}"""))
        assertNull(call("set_switch", """{"name":"teleport","state":"on"}"""))
        assertEquals(
            Action.SetAlarm(6, 30, "开会", listOf(Calendar.MONDAY, Calendar.FRIDAY)),
            call("set_alarm", """{"hour":6,"minute":30,"label":"开会","repeat":["mon","fri"]}"""),
        )
        assertEquals(Action.SetAlarm(7, 0), call("set_alarm", """{"hour":"7"}"""))
        assertEquals(Action.SetTimer(90), call("set_timer", """{"seconds":90}"""))
        assertNull(call("set_timer", """{"seconds":0}"""))
        assertEquals(Action.Navigate("西湖", TravelMode.WALKING), call("navigate", """{"destination":"西湖","mode":"walking"}"""))
        assertEquals(Action.Media(MediaCommand.NEXT), call("media_control", """{"action":"next"}"""))
        assertEquals(Action.PlayMusic(null, null, true), call("play_music", """{"shuffle":true}"""))
        assertEquals(Action.Volume(delta = -15), call("set_volume", """{"delta":-15}"""))
        assertNull(call("set_volume", "{}"))
        assertNull(call("open_app", "not json"))
        assertNull(call("unknown_tool", "{}"))
    }

    @Test fun specsAreValid() {
        val specs = Tools.specs
        assertEquals(12, specs.length())
        for (i in 0 until specs.length()) {
            val f = specs.getJSONObject(i).getJSONObject("function")
            assertTrue(f.getString("name").matches(Regex("[a-z_]+")))
            assertEquals("object", f.getJSONObject("parameters").getString("type"))
        }
    }

    @Test fun durations() {
        assertEquals("1小时30分钟", ActionExecutor.formatDuration(5400))
        assertEquals("5分钟30秒", ActionExecutor.formatDuration(330))
        assertEquals("45秒", ActionExecutor.formatDuration(45))
    }
}
