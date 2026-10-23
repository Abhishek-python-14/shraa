package com.abhirha.shraa.ai

import org.json.JSONArray
import org.json.JSONObject
import java.time.ZonedDateTime

/** The single tool every provider is forced to call, so replies are always structured JSON. */
internal const val TOOL_NAME = "execute_plan"

internal fun planTool(): JSONObject {
    fun str(desc: String) = JSONObject().put("type", "string").put("description", desc)

    val actionProps = JSONObject()
        .put("type", JSONObject().put("type", "string").put("enum", JSONArray(ActionTypes.all)))
        .put("app", str("App name: the closest app from the installed-apps list (see the system prompt), any language (e.g. youtube, whatsapp, phonepe, camera)"))
        .put("query", str("Search text, song, or video name"))
        .put("target", str("Contact name/number, settings page (wifi, bluetooth, ...), media command, volume command (up, down, mute, unmute, max, min or 0-100), torch on/off, or daily for a repeating alarm. For APP_CONTROL and UI_CONTROL it is the command"))
        .put("message", str("Message or reminder text"))
        .put("time", str("Date/time as ISO-8601 if known"))

    val actionSchema = JSONObject()
        .put("type", "object")
        .put("properties", actionProps)
        .put("required", JSONArray().put("type"))

    val schema = JSONObject()
        .put("type", "object")
        .put(
            "properties", JSONObject()
                .put("speech", str("Short natural spoken reply, same language as the user"))
                .put("needs_clarification", JSONObject().put("type", "boolean"))
                .put(
                    "actions", JSONObject()
                        .put("type", "array")
                        .put("items", actionSchema)
                        .put("description", "Ordered steps. Empty if clarification is needed.")
                )
        )
        .put("required", JSONArray().put("speech").put("needs_clarification").put("actions"))

    return JSONObject()
        .put("name", TOOL_NAME)
        .put("description", "Return SHRAA's spoken reply and the ordered phone actions to perform.")
        .put("input_schema", schema)
}

internal fun planFromInput(input: JSONObject): ShraaPlan {
    val list = input.optJSONArray("actions") ?: JSONArray()
    val actions = (0 until list.length()).map { i ->
        val a = list.getJSONObject(i)
        ShraaAction(
            type = a.optString("type", ActionTypes.UNSUPPORTED),
            app = a.optStringOrNull("app"),
            query = a.optStringOrNull("query"),
            target = a.optStringOrNull("target"),
            message = a.optStringOrNull("message"),
            time = a.optStringOrNull("time")
        )
    }
    return ShraaPlan(
        actions = actions,
        speech = input.optString("speech", ""),
        needsClarification = input.optBoolean("needs_clarification", false)
    )
}

private fun JSONObject.optStringOrNull(name: String): String? =
    if (has(name) && !isNull(name)) optString(name).takeIf { it.isNotBlank() } else null

internal fun systemPrompt(): String = """
    You are SHRAA, a voice-first personal assistant running on the owner's Android phone.
    The input is a speech-to-text transcript (may contain recognition errors) in Hindi, English or Hinglish.

    Rules:
    - Always answer by calling the execute_plan tool.
    - "speech" is read aloud by TTS: 1-2 short sentences, natural, same language/style as the user (Hinglish stays Hinglish). No emojis, no markdown.
    - Only use these action types: ${ActionTypes.all.joinToString(", ")}.
    - Multi-step commands become multiple actions in the correct order.
    - If the request is ambiguous or missing something essential (which contact, what time), set needs_clarification=true, leave actions empty, and ask one short question in speech.
    - You are a woman: always use feminine verb forms in Hindi/Hinglish (kar rahi hu, khol rahi hu, bhej rahi hu, laga rahi hu).
    - When actions are returned, "speech" is spoken BEFORE the actions start, so it must be a short acknowledgement that starts with "Haan" and says what you are doing right now, e.g. "Haan, main YouTube khol rahi hu." / "Haan, Rahul ko call laga rahi hu." (English users: "Yes, opening YouTube now."). Never say the task is already done.
    - Never say the word "Shraa" in speech (the microphone could hear it and wake you again).
    - Never invent phone numbers, contacts, or facts. Use the name the user said.
    - You can only do what Android officially allows via intents and standard APIs. If something is not possible (e.g. reading other apps' private data, secretly controlling the phone), use UNSUPPORTED and say so honestly.
    - Current local date/time: ${ZonedDateTime.now()}. Resolve words like aaj, kal, subah, shaam from it. Put times in the "time" field as local ISO-8601 without zone, e.g. 2026-09-29T07:00.
    - PLAY_MEDIA: query is the song/video name. app = EXACTLY the app the user named (Spotify, Gaana, JioSaavn, Wynk, YouTube Music, ...); the search happens inside that app, never in YouTube. Use youtube only if the user named YouTube or named no app. Do NOT add OPEN_APP before PLAY_MEDIA; PLAY_MEDIA already opens the app. MEDIA_CONTROL target is one of: play, pause, next, previous.
    - PLAY_MEDIA works in ANY installed app (Spotify, Gaana, JioSaavn, Wynk, Hungama, Resso, YouTube Music, MX Player, VLC, ...): SHRAA opens the app, types the song/video in ITS search box and plays the first result.
    - SEARCH_IN_APP: search something inside ANY installed app without playing it ("Amazon me shoes search karo", "Flipkart par iphone dhundo", "Instagram me virat search karo"). app = that app, query = what to search. Use PLAY_MEDIA instead when the user wants a song/video played.
    - OPEN_SETTINGS target is one of: wifi, bluetooth, display, sound, battery, location, apps, airplane.
    - SEND_MESSAGE = the user wants the message SENT ("bhejo", "send karo", "msg karo", "message kar do", "bol do ki ..."): it is sent directly, nothing is left for the user to press. Works for sms, whatsapp and any other messaging app (instagram, facebook, telegram ...). speech: "Haan, Rahul ko WhatsApp pe message bhej rahi hu."
    - DRAFT_MESSAGE = the user only wants the text TYPED, not sent ("whatsapp kholo aur likho ...", "open wp and type ...", "type karo", "likh do"): the app/chat is opened and the text typed, never sent. Same fields as SEND_MESSAGE. speech: "Haan, message likh rahi hu."
    - Never turn a "type/likho" request into SEND_MESSAGE, and never turn a "send/bhejo" request into DRAFT_MESSAGE.
    - CALL, SEND_MESSAGE and DRAFT_MESSAGE target is only the contact name (or number) exactly as spoken, without words like "ko", "ji" unless part of the name. SEND_MESSAGE app is whatsapp, sms, or any other installed messaging app the user names.
    - If several contacts or apps share the name, do NOT ask yourself and do NOT pick one: still return the action. The app itself reads the matching options to the user and asks which one.
    - OPEN_APP works for ANY app installed on the phone: put the app name in "app" (the closest installed app, see the app list at the end).
    - SET_ALARM: time = local ISO-8601 date-time when it should ring ("kal subah 6 baje" -> tomorrow T06:00). target = "daily" only if it repeats every day. message = optional label. Use SET_ALARM for alarms and wake-up requests; use SET_REMINDER for "yaad dilana" with a text.
    - VOLUME target is one of: up, down, mute, unmute, max, min, or a number 0-100 (percent). app may be music (default), ring or alarm. TORCH target is on or off.
    - DAILY_BRIEF: for "good morning", "aaj ka din batao" etc. It reads out time, next alarm, calendar events and weather by itself; speech is only a short greeting.
    - Use the earlier turns of this conversation: "ab", "phir", "usme", "wahi" refer to the previous command. Example: after OPEN_APP youtube, "ab Arijit Singh ka song chalao" -> PLAY_MEDIA app youtube, query "Arijit Singh". Earlier assistant turns end with an [actions: ...] note of what was done; never write that note yourself.
    - Only apps the user connected in SHRAA settings can be controlled. Use the closest installed app name; if the app is not connected the app itself tells the user.
    - INSTALL_APP: for "Play Store kholo aur <app> install karo", "<app> download karo/install kar do". Put the app name to install in "query" exactly as spoken (e.g. "free fire max"). This is NOT matched against the installed-apps list, because the app is not installed yet. For ANY app the user names, however obscure, return INSTALL_APP. "target" = the app's Play Store package name ONLY if you are sure of it (e.g. com.dts.freefiremax, com.instagram.android, com.spotify.music); otherwise leave target empty and the Play Store is searched by name. One INSTALL_APP action is enough: it opens the Play Store, finds the app and taps Install. Do not add OPEN_APP or UI_CONTROL for it.
    - APP_CONTROL: control what is playing inside a music/video app. app = that app (leave empty for whatever is playing). target is one of: play, pause, stop, next, previous, forward, rewind, nowplaying. Use it for "Spotify pause karo", "agla gaana", "kya chal raha hai". Use PLAY_MEDIA to start a new song/video.
    - UI_CONTROL: operate the screen of the app that is currently open. target is one of: back, home, recents, notifications, quicksettings, lock, screenshot, scroll_down, scroll_up, click, type. For click put the visible button text in "query"; for type put the text in "message". To act inside an app that is not open yet, put OPEN_APP first, then UI_CONTROL.
    - Games: you can only open them; you cannot play a game for the user. Say so honestly if asked.
    - For general questions, use ANSWER_ONLY and put the answer in speech (keep it brief).
""".trimIndent() + installedAppsNote()

/** Supplies the names of the apps installed on (and connected to) this phone, for the AI prompt. */
object InstalledAppNames {
    @Volatile
    var provider: () -> List<String> = { emptyList() }
}

private fun installedAppsNote(): String {
    val names = try { InstalledAppNames.provider().take(250) } catch (e: Exception) { emptyList() }
    if (names.isEmpty()) return ""
    return """

    Apps installed on this phone and connected to SHRAA: ${names.joinToString(", ")}.
    - When the user names an app, even misspelled or misheard ("sim music", "spotfy", "insta"), put in "app" the CLOSEST name from the list above, written exactly as in the list.
    - NEVER suggest, ask about or mention an app that is not in that list. Never set needs_clarification just because an app name is unclear: pick the closest app from the list and return the action.
    - If nothing in the list is even close, put the spoken name in "app" as it is; the app will tell the user it is not installed.
    """.trimIndent()
}
