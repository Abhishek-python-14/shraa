package com.abhirha.shraa.actions

import com.abhirha.shraa.ai.ActionTypes
import com.abhirha.shraa.ai.ShraaAction
import com.abhirha.shraa.ai.ShraaPlan
import java.time.LocalDateTime
import java.util.Locale

/**
 * Offline "basic mode": understands the common commands WITHOUT any AI or internet
 * (alarm, reminder, volume, torch, open app, call, settings, media, play on YouTube, time, good morning).
 * Used when the phone is offline or every AI provider failed. Returns null when it is not sure.
 */
object LocalIntent {

    /** Common Hindi-script words -> Latin, so one set of rules handles both scripts. */
    private val devaWords = mapOf(
        "खोलो" to "kholo", "खोल" to "khol", "दो" to "do", "चलाओ" to "chalao", "चला" to "chala",
        "लगाओ" to "lagao", "अलार्म" to "alarm", "उठा" to "utha", "उठाना" to "uthana", "जगा" to "jaga",
        "जगाना" to "jagana", "बजे" to "baje", "सुबह" to "subah", "शाम" to "shaam", "रात" to "raat",
        "दोपहर" to "dopahar", "कल" to "kal", "आज" to "aaj", "परसों" to "parso", "कॉल" to "call",
        "फोन" to "phone", "को" to "ko", "करो" to "karo", "कर" to "kar", "वॉल्यूम" to "volume",
        "आवाज़" to "awaaz", "आवाज" to "awaaz", "बढ़ाओ" to "badhao", "बढ़ा" to "badha", "कम" to "kam",
        "घटाओ" to "ghatao", "म्यूट" to "mute", "टॉर्च" to "torch", "फ्लैशलाइट" to "flashlight",
        "चालू" to "chalu", "बंद" to "band", "ऑन" to "on", "ऑफ" to "off", "टाइम" to "time",
        "समय" to "samay", "कितने" to "kitne", "क्या" to "kya", "गाना" to "gana", "गाने" to "gane",
        "सॉन्ग" to "song", "यूट्यूब" to "youtube", "पे" to "pe", "पर" to "par", "का" to "ka",
        "के" to "ke", "की" to "ki", "मुझे" to "mujhe", "वाईफाई" to "wifi", "ब्लूटूथ" to "bluetooth",
        "सेटिंग" to "setting", "सेटिंग्स" to "settings", "ब्राइटनेस" to "brightness", "बैटरी" to "battery",
        "ओपन" to "open", "अब" to "ab", "रुको" to "ruko", "पॉज" to "pause", "पॉज़" to "pause",
        "नेक्स्ट" to "next", "प्ले" to "play", "याद" to "yaad", "दिलाना" to "dilana",
        "रिमाइंडर" to "reminder", "मिनट" to "minute", "घंटे" to "ghante", "बाद" to "baad", "में" to "mein",
        "गुड" to "good", "मॉर्निंग" to "morning", "मोर्निंग" to "morning", "सुप्रभात" to "suprabhat",
        "रोज़" to "roz", "रोज" to "roz"
    )

    private val playStop = setOf(
        "ab", "please", "youtube", "yt", "pe", "par", "on", "ka", "ke", "ki", "song", "songs", "gana",
        "gaana", "gane", "music", "video", "chalao", "chala", "do", "play", "lagao", "laga", "bajao",
        "baja", "karo", "kar", "mujhe", "mere", "liye", "ek", "the", "spotify", "aur", "phir", "fir",
        "wala", "wali", "vala", "se", "jiosaavn", "saavn", "wynk", "resso", "hungama"
    )
    private val reminderStop = setOf(
        "mujhe", "yaad", "dilana", "dila", "dena", "de", "do", "remind", "reminder", "me", "karo", "kar",
        "kal", "aaj", "parso", "subah", "shaam", "raat", "dopahar", "baje", "bje", "am", "pm", "ko", "ki", "ka"
    )
    private val musicApps = listOf("youtube", "spotify", "gaana", "jiosaavn", "wynk", "youtube music")

    private val timeQuestion =
        Regex("\\b(time kya|kya time|kitne baje|what time|samay kya|time batao|abhi kitne baje)\\b")
    private val callPrefix = Regex("^(?:please )?(?:call|phone)\\s+(.+)$")
    private val callSuffix =
        Regex("^(.+?)\\s+ko\\s+(?:call|phone|fone)(?:\\s+(?:karo|kar do|kar|lagao|laga do|laga))?$")
    private val openPrefix = Regex("^(?:open|launch|start)\\s+(.+)$")
    private val openSuffix = Regex(
        "^(.+?)\\s+(?:kholo|khol do|khol de|khol|open karo|open kar do|open|chalu karo|start karo|launch karo)$"
    )

    private fun normalize(raw: String): String {
        val ascii = raw.map { if (it in '०'..'९') '0' + (it - '०') else it }.joinToString("")
        // Strip punctuation, but keep the dot/comma inside numbers like 7.30.
        val spaced = ascii.lowercase(Locale.ROOT).replace(Regex("(?<!\\d)[.,!?।]|[.,!?।](?!\\d)"), " ")
        return spaced.split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ") { devaWords[it] ?: it }
    }

    fun isBrief(raw: String): Boolean {
        val t = normalize(raw)
        return "good morning" in t || "suprabhat" in t || t == "gm" ||
            "aaj ka din" in t || "daily brief" in t || "morning brief" in t
    }

    /** [lastApp] = app used by the previous command of this conversation ("ab ... chalao"). */
    fun parse(raw: String, lastApp: String?): ShraaPlan? {
        val t = normalize(raw)
        if (t.isBlank()) return null
        val w = t.split(' ')
        fun has(vararg k: String) = k.any { it in w }
        fun plan(speech: String, a: ShraaAction) = ShraaPlan(listOf(a), speech, false)
        fun media(cmd: String, say: String) =
            plan("Haan, $say.", ShraaAction(ActionTypes.MEDIA_CONTROL, target = cmd))

        if (isBrief(raw)) return plan("Good morning!", ShraaAction(ActionTypes.DAILY_BRIEF))

        if (timeQuestion.containsMatchIn(t)) return ShraaPlan(emptyList(), "Abhi ${SpokenTime.nowSentence()}", false)

        // ---- alarm / wake-up / reminder ----
        val alarmWord = has("alarm", "utha", "uthana", "jaga", "jagana", "wake", "uthna", "timer")
        val reminderWord = has("remind", "reminder", "yaad")
        if (alarmWord || reminderWord) {
            val dt = parseWhen(t) ?: return null
            val daily = has("roz", "daily", "everyday") || "every day" in t || "har din" in t
            val iso = dt.withSecond(0).withNano(0).toString()
            return if (alarmWord) {
                plan(
                    "Haan, ${SpokenTime.full(dt, daily)} ka alarm laga rahi hu.",
                    ShraaAction(ActionTypes.SET_ALARM, time = iso, target = if (daily) "daily" else null)
                )
            } else {
                val text = w.filterNot { it in reminderStop || it.firstOrNull()?.isDigit() == true }.joinToString(" ")
                plan(
                    "Haan, reminder laga rahi hu.",
                    ShraaAction(ActionTypes.SET_REMINDER, message = text.ifBlank { "SHRAA reminder" }, time = iso)
                )
            }
        }

        // ---- volume ----
        if (has("volume", "awaaz", "sound", "aawaz")) {
            val pct = Regex("\\b(\\d{1,3})\\b").find(t)?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it <= 100 }
            val (cmd, say) = when {
                pct != null -> pct.toString() to "$pct percent kar rahi hu"
                has("unmute") -> "unmute" to "unmute kar rahi hu"
                has("mute", "silent", "chup") -> "mute" to "mute kar rahi hu"
                has("full", "max", "maximum", "poori") -> "max" to "full kar rahi hu"
                has("badhao", "badha", "increase", "up", "zyada", "jyada", "tez", "loud", "high") -> "up" to "badha rahi hu"
                has("kam", "ghatao", "ghata", "decrease", "down", "low", "dheere", "halka") -> "down" to "kam kar rahi hu"
                else -> return null
            }
            return plan("Haan, volume $say.", ShraaAction(ActionTypes.VOLUME, target = cmd))
        }

        // ---- torch ----
        if (has("torch", "flashlight", "flash", "tourch")) {
            return when {
                has("off", "band", "bujhao", "bujha", "stop") ->
                    plan("Haan, torch band kar rahi hu.", ShraaAction(ActionTypes.TORCH, target = "off"))
                has("on", "chalu", "jalao", "jala", "start", "kholo") ->
                    plan("Haan, torch on kar rahi hu.", ShraaAction(ActionTypes.TORCH, target = "on"))
                else -> null
            }
        }

        // ---- call ----
        val who = callPrefix.find(t)?.groupValues?.get(1)?.removeSuffix(" karo")?.removeSuffix(" kar do")
            ?: callSuffix.find(t)?.groupValues?.get(1)
        if (!who.isNullOrBlank()) {
            val name = who.trim()
            return plan("Haan, $name ko call laga rahi hu.", ShraaAction(ActionTypes.CALL, target = name))
        }

        // ---- media keys ----
        if (has("pause", "ruko", "roko")) return media("pause", "pause kar rahi hu")
        if (has("resume", "continue")) return media("play", "chala rahi hu")
        if (has("next", "agla", "skip")) return media("next", "agla laga rahi hu")
        if (has("previous", "pichla", "pichhla")) return media("previous", "pichla laga rahi hu")

        // ---- play a song (uses the music app of the previous command when none is named) ----
        if (has("chalao", "chala", "lagao", "bajao", "baja", "play") &&
            has("youtube", "yt", "song", "songs", "gana", "gaana", "gane", "music", "video", "spotify", "jiosaavn", "saavn", "wynk", "resso", "hungama")
        ) {
            val query = w.filterNot { it in playStop }.joinToString(" ").trim()
            if (query.isBlank()) return null
            val app = when {
                has("youtube", "yt") && has("music") -> "youtube music"
                has("spotify") -> "spotify"
                has("gaana") -> "gaana"
                has("jiosaavn", "saavn") -> "jiosaavn"
                has("wynk") -> "wynk"
                has("resso") -> "resso"
                has("hungama") -> "hungama"
                has("youtube", "yt") -> "youtube"
                lastApp != null && musicApps.any { it in lastApp.lowercase() } -> lastApp
                else -> "youtube"
            }
            return plan("Haan, $query chala rahi hu.", ShraaAction(ActionTypes.PLAY_MEDIA, app = app, query = query))
        }

        // ---- settings pages ----
        val page = SETTINGS_PAGES.firstOrNull { it.first in w }?.second
        if (page != null || has("setting", "settings")) {
            val say = if (page != null) "Haan, $page settings khol rahi hu." else "Haan, settings khol rahi hu."
            return plan(say, ShraaAction(ActionTypes.OPEN_SETTINGS, target = page))
        }

        // ---- open an app ----
        val opened = openPrefix.find(t)?.groupValues?.get(1) ?: openSuffix.find(t)?.groupValues?.get(1)
        val app = opened?.removeSuffix(" ko")?.removePrefix("app ")?.removeSuffix(" app")?.trim()
        if (!app.isNullOrBlank()) return plan("Haan, $app khol rahi hu.", ShraaAction(ActionTypes.OPEN_APP, app = app))

        return null
    }

    private val SETTINGS_PAGES = listOf(
        "wifi" to "wifi", "wi-fi" to "wifi", "bluetooth" to "bluetooth", "brightness" to "display",
        "display" to "display", "battery" to "battery", "location" to "location",
        "airplane" to "airplane", "sound" to "sound"
    )

    /** Understands "kal subah 6 baje", "7:30 pm", "shaam 5 baje", "10 minute baad", "2 ghante baad". */
    private fun parseWhen(t: String): LocalDateTime? {
        val now = LocalDateTime.now()

        // relative: "10 minute baad", "2 ghante baad"
        Regex("(\\d+)\\s*(minute|minutes|min|mins|ghante|ghanta|hour|hours|hrs|hr)\\b").find(t)?.let { m ->
            if (Regex("\\b(baad|mein|later|in|after)\\b").containsMatchIn(t)) {
                val n = m.groupValues[1].toLongOrNull() ?: return null
                val unit = m.groupValues[2]
                val hours = unit.startsWith("gh") || unit.startsWith("hour") || unit.startsWith("hr")
                return (if (hours) now.plusHours(n) else now.plusMinutes(n)).withSecond(0).withNano(0)
            }
        }

        // clock time
        val m = Regex("(\\d{1,2})(?:[:.](\\d{2}))?\\s*(am|pm|baje|bje)?").find(t) ?: return null
        var hour = m.groupValues[1].toInt()
        val minute = m.groupValues[2].ifEmpty { "0" }.toInt()
        if (hour > 23 || minute > 59) return null
        val meridiem = m.groupValues[3]
        val w = t.split(' ')
        fun any(vararg k: String) = k.any { it in w }
        val night = any("raat", "night")
        val evening = any("shaam", "sham", "evening")
        val afternoon = any("dopahar", "afternoon")
        val morning = any("subah", "morning", "savere")
        val explicit = meridiem == "am" || meridiem == "pm" || night || evening || afternoon || morning
        when {
            meridiem == "pm" -> if (hour < 12) hour += 12
            meridiem == "am" -> if (hour == 12) hour = 0
            night -> hour = when { hour == 12 -> 0; hour in 6..11 -> hour + 12; else -> hour }
            evening -> if (hour < 12) hour += 12
            afternoon -> if (hour in 1..6) hour += 12
            morning -> if (hour == 12) hour = 0
        }

        val dayOffset = when {
            any("kal", "tomorrow") -> 1L
            any("parso") || "day after" in t -> 2L
            else -> 0L
        }
        val date = now.toLocalDate().plusDays(dayOffset)
        var dt = date.atTime(hour % 24, minute)
        if (dayOffset == 0L) {
            if (!explicit && hour in 1..11) {
                // "6 baje" without am/pm: the next 6 o'clock, morning or evening.
                val am = date.atTime(hour, minute)
                val pm = date.atTime(hour + 12, minute)
                dt = listOf(am, pm, am.plusDays(1)).first { it.isAfter(now) }
            } else if (!dt.isAfter(now)) {
                dt = dt.plusDays(1)
            }
        }
        return dt
    }
}
