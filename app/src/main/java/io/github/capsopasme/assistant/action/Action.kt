package io.github.capsopasme.assistant.action

/** Everything the assistant can do on the phone, produced by the local parser or the model's tool calls. */
sealed interface Action {
    data class OpenApp(val name: String) : Action
    data class SetTimer(val seconds: Int, val label: String? = null) : Action
    /** [days]: java.util.Calendar day constants (SUNDAY = 1 … SATURDAY = 7), null = one-off. */
    data class SetAlarm(val hour: Int, val minute: Int, val label: String? = null, val days: List<Int>? = null) : Action
    data object ShowAlarms : Action
    data class Call(val target: String) : Action
    data class Navigate(val destination: String, val mode: TravelMode = TravelMode.DRIVING) : Action
    /** [query] null and [shuffle] = shuffle the whole library. */
    data class PlayMusic(val query: String? = null, val artist: String? = null, val shuffle: Boolean = false) : Action
    data class Media(val command: MediaCommand) : Action
    /** [on] null = toggle. */
    data class Switch(val target: SwitchTarget, val on: Boolean?) : Action
    /** Either an absolute [percent] or a relative [delta] in percent points. */
    data class Volume(val percent: Int? = null, val delta: Int? = null) : Action
    data class Brightness(val percent: Int? = null, val delta: Int? = null) : Action
    data class WebSearch(val query: String) : Action
}

enum class TravelMode { DRIVING, WALKING, TRANSIT, BICYCLING }

enum class MediaCommand(val label: String) {
    PLAY("继续播放"), PAUSE("暂停"), NEXT("下一首"), PREVIOUS("上一首")
}

enum class SwitchTarget(val label: String, val toolName: String) {
    BLUETOOTH("蓝牙", "bluetooth"),
    WIFI("WLAN", "wifi"),
    AIRPLANE("飞行模式", "airplane_mode"),
    MOBILE_DATA("移动数据", "mobile_data"),
    DARK_MODE("深色模式", "dark_mode"),
    EYE_CARE("护眼模式", "eye_care"),
    FLASHLIGHT("手电筒", "flashlight"),
    DND("勿扰模式", "do_not_disturb"),
    LOCATION("定位", "location"),
    AUTO_ROTATE("自动旋转", "auto_rotate"),
    NFC("NFC", "nfc");

    companion object {
        fun fromTool(name: String): SwitchTarget? = entries.firstOrNull { it.toolName == name.trim().lowercase() }
    }
}

/**
 * What happened. [message] is shown to the user and fed back to the model.
 * [leftApp]: another app's screen was opened, so the panel should get out of the way.
 * [candidates]: possible matches when the target was ambiguous or not found.
 */
data class ActionResult(
    val ok: Boolean,
    val message: String,
    val leftApp: Boolean = false,
    val candidates: List<String> = emptyList(),
    /** the local parser guessed wrong (e.g. no app with that name): let the model have a go */
    val fallbackToModel: Boolean = false,
)
