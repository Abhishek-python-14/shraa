package com.abhirha.shraa.ai

/** One step for the ActionExecutor. Which fields matter depends on [type] (see [ActionTypes]). */
data class ShraaAction(
    val type: String,
    val app: String? = null,     // "youtube", "whatsapp", any installed app
    val query: String? = null,   // search text / song name
    val target: String? = null,  // contact, number, settings page, media/volume/torch command
    val message: String? = null,
    val time: String? = null     // local ISO-8601, e.g. "2026-10-18T07:00"
)

/** What the AI decided: the actions to run and a short line to speak first. */
data class ShraaPlan(
    val actions: List<ShraaAction>,
    val speech: String,
    val needsClarification: Boolean
)

enum class FailureReason { NO_API_KEY, NO_INTERNET, AUTH, RATE_LIMIT, SERVER, PARSE, UNKNOWN }

sealed interface AiResult {
    data class Success(val plan: ShraaPlan) : AiResult
    data class Failure(val reason: FailureReason, val detail: String? = null) : AiResult
}

object ActionTypes {
    const val OPEN_APP = "OPEN_APP"
    const val PLAY_MEDIA = "PLAY_MEDIA"
    const val SEARCH_IN_APP = "SEARCH_IN_APP"   // app + query: search inside that app
    const val MEDIA_CONTROL = "MEDIA_CONTROL"   // target: play, pause, next, previous
    const val CALL = "CALL"
    const val SEND_MESSAGE = "SEND_MESSAGE"     // really sends the message
    const val DRAFT_MESSAGE = "DRAFT_MESSAGE"   // opens the app/chat and only TYPES the message, never sends
    const val SET_REMINDER = "SET_REMINDER"
    const val CALENDAR_EVENT = "CALENDAR_EVENT"
    const val OPEN_SETTINGS = "OPEN_SETTINGS"
    const val SET_ALARM = "SET_ALARM"           // time: local ISO date-time, target "daily" = repeat
    const val VOLUME = "VOLUME"                 // target: up, down, mute, unmute, max, min or 0-100
    const val TORCH = "TORCH"                   // target: on, off
    const val DAILY_BRIEF = "DAILY_BRIEF"       // time, next alarm, calendar, weather
    const val INSTALL_APP = "INSTALL_APP"       // query: app name to install from the Play Store
    const val APP_CONTROL = "APP_CONTROL"       // app + target: play, pause, stop, next, previous, forward, rewind, nowplaying
    const val UI_CONTROL = "UI_CONTROL"         // target: back, home, recents, notifications, quicksettings, lock, screenshot, scroll_down, scroll_up, click, type
    const val ANSWER_ONLY = "ANSWER_ONLY"       // just speak, no phone action
    const val UNSUPPORTED = "UNSUPPORTED"

    val all = listOf(
        OPEN_APP, PLAY_MEDIA, SEARCH_IN_APP, MEDIA_CONTROL, CALL, SEND_MESSAGE, DRAFT_MESSAGE, SET_REMINDER, CALENDAR_EVENT,
        OPEN_SETTINGS, SET_ALARM, VOLUME, TORCH, DAILY_BRIEF, INSTALL_APP, APP_CONTROL, UI_CONTROL, ANSWER_ONLY, UNSUPPORTED
    )
}
