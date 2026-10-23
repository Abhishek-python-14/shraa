package com.abhirha.shraa.actions

import android.Manifest
import android.app.SearchManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.provider.MediaStore
import android.provider.Settings
import android.view.KeyEvent
import androidx.core.content.ContextCompat
import com.abhirha.shraa.ai.ActionTypes
import com.abhirha.shraa.ai.ShraaAction
import com.abhirha.shraa.apps.ConnectedAppsStore
import com.abhirha.shraa.apps.MediaAccess
import com.abhirha.shraa.service.ShraaAccessibilityService
import java.net.URLEncoder
import java.time.Duration
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.util.Calendar
import java.util.Locale
import kotlin.math.roundToInt

/** One option SHRAA reads out when a name matches several contacts / apps. [perform] does it. */
class Choice(val label: String, val perform: () -> ExecResult)

/**
 * Result of an action. When [choices] is not empty, [message] is the question to ask the user
 * ("Rahul ke 3 contact mile. Pehla, ...") and nothing has been done yet.
 */
data class ExecResult(
    val ok: Boolean,
    val message: String,
    val choices: List<Choice> = emptyList(),
    /** True = SHRAA speaks [message] after the task (alarm set, reminder saved, brief ...). */
    val confirm: Boolean = false
)

/**
 * Turns the AI's structured actions into official Android intents and APIs.
 * It never claims more than Android allows: messages are prepared (the user taps send),
 * calls use the dialer unless CALL_PHONE was granted, and playback depends on the target app.
 */
class ActionExecutor(context: Context) {
    private val ctx = context.applicationContext
    private val pm = ctx.packageManager
    private val connected = ConnectedAppsStore(ctx)

    /** Speaks a result that arrives later (auto-send finished or failed). Set by the controller. */
    var notify: (String) -> Unit = {}

    fun run(a: ShraaAction): ExecResult = guard {
        when (a.type) {
            ActionTypes.OPEN_APP -> openApp(a.app ?: a.target)
            ActionTypes.PLAY_MEDIA -> playMedia(a.app, a.query ?: a.target)
            ActionTypes.SEARCH_IN_APP -> searchApp(a.app, a.query ?: a.target)
            ActionTypes.MEDIA_CONTROL -> mediaControl(a.target)
            ActionTypes.CALL -> call(a.target)
            ActionTypes.SEND_MESSAGE -> sendMessage(a.app, a.target, a.message, send = true)
            ActionTypes.DRAFT_MESSAGE -> sendMessage(a.app, a.target, a.message, send = false)
            ActionTypes.SET_REMINDER -> setReminder(a.message ?: a.query, a.time)
            ActionTypes.CALENDAR_EVENT -> calendarEvent(a.message ?: a.query, a.time)
            ActionTypes.OPEN_SETTINGS -> openSettings(a.target)
            ActionTypes.SET_ALARM -> setAlarm(a.time, a.message ?: a.query, a.target)
            ActionTypes.VOLUME -> volume(a.target, a.app)
            ActionTypes.TORCH -> torch(a.target)
            ActionTypes.INSTALL_APP -> installApp(a.query ?: a.app, a.target)
            ActionTypes.APP_CONTROL -> appControl(a.app, a.target ?: a.query)
            ActionTypes.UI_CONTROL -> uiControl(a.target, a.query, a.message)
            ActionTypes.UNSUPPORTED -> ExecResult(false, "Ye kaam Android allow nahi karta.")
            else -> ExecResult(false, "Ye action abhi supported nahi hai.")
        }
    }

    // =========================================================================================
    // Shared helpers
    // =========================================================================================

    /** Turns Android exceptions into a short spoken message. Also wraps every [Choice]. */
    private fun guard(block: () -> ExecResult): ExecResult = try {
        block()
    } catch (e: ActivityNotFoundException) {
        ExecResult(false, "Iske liye app nahi mila.")
    } catch (e: SecurityException) {
        ExecResult(
            false,
            if (e.message == "contacts") "Contacts permission chahiye. Settings me allow karo."
            else "Permission chahiye. Settings me allow karo."
        )
    } catch (e: Exception) {
        ExecResult(false, "Ye kaam nahi ho paya.")
    }

    private fun launch(i: Intent) {
        ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun has(p: String) = ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED

    private fun isInstalled(pkg: String) = pm.getLaunchIntentForPackage(pkg) != null

    /** Contact names without emojis and symbols ("BHAIYA 🤍" -> "BHAIYA"), so speech and screen stay clean. */
    private fun tidy(name: String): String =
        name.replace(NAME_JUNK, " ").replace(Regex("\\s+"), " ").trim().ifEmpty { name }

    private fun digits(s: String) = s.filter { it.isDigit() }

    private fun looksLikeNumber(s: String) = NUMBER_PATTERN.matches(s.trim())

    private fun dialable(s: String) = s.filter { it.isDigit() || it == '+' }

    /** "Rahul ke 3 contact mile. Pehla, Rahul Sharma. Doosra, ... Kisko call karun?" */
    private fun question(query: String, noun: String, labels: List<String>, verb: String): String {
        val list = labels.mapIndexed { i, l -> "${ORDINALS[i]}, $l" }.joinToString(". ")
        return "$query ke ${labels.size} $noun mile. $list. Kisko $verb?"
    }

    // =========================================================================================
    // Installed apps
    // =========================================================================================

    private class AppEntry(val label: String, val pkg: String, val activity: String)

    private fun installedApps(): List<AppEntry> {
        val main = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(main, 0)
            .map { AppEntry(it.loadLabel(pm).toString(), it.activityInfo.packageName, it.activityInfo.name) }
            .distinctBy { it.pkg + "|" + it.label }
    }

    /** Best-matching apps for what the user said (any language). More than one = ambiguous. */
    private fun findApps(name: String): List<AppEntry> {
        val q = APP_SYNONYMS[NameMatch.norm(name)] ?: name
        val ranked = installedApps().map { it to NameMatch.rank(q, it.label) }.filter { it.second > 0 }
        if (ranked.isEmpty()) return emptyList()
        val top = ranked.maxOf { it.second }
        return ranked.filter { it.second == top }.map { it.first }.sortedBy { it.label.length }
    }

    @Volatile private var labelCache: Pair<Long, List<String>>? = null

    /** Names of the connected apps on this phone (for the AI prompt). Cached for a minute. */
    fun appLabels(): List<String> {
        val now = System.currentTimeMillis()
        labelCache?.let { if (now - it.first < 60_000) return it.second }
        val labels = installedApps().filter { connected.isConnected(it.pkg) }
            .map { it.label }.distinct().sortedBy { it.lowercase() }
        labelCache = now to labels
        return labels
    }

    /** Closest installed apps for a misspelled / misheard name, best first. */
    private fun suggestApps(name: String): List<Pair<AppEntry, Double>> {
        val q = APP_SYNONYMS[NameMatch.norm(name)] ?: name
        return installedApps()
            .map { it to NameMatch.similarity(q, it.label) }
            .filter { it.second >= SUGGEST_MIN }
            .sortedByDescending { it.second }
            .distinctBy { NameMatch.norm(it.first.label) }
            .take(MAX_CHOICES)
    }

    /** No exact match: go ahead with one clear near-match, or read the closest apps and ask. */
    private fun suggestApp(name: String, verb: String, action: (AppEntry) -> ExecResult): ExecResult {
        val all = suggestApps(name)
        if (all.isEmpty()) return ExecResult(false, "$name app nahi mila.")
        val usable = all.filter { connected.isConnected(it.first.pkg) }
        if (usable.isEmpty()) return notConnected(all[0].first.label)
        val clear = usable.size == 1 || (usable[0].second >= 0.85 && usable[0].second - usable[1].second >= 0.1)
        if (clear) return action(usable[0].first)
        val list = usable.mapIndexed { i, u -> "${ORDINALS[i]}, ${u.first.label}" }.joinToString(". ")
        return ExecResult(
            true,
            "$name app nahi mila. Kya aap inme se keh rahe the? $list. Kaun sa $verb?",
            usable.map { u -> Choice(u.first.label) { guard { action(u.first) } } }
        )
    }

    private fun notConnected(label: String) =
        ExecResult(false, "$label SHRAA se connected nahi hai. Settings me Connected Apps se connect karo.")

    private fun launchApp(app: AppEntry) {
        launch(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setClassName(app.pkg, app.activity))
    }

    /** Runs [action] on the matching app; if several apps match, asks which one first. */
    private fun withApp(name: String, verb: String, action: (AppEntry) -> ExecResult): ExecResult {
        val found = findApps(name)
        val hits = found.filter { connected.isConnected(it.pkg) }
        return when {
            found.isEmpty() -> suggestApp(name, verb, action)
            hits.isEmpty() -> notConnected(found[0].label)
            hits.size == 1 -> action(hits[0])
            else -> {
                val shown = hits.take(MAX_CHOICES)
                ExecResult(
                    true,
                    question(name, "app", shown.map { it.label }, verb),
                    shown.map { app -> Choice(app.label) { guard { action(app) } } }
                )
            }
        }
    }

    // =========================================================================================
    // Contacts
    // =========================================================================================

    private class Candidate(val contactId: Long, val name: String, val number: String, val type: String)

    private fun loadContacts(): List<Candidate> {
        if (!has(Manifest.permission.READ_CONTACTS)) throw SecurityException("contacts")
        val out = ArrayList<Candidate>()
        ctx.contentResolver.query(
            Phone.CONTENT_URI,
            arrayOf(Phone.CONTACT_ID, Phone.DISPLAY_NAME, Phone.NUMBER, Phone.TYPE, Phone.LABEL),
            null, null, null
        )?.use { c ->
            while (c.moveToNext()) {
                val name = c.getString(1) ?: continue
                val number = c.getString(2) ?: continue
                val type = Phone.getTypeLabel(ctx.resources, c.getInt(3), c.getString(4)).toString()
                out += Candidate(c.getLong(0), tidy(name), number, type)
            }
        }
        return out
    }

    /** All contacts that could be who the user meant, best matches first. */
    private fun findContacts(query: String): List<Candidate> {
        val all = loadContacts().distinctBy { it.contactId to digits(it.number).takeLast(10) }
        val ranked = all.map { it to NameMatch.rank(query, it.name) }.filter { it.second > 0 }
        if (ranked.isEmpty()) return emptyList()
        // Said exactly "Bhaiya" and a contact is exactly "Bhaiya" (emojis ignored): only that one.
        // "Ankit Bhaiya" and the like are not suggested then.
        val exact = ranked.filter { it.second == 4 }
        val strong = ranked.filter { it.second >= 3 }
        return (exact.ifEmpty { strong.ifEmpty { ranked } })
            .sortedWith(compareBy<Pair<Candidate, Int>>({ -it.second }, { it.first.name.lowercase() }))
            .map { it.first }
    }

    /** Labels read to the user: adds the number type / last digits when names repeat. */
    private fun spokenLabels(list: List<Candidate>): List<String> {
        val byName = list.groupBy { NameMatch.norm(it.name) }
        return list.map { h ->
            val same = byName.getValue(NameMatch.norm(h.name))
            when {
                same.size == 1 -> h.name
                same.map { it.contactId }.distinct().size == 1 -> "${h.name} ${h.type}"
                else -> "${h.name} number " + digits(h.number).takeLast(4).toCharArray().joinToString(" ")
            }
        }
    }

    /**
     * Runs [action] for the contact the user named. One match = go ahead. Several people with that
     * name (or one person with several numbers) = read the options out and ask first.
     */
    private fun withContact(query: String, verb: String, action: (name: String, number: String) -> ExecResult): ExecResult {
        val hits = findContacts(query)
        if (hits.isEmpty()) return ExecResult(false, "$query contact nahi mila.")
        if (hits.size == 1) return action(hits[0].name, hits[0].number)
        val shown = hits.take(MAX_CHOICES)
        val labels = spokenLabels(shown)
        return ExecResult(
            true,
            question(query, "contact", labels, verb),
            shown.mapIndexed { i, h -> Choice(labels[i]) { guard { action(h.name, h.number) } } }
        )
    }

    // =========================================================================================
    // Apps, media, calls, messages
    // =========================================================================================

    private fun openApp(name: String?): ExecResult {
        if (name.isNullOrBlank()) return ExecResult(false, "Kaun sa app kholna hai?")
        return withApp(name, "kholun") { app ->
            launchApp(app)
            ExecResult(true, "${app.label} khol diya.")
        }
    }

    /** Plays / searches inside the app the user NAMED. YouTube is used only when no app was named. */
    private fun playMedia(app: String?, query: String?): ExecResult {
        if (query.isNullOrBlank()) return ExecResult(false, "Kya chalana hai?")
        val target = app?.trim()
        if (target.isNullOrBlank() || NameMatch.rank(target, "youtube") >= 2) return youtubeSearch(query)
        return withApp(target, "chalaun") { a -> searchInApp(a, query, play = true) }
    }

    private fun tryStart(i: Intent): Boolean = try {
        if (i.resolveActivity(pm) == null) false else { launch(i); true }
    } catch (e: ActivityNotFoundException) {
        false
    } catch (e: SecurityException) {
        false
    }

    /** Search links that open the search results of well-known music apps. */
    private fun deepLinks(pkg: String, q: String): List<Intent> {
        val e = URLEncoder.encode(q, "UTF-8")
        val uri = when {
            pkg.startsWith("com.spotify") -> "spotify:search:" + Uri.encode(q)
            pkg == "com.google.android.apps.youtube.music" -> "https://music.youtube.com/search?q=$e"
            pkg == "com.jio.media.jiobeats" || "saavn" in pkg -> "https://www.jiosaavn.com/search/$e"
            "gaana" in pkg -> "https://gaana.com/search/$e"
            else -> return emptyList()
        }
        return listOf(Intent(Intent.ACTION_VIEW, Uri.parse(uri)))
    }

    /** "<app> me <something> search karo": search only, nothing is played. */
    private fun searchApp(app: String?, query: String?): ExecResult {
        if (query.isNullOrBlank()) return ExecResult(false, "Kya search karna hai?")
        if (app.isNullOrBlank()) return ExecResult(false, "Kis app me search karna hai?")
        if (NameMatch.rank(app, "youtube") >= 2) return youtubeSearch(query, play = false)
        return withApp(app, "kholun") { a -> searchInApp(a, query, play = false) }
    }

    /**
     * Search (and with [play] start playing) inside ANY app.
     * Spotify / YouTube Music have reliable links. Every other app is operated through its own search box
     * with Phone control (Accessibility); without it the app's search intents are tried as a fallback.
     */
    private fun searchInApp(a: AppEntry, query: String, play: Boolean): ExecResult {
        val svc = ShraaAccessibilityService.instance
        val links = deepLinks(a.pkg, query)
        val playIntent = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH)
            .putExtra(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*")
            .putExtra(SearchManager.QUERY, query)

        if (a.pkg.startsWith("com.spotify")) {
            val i = if (play) playIntent else links.firstOrNull() ?: playIntent
            if (tryStart(Intent(i).setPackage(a.pkg))) {
                return ExecResult(true, "${a.label} me $query ${if (play) "chala diya" else "search kar diya"}.")
            }
        }
        if (a.pkg == "com.google.android.apps.youtube.music" && links.isNotEmpty() && tryStart(Intent(links[0]).setPackage(a.pkg))) {
            if (play && svc != null) svc.searchInApp(a.pkg, query, play = true, alreadySearched = true) { notify(it) }
            return ExecResult(true, "${a.label} me $query ${if (play) "chala rahi hu" else "search kar diya"}.")
        }

        if (svc != null) {
            launchApp(a)
            svc.searchInApp(a.pkg, query, play, alreadySearched = false) { msg -> notify(msg) }
            return ExecResult(true, "${a.label} me $query ${if (play) "chalane" else "search karne"} ki koshish kar rahi hu.")
        }

        // No Phone control: whatever the app itself understands.
        val search = Intent(Intent.ACTION_SEARCH).putExtra(SearchManager.QUERY, query)
        val tries = ArrayList<Intent>()
        tries += links
        tries += search
        if (play) tries += playIntent
        for (i in tries) {
            if (tryStart(Intent(i).setPackage(a.pkg))) {
                return ExecResult(true, "${a.label} khol diya. Seedha search/play ke liye Settings me Phone control allow karo.", confirm = true)
            }
        }
        launchApp(a)
        return ExecResult(
            true,
            "${a.label} khol diya. Seedha search/play ke liye Settings me Phone control allow karo.",
            confirm = true
        )
    }

    private fun youtubeSearch(query: String, play: Boolean = true): ExecResult {
        val yt = "com.google.android.youtube"
        if (isInstalled(yt) && !connected.isConnected(yt)) return notConnected("YouTube")
        return if (isInstalled(yt)) {
            // YouTube exposes search, not guaranteed auto-play, so the results are opened.
            launch(Intent(Intent.ACTION_SEARCH).setPackage(yt).putExtra(SearchManager.QUERY, query))
            val svc = ShraaAccessibilityService.instance
            if (play && svc != null) {
                svc.searchInApp(yt, query, play = true, alreadySearched = true) { notify(it) }
                ExecResult(true, "YouTube pe $query chala rahi hu.")
            } else ExecResult(true, "YouTube pe $query search kar diya.")
        } else {
            val url = "https://www.youtube.com/results?search_query=" + URLEncoder.encode(query, "UTF-8")
            launch(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            ExecResult(true, "Browser me YouTube search khol diya.")
        }
    }

    private fun mediaControl(cmd: String?): ExecResult {
        val key = when (cmd?.lowercase()?.trim()) {
            "pause", "stop" -> KeyEvent.KEYCODE_MEDIA_PAUSE
            "play", "resume" -> KeyEvent.KEYCODE_MEDIA_PLAY
            "next", "skip" -> KeyEvent.KEYCODE_MEDIA_NEXT
            "previous", "back" -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
            else -> return ExecResult(false, "Ye media command samajh nahi aayi.")
        }
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, key))
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, key))
        return ExecResult(true, "Ho gaya.")
    }

    private fun call(target: String?): ExecResult {
        if (target.isNullOrBlank()) return ExecResult(false, "Kisko call karna hai?")
        if (looksLikeNumber(target)) return dial(target, dialable(target))
        return withContact(target, "call karun") { name, number -> dial(name, number) }
    }

    private fun dial(who: String, number: String): ExecResult {
        val uri = Uri.parse("tel:" + Uri.encode(number))
        return if (has(Manifest.permission.CALL_PHONE)) {
            launch(Intent(Intent.ACTION_CALL, uri))
            ExecResult(true, "$who ko call laga raha hoon.")
        } else {
            launch(Intent(Intent.ACTION_DIAL, uri))
            ExecResult(true, "Dialer khol diya, call button dabao.")
        }
    }

    /**
     * [send] = true: really send (sms directly; other apps by tapping their Send button).
     * [send] = false: only open the chat and type the text.
     */
    private fun sendMessage(app: String?, target: String?, text: String?, send: Boolean): ExecResult {
        val appName = app?.trim().orEmpty()
        val isSms = appName.isBlank() || NameMatch.norm(appName) in SMS_NAMES
        val isWhatsapp = !isSms && NameMatch.rank(appName, "whatsapp") >= 2
        // Instagram, Facebook, Telegram ...: the chat is picked by name inside the app, no phone number needed.
        if (!isSms && !isWhatsapp) return deliver(app, null, target, text, send)
        if (target.isNullOrBlank()) return deliver(app, null, null, text, send)
        if (looksLikeNumber(target)) return deliver(app, dialable(target), null, text, send)
        return withContact(target, if (send) "message bhejun" else "likhun") { _, number ->
            deliver(app, number, null, text, send)
        }
    }

    @Suppress("DEPRECATION")
    private fun smsSend(number: String, body: String) {
        val sm = if (Build.VERSION.SDK_INT >= 31) ctx.getSystemService(android.telephony.SmsManager::class.java)
        else android.telephony.SmsManager.getDefault()
        sm.sendMultipartTextMessage(number, null, sm.divideMessage(body), null, null)
    }

    /** Taps Send (and the contact, if [contact] is given) once the app is ready. False = cannot auto-send. */
    private fun autoSend(pkg: String?, contact: String?): Boolean {
        val svc = ShraaAccessibilityService.instance ?: return false
        if (pkg == null) return false
        svc.sendWhenReady(pkg, contact) { ok ->
            notify(if (ok) "Message bhej diya." else "Send button nahi mila, khud send dabao.")
        }
        return true
    }

    private fun afterLaunch(pkg: String?, contact: String?, send: Boolean, label: String, typed: String): ExecResult {
        if (!send) return ExecResult(true, typed)
        if (autoSend(pkg, contact)) return ExecResult(true, "$label me message bhej rahi hu.")
        return ExecResult(
            true,
            "$label khul gaya. Seedha send karne ke liye Settings me Phone control allow karo. Abhi send dabao.",
            confirm = true
        )
    }

    /** WhatsApp wants the full international number; add the country code to local 10-digit numbers. */
    private fun whatsappNumber(raw: String): String {
        val d = digits(raw)
        val india = Locale.getDefault().country == "IN"
        return when {
            raw.trim().startsWith("+") -> d
            d.length == 10 && india -> "91$d"
            d.length == 11 && d.startsWith("0") && india -> "91" + d.drop(1)
            else -> d
        }
    }

    private fun deliver(app: String?, number: String?, name: String?, text: String?, send: Boolean): ExecResult {
        val body = text.orEmpty()
        val appName = app?.trim().orEmpty()

        if (appName.isBlank() || NameMatch.norm(appName) in SMS_NAMES) {
            val to = number?.let { dialable(it) }.orEmpty()
            if (send && to.isNotBlank() && body.isNotBlank() && has(Manifest.permission.SEND_SMS)) {
                smsSend(to, body)
                return ExecResult(true, "Message bhej diya.", confirm = true)
            }
            launch(Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + number.orEmpty())).putExtra("sms_body", body))
            return if (send && to.isNotBlank() && !has(Manifest.permission.SEND_SMS)) {
                ExecResult(true, "SMS permission nahi hai, isliye message taiyaar kiya. Settings me SMS allow karo, abhi send dabao.", confirm = true)
            } else ExecResult(true, "Message taiyaar hai, send dabao.")
        }

        if (NameMatch.rank(appName, "whatsapp") >= 2) {
            val installed = listOf("com.whatsapp", "com.whatsapp.w4b").filter { isInstalled(it) }
            if (installed.isNotEmpty() && installed.none { connected.isConnected(it) }) return notConnected("WhatsApp")
            val pkg = installed.firstOrNull { connected.isConnected(it) }
            val i = if (number.isNullOrBlank()) {
                Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, body)
            } else {
                val url = "https://wa.me/${whatsappNumber(number)}?text=" + URLEncoder.encode(body, "UTF-8")
                Intent(Intent.ACTION_VIEW, Uri.parse(url))
            }
            pkg?.let { i.setPackage(it) }
            launch(i)
            return afterLaunch(pkg, null, send, "WhatsApp", "WhatsApp me message likh diya.")
        }

        // Any other installed app (Instagram, Facebook, Telegram, ...): hand it the text, then pick the chat by name.
        return withApp(appName, "kholun") { a ->
            launch(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, body).setPackage(a.pkg))
            afterLaunch(a.pkg, name, send, a.label, "${a.label} khul gaya, chat chuno.")
        }
    }

    // =========================================================================================
    // Alarms, reminders, calendar
    // =========================================================================================

    private fun parseTime(t: String?): LocalDateTime? {
        if (t.isNullOrBlank()) return null
        return try {
            LocalDateTime.parse(t)
        } catch (e: Exception) {
            try {
                OffsetDateTime.parse(t).atZoneSameInstant(ZoneId.systemDefault()).toLocalDateTime()
            } catch (e2: Exception) {
                null
            }
        }
    }

    /** AlarmClock only takes hour + minute (the next occurrence), so it is used for the next 24h only. */
    private fun ringAlarm(dt: LocalDateTime, label: String, daily: Boolean = false) {
        val i = Intent(AlarmClock.ACTION_SET_ALARM)
            .putExtra(AlarmClock.EXTRA_HOUR, dt.hour)
            .putExtra(AlarmClock.EXTRA_MINUTES, dt.minute)
            .putExtra(AlarmClock.EXTRA_MESSAGE, label)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        if (daily) i.putExtra(AlarmClock.EXTRA_DAYS, ArrayList(ALL_DAYS))
        launch(i)
    }

    private fun setReminder(text: String?, time: String?): ExecResult {
        val dt = parseTime(time)
        val hoursAway = dt?.let { Duration.between(LocalDateTime.now(), it).toHours() }
        if (dt == null || hoursAway == null || hoursAway !in 0..23) return calendarEvent(text, time)
        ringAlarm(dt, text ?: "SHRAA reminder")
        return ExecResult(true, "Reminder ${SpokenTime.full(dt)} ke liye set ho gaya.", confirm = true)
    }

    private fun setAlarm(time: String?, label: String?, repeat: String?): ExecResult {
        val dt = parseTime(time) ?: return ExecResult(false, "Alarm kis time ka lagana hai?")
        val daily = repeat?.lowercase()?.let { "daily" in it || "roz" in it || "every" in it } == true
        if (!daily && Duration.between(LocalDateTime.now(), dt).toHours() > 23) {
            calendarEvent(label ?: "Alarm", time)
            return ExecResult(
                true, "Ye alarm 24 ghante se zyada door hai, isliye calendar event taiyaar kiya, save dabao.",
                confirm = true
            )
        }
        ringAlarm(dt, label ?: "SHRAA alarm", daily)
        return ExecResult(true, "Alarm ${SpokenTime.full(dt, daily)} ke liye set ho gaya.", confirm = true)
    }

    private fun calendarEvent(title: String?, time: String?): ExecResult {
        val i = Intent(Intent.ACTION_INSERT)
            .setData(CalendarContract.Events.CONTENT_URI)
            .putExtra(CalendarContract.Events.TITLE, title ?: "SHRAA event")
        parseTime(time)?.let { dt ->
            val ms = dt.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
            i.putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, ms)
            i.putExtra(CalendarContract.EXTRA_EVENT_END_TIME, ms + HOUR_MS)
        }
        launch(i)
        return ExecResult(true, "Calendar me event taiyaar hai, save dabao.", confirm = true)
    }

    // =========================================================================================
    // Device controls
    // =========================================================================================

    private fun volume(cmd: String?, which: String?): ExecResult {
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val stream = when (which?.lowercase()?.trim()) {
            "ring", "ringtone" -> AudioManager.STREAM_RING
            "alarm" -> AudioManager.STREAM_ALARM
            else -> AudioManager.STREAM_MUSIC
        }
        val max = am.getStreamMaxVolume(stream)
        val ui = AudioManager.FLAG_SHOW_UI
        val c = cmd?.lowercase()?.trim().orEmpty()
        val pct = c.trimEnd('%').toIntOrNull()
        when {
            pct != null -> am.setStreamVolume(stream, (max * pct.coerceIn(0, 100) / 100f).roundToInt(), ui)
            c in VOLUME_UP -> repeat(3) { am.adjustStreamVolume(stream, AudioManager.ADJUST_RAISE, ui) }
            c in VOLUME_DOWN -> repeat(3) { am.adjustStreamVolume(stream, AudioManager.ADJUST_LOWER, ui) }
            c == "mute" ->
                if (Build.VERSION.SDK_INT >= 23) am.adjustStreamVolume(stream, AudioManager.ADJUST_MUTE, ui)
                else am.setStreamVolume(stream, 0, ui)
            c == "unmute" ->
                if (Build.VERSION.SDK_INT >= 23) am.adjustStreamVolume(stream, AudioManager.ADJUST_UNMUTE, ui)
                else am.setStreamVolume(stream, max / 2, ui)
            c == "max" -> am.setStreamVolume(stream, max, ui)
            c == "min" -> am.setStreamVolume(stream, 0, ui)
            else -> return ExecResult(false, "Volume ka command samajh nahi aaya.")
        }
        val msg = when {
            pct != null -> "Volume $pct percent kar diya."
            c == "mute" -> "Mute kar diya."
            c == "unmute" -> "Unmute kar diya."
            c in VOLUME_DOWN || c == "min" -> "Volume kam kar diya."
            else -> "Volume badha diya."
        }
        return ExecResult(true, msg)
    }

    private fun torch(cmd: String?): ExecResult {
        if (Build.VERSION.SDK_INT < 23) return ExecResult(false, "Is Android version pe torch awaaz se nahi chal sakti.")
        val on = when (cmd?.lowercase()?.trim()) {
            "on", "chalu", "jalao" -> true
            "off", "band", "bujhao" -> false
            else -> return ExecResult(false, "Torch on karni hai ya off?")
        }
        val cm = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val id = cm.cameraIdList.firstOrNull {
            cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        } ?: return ExecResult(false, "Is phone me flash nahi mila.")
        cm.setTorchMode(id, on)
        return ExecResult(true, if (on) "Torch on kar di." else "Torch band kar di.")
    }

    // =========================================================================================
    // Connected apps: media sessions + screen control
    // =========================================================================================

    /**
     * Installs ANY app by name. With a Play Store [pkgHint] from the AI the app's page opens directly,
     * otherwise (or if that page is wrong) the Play Store search is used. Needs Accessibility to tap Install.
     */
    private fun installApp(name: String?, pkgHint: String?): ExecResult {
        if (name.isNullOrBlank()) return ExecResult(false, "Kaun sa app install karna hai?")
        val store = "com.android.vending"
        if (!isInstalled(store)) return ExecResult(false, "Play Store nahi mila.")
        if (!connected.isConnected(store)) return notConnected("Play Store")
        val pkg = pkgHint?.trim()?.takeIf { PACKAGE_NAME.matches(it) }
        if (pkg != null && isInstalled(pkg)) return ExecResult(true, "$name pehle se phone me installed hai.", confirm = true)
        installedApps().firstOrNull { NameMatch.rank(name, it.label) >= 3 }?.let {
            return ExecResult(true, "${it.label} pehle se phone me installed hai.", confirm = true)
        }
        fun openSearch() = launch(
            Intent(Intent.ACTION_VIEW, Uri.parse("market://search?q=" + URLEncoder.encode(name, "UTF-8") + "&c=apps"))
                .setPackage(store)
        )
        if (pkg != null) launch(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$pkg")).setPackage(store))
        else openSearch()
        val svc = ShraaAccessibilityService.instance
            ?: return ExecResult(
                true,
                "Play Store khol diya. Seedha install karne ke liye Settings me Phone control allow karo. Abhi Install khud dabao.",
                confirm = true
            )
        svc.installWhenReady(name, direct = pkg != null, openSearch = { openSearch() }) { msg -> notify(msg) }
        return ExecResult(true, "Play Store me $name dhundh rahi hu.")
    }

    /** play / pause / next ... inside one connected music or video app (or whatever is playing). */
    private fun appControl(app: String?, cmd: String?): ExecResult {
        val c = cmd?.lowercase()?.trim().orEmpty()
        if (c.isEmpty()) return ExecResult(false, "Kya karna hai?")
        if (!MediaAccess.isEnabled(ctx)) {
            return ExecResult(false, "Media control ke liye Notification access chahiye. Settings me Connected Apps se allow karo.")
        }
        if (app.isNullOrBlank()) return mediaOn(null, null, c)
        return withApp(app, "control karun") { a -> mediaOn(a.pkg, a.label, c) }
    }

    private fun mediaOn(pkg: String?, label: String?, cmd: String): ExecResult {
        val who = label ?: "Koi app"
        if (cmd in NOW_PLAYING) {
            val now = MediaAccess.nowPlaying(ctx, pkg)
                ?: return ExecResult(false, "$who me abhi kuch chal nahi raha.")
            return ExecResult(true, "Abhi $now chal raha hai.", confirm = true)
        }
        return when (MediaAccess.control(ctx, pkg, cmd)) {
            MediaAccess.OK -> ExecResult(true, "Ho gaya.")
            MediaAccess.NO_SESSION -> ExecResult(false, "$who me abhi kuch chal nahi raha.")
            MediaAccess.UNKNOWN_COMMAND -> ExecResult(false, "Ye media command samajh nahi aayi.")
            else -> ExecResult(false, "Media control ke liye Notification access chahiye.")
        }
    }

    /** Back / Home / scroll / tap / type on the app that is on screen (needs Accessibility access). */
    private fun uiControl(cmd: String?, clickText: String?, typeText: String?): ExecResult {
        val svc = ShraaAccessibilityService.instance
            ?: return ExecResult(false, "Phone control ke liye Accessibility access chahiye. Settings me Connected Apps se allow karo.")
        val (ok, msg) = svc.perform(cmd, clickText, typeText)
        return ExecResult(ok, msg)
    }

    private fun openSettings(page: String?): ExecResult {
        val action = when (page?.lowercase()?.trim()) {
            "wifi", "wi-fi" -> Settings.ACTION_WIFI_SETTINGS
            "bluetooth" -> Settings.ACTION_BLUETOOTH_SETTINGS
            "display", "brightness" -> Settings.ACTION_DISPLAY_SETTINGS
            "sound", "volume" -> Settings.ACTION_SOUND_SETTINGS
            "battery" -> if (Build.VERSION.SDK_INT >= 22) Settings.ACTION_BATTERY_SAVER_SETTINGS else Settings.ACTION_SETTINGS
            "location" -> Settings.ACTION_LOCATION_SOURCE_SETTINGS
            "apps" -> Settings.ACTION_APPLICATION_SETTINGS
            "airplane" -> Settings.ACTION_AIRPLANE_MODE_SETTINGS
            else -> Settings.ACTION_SETTINGS
        }
        launch(Intent(action))
        return ExecResult(true, "Settings khol diya.")
    }

    private companion object {
        const val MAX_CHOICES = 3
        val PACKAGE_NAME = Regex("^[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z0-9_]+)+$")
        const val SUGGEST_MIN = 0.6
        val NOW_PLAYING = setOf("nowplaying", "now playing", "now_playing", "title", "song")
        const val HOUR_MS = 3_600_000L

        val NAME_JUNK = Regex("[^\\p{L}\\p{N}\\p{M}\\s.'-]")
        val NUMBER_PATTERN = Regex("^[+0-9()\\s-]{3,}$")
        val ORDINALS = listOf("Pehla", "Doosra", "Teesra", "Chautha", "Paanchva")
        val SMS_NAMES = setOf("sms", "message", "messages", "text")
        val VOLUME_UP = setOf("up", "increase", "badhao")
        val VOLUME_DOWN = setOf("down", "decrease", "kam")
        val ALL_DAYS = listOf(
            Calendar.SUNDAY, Calendar.MONDAY, Calendar.TUESDAY, Calendar.WEDNESDAY,
            Calendar.THURSDAY, Calendar.FRIDAY, Calendar.SATURDAY
        )
        val APP_SYNONYMS = mapOf(
            "yt" to "youtube", "insta" to "instagram", "ig" to "instagram", "wa" to "whatsapp",
            "fb" to "facebook", "gpay" to "google pay", "g pay" to "google pay"
        )
    }
}
