package com.abhirha.shraa

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.provider.Settings
import android.speech.SpeechRecognizer
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.abhirha.shraa.actions.ActionExecutor
import com.abhirha.shraa.actions.DailyBrief
import com.abhirha.shraa.actions.ExecResult
import com.abhirha.shraa.actions.LocalIntent
import com.abhirha.shraa.actions.NameMatch
import com.abhirha.shraa.ai.ActionTypes
import com.abhirha.shraa.ai.AiProvider
import com.abhirha.shraa.ai.AiResult
import com.abhirha.shraa.ai.ApiKeyStore
import com.abhirha.shraa.ai.FailureReason
import com.abhirha.shraa.ai.IntentClient
import com.abhirha.shraa.ai.ShraaAction
import com.abhirha.shraa.ai.ShraaPlan
import com.abhirha.shraa.data.HistoryItem
import com.abhirha.shraa.data.HistoryRepository
import com.abhirha.shraa.data.SettingsStore
import com.abhirha.shraa.service.AssistantService
import com.abhirha.shraa.usage.UsageStatsHelper
import com.abhirha.shraa.voice.SpeechInput
import com.abhirha.shraa.voice.TtsManager
import com.abhirha.shraa.voice.WakeWord
import com.abhirha.shraa.voice.WakeWordEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** STANDBY = Background Assistant is ON and only waiting for the wake word "Shraa". */
enum class AssistantState { READY, STANDBY, LISTENING, PROCESSING, EXECUTING, SPEAKING }

/**
 * The one coordinator shared by the UI and the background service:
 * speech -> AI plan -> actions -> speech -> history.
 *
 * Rhythm of a task: SHRAA first says what she is doing, and only when she has finished
 * speaking does the task run. She speaks again only to confirm, to ask, or to report a failure.
 * After a task she rests: only the wake word (or a tap on the orb) starts her again.
 */
object AssistantController {
    private lateinit var app: Application
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    lateinit var settings: SettingsStore private set
    lateinit var keyStore: ApiKeyStore private set
    lateinit var history: HistoryRepository private set
    lateinit var usage: UsageStatsHelper private set
    lateinit var tts: TtsManager private set
    lateinit var speech: SpeechInput private set
    private lateinit var executor: ActionExecutor
    private lateinit var ai: IntentClient
    private lateinit var brief: DailyBrief

    // ---- observable state (read by the UI) ----
    val state = MutableStateFlow(AssistantState.READY)
    val heard = MutableStateFlow("")
    val reply = MutableStateFlow("")
    val online = MutableStateFlow(true)
    val backgroundOn = MutableStateFlow(false)

    /** A readable problem with background listening ("" = fine), shown on the Home screen. */
    val bgStatus = MutableStateFlow("")

    /** Set by AssistantService while it runs, so the controller can pause/resume wake listening. */
    var wakeEngine: WakeWordEngine? = null

    /** True while MainActivity is on screen; the wake pop-up is only shown when it is not. */
    @Volatile var appVisible = false

    // ---- session bookkeeping ----
    private var busy = false
    private var busySince = 0L
    private var lastFinish = 0L

    /** True from "Shraa" until the user goes quiet. */
    private var conversation = false
    private var followUpMode = false

    /** Set while SHRAA waits for the user to pick one of several matching contacts / apps. */
    private var pendingChoice: ExecResult? = null
    private var choiceTries = 0

    /** Context for follow-ups ("ab ... chalao"): the last app used, forgotten after 3 idle minutes. */
    private var lastApp: String? = null
    private var lastTurnAt = 0L

    private const val CONTEXT_MS = 180_000L
    private const val STUCK_MS = 45_000L
    private const val COOLDOWN_MS = 1_500L

    fun init(application: Application) {
        app = application
        settings = SettingsStore(app)
        keyStore = ApiKeyStore(app)
        keyStore.purgeRemovedProviders()
        history = HistoryRepository(app)
        usage = UsageStatsHelper(app)
        tts = TtsManager(app, settings)
        speech = SpeechInput(app, settings)
        executor = ActionExecutor(app)
        executor.notify = { msg -> say(msg) }
        ai = IntentClient(keyStore, settings)
        com.abhirha.shraa.ai.InstalledAppNames.provider = { executor.appLabels() }
        brief = DailyBrief(app)
        backgroundOn.value = settings.backgroundEnabled
        refreshOnline()
    }

    // =========================================================================================
    // Status and permissions
    // =========================================================================================

    fun hasMic(): Boolean =
        ContextCompat.checkSelfPermission(app, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    fun refreshOnline() {
        val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        online.value = if (Build.VERSION.SDK_INT >= 23) {
            val caps = cm.getNetworkCapabilities(cm.activeNetwork)
            caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        } else {
            @Suppress("DEPRECATION")
            cm.activeNetworkInfo?.isConnected == true
        }
    }

    // =========================================================================================
    // Background assistant
    // =========================================================================================

    fun setBackground(on: Boolean, showHints: Boolean = true) {
        settings.backgroundEnabled = on
        backgroundOn.value = on
        val service = Intent(app, AssistantService::class.java)
        try {
            if (on) {
                ContextCompat.startForegroundService(app, service)
                if (state.value == AssistantState.READY) state.value = AssistantState.STANDBY
                // Android 10+ blocks opening apps from the background unless "Display over other apps" is allowed.
                if (showHints && Build.VERSION.SDK_INT >= 29 && !Settings.canDrawOverlays(app)) {
                    Toast.makeText(
                        app,
                        "Background se apps kholne ke liye Settings > 'Display over other apps' allow karo.",
                        Toast.LENGTH_LONG
                    ).show()
                }
            } else {
                bgStatus.value = ""
                app.stopService(service)
                if (state.value == AssistantState.STANDBY) state.value = AssistantState.READY
            }
        } catch (e: Exception) {
            backgroundFailed()
            reply.value = "Background assistant start nahi ho paya."
        }
    }

    /** Called when the app comes to the foreground: restart the service if it was left ON. */
    fun syncBackground() {
        if (!settings.backgroundEnabled) return
        if (hasMic()) setBackground(true, showHints = false) else backgroundFailed()
    }

    private fun backgroundFailed() {
        settings.backgroundEnabled = false
        backgroundOn.value = false
        if (state.value == AssistantState.STANDBY) state.value = AssistantState.READY
    }

    // =========================================================================================
    // Entry points
    // =========================================================================================

    /** Tap on the orb. */
    fun startManualListening() {
        if (busy) return
        if (!hasMic()) { say("Mic permission chahiye."); return }
        if (!speech.isAvailable()) { say("Is phone pe speech recognition available nahi hai."); return }
        busy = true
        busySince = System.currentTimeMillis()
        conversation = false
        pendingChoice = null
        wakeEngine?.pause()
        listenForCommand()
    }

    /** Mic button / orb tapped again while listening: stop right away and go back to rest. */
    fun stopManualListening() {
        if (state.value != AssistantState.LISTENING) return
        speech.stop()
        heard.value = ""
        endConversation()
    }

    /**
     * Called by the wake-word engine. [command] is the text after "Shraa" (may be empty).
     * Returns false if the assistant is busy (the engine then keeps listening).
     */
    fun onWake(command: String): Boolean {
        val now = System.currentTimeMillis()
        // A flag stuck for 45s+ means something went wrong earlier: reset instead of staying deaf.
        if (busy && now - busySince < STUCK_MS) return false
        // Short cooldown so SHRAA's own voice cannot re-trigger her.
        if (!busy && now - lastFinish < COOLDOWN_MS) return false
        busy = true
        busySince = now
        conversation = true
        followUpMode = false
        pendingChoice = null
        reply.value = ""
        showWakePopup()
        if (command.isBlank()) {
            // Like "Hey Google": the pop-up appears and she listens right away, no spoken greeting first.
            listenForCommand()
        } else {
            state.value = AssistantState.PROCESSING
            scope.launch { process(command) }
        }
        return true
    }

    fun testVoice() {
        tts.speak("SHRAA online hai. Main aapki madad ke liye taiyaar hoon.")
    }

    suspend fun testAi(provider: AiProvider): String = ai.test(provider)

    /** Small SHRAA card over whatever is on screen (needs "Display over other apps"). */
    private fun showWakePopup() {
        if (appVisible) return
        // Lock-screen pop-up switched off in Settings: stay completely hidden while the phone is locked.
        val locked = (app.getSystemService(android.content.Context.KEYGUARD_SERVICE) as android.app.KeyguardManager)
            .isKeyguardLocked
        if (locked && !settings.lockScreenPopup) return
        // Without "Display over other apps" Android blocks a pop-up from the background: use a
        // full-screen notification instead (it opens the same card, also over the lock screen).
        if (Build.VERSION.SDK_INT >= 29 && !Settings.canDrawOverlays(app)) postWakeNotification()
        try {
            app.startActivity(
                Intent(app, WakePopupActivity::class.java).addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION or Intent.FLAG_ACTIVITY_SINGLE_TOP
                )
            )
        } catch (_: Exception) {
            // Android refused: SHRAA still works, just without the pop-up.
        }
    }

    private fun postWakeNotification() {
        try {
            val nm = app.getSystemService(android.content.Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            if (Build.VERSION.SDK_INT >= 26) {
                nm.createNotificationChannel(
                    android.app.NotificationChannel("shraa_wake", "SHRAA wake", android.app.NotificationManager.IMPORTANCE_HIGH)
                )
            }
            val pi = android.app.PendingIntent.getActivity(
                app, 7,
                Intent(app, WakePopupActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT
            )
            val n = androidx.core.app.NotificationCompat.Builder(app, "shraa_wake")
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setContentTitle("SHRAA")
                .setContentText("Sun rahi hu…")
                .setPriority(androidx.core.app.NotificationCompat.PRIORITY_MAX)
                .setCategory(androidx.core.app.NotificationCompat.CATEGORY_CALL)
                .setFullScreenIntent(pi, true)
                .setAutoCancel(true)
                .setTimeoutAfter(8000)
                .build()
            nm.notify(7, n)
        } catch (_: Exception) {
            // Notifications blocked: SHRAA still listens, just without the card.
        }
    }

    // =========================================================================================
    // Listening
    // =========================================================================================

    private fun listenForCommand(followUp: Boolean = false) {
        followUpMode = followUp
        state.value = AssistantState.LISTENING
        heard.value = ""
        if (!followUp) reply.value = "" // a new command never shows the previous answer
        speech.listen(
            onPartial = { heard.value = it },
            onFinalResult = { handleTranscript(it) },
            onFail = { code -> speechFailed(code) }
        )
    }

    private fun handleTranscript(text: String) {
        var t = text.trim()
        // During a follow-up the user may say "Shraa ..." again.
        if (followUpMode) t = WakeWord.extractCommand(t, settings.looseWake) ?: t
        when {
            t.isEmpty() ->
                // Woken but nothing followed (likely a false trigger): rest silently.
                if (conversation) endConversation() else speakThenFinish("Kuch sunai nahi diya.")
            conversation && isStopPhrase(t) -> {
                pendingChoice = null
                ai.resetContext()
                lastApp = null
                speakThenFinish("Theek hai.")
            }
            pendingChoice != null -> handleChoice(t)
            else -> scope.launch { process(t) }
        }
    }

    private fun speechFailed(code: Int) {
        if (followUpMode) { endConversation(); return }
        val quiet = code == SpeechRecognizer.ERROR_NO_MATCH || code == SpeechRecognizer.ERROR_SPEECH_TIMEOUT
        if (conversation && quiet) { endConversation(); return } // woken, nothing said: no chatter
        speakThenFinish(
            when (code) {
                SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Kuch sunai nahi diya."
                SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Speech ke liye internet chahiye."
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Mic permission chahiye."
                else -> "Awaaz samajh nahi aayi, dobara bolo."
            }
        )
    }

    private val stopWords = setOf(
        "bas", "bas karo", "bas itna hi", "ok bas", "theek hai bas", "ruko", "rehne do", "kuch nahi",
        "nothing", "cancel", "that's all", "thats all", "thanks", "thank you", "dhanyavad", "dhanyawad",
        "shukriya", "stop listening", "sunna band karo", "no thanks"
    )

    private fun isStopPhrase(t: String): Boolean =
        t.lowercase().trim(' ', '.', '!', ',', '।') in stopWords

    // =========================================================================================
    // Understanding: local parser first when offline, AI otherwise
    // =========================================================================================

    private suspend fun process(text: String) {
        try {
            understand(text)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            speakThenFinish("Kuch gadbad ho gayi. Dobara bolo.")
        }
    }

    private suspend fun understand(text: String) {
        heard.value = text
        refreshOnline()
        state.value = AssistantState.PROCESSING
        if (System.currentTimeMillis() - lastTurnAt > CONTEXT_MS) lastApp = null

        // The offline parser understands the basics without internet; the morning brief is always local.
        val local = LocalIntent.parse(text, lastApp)
        if (local != null && (!online.value || LocalIntent.isBrief(text))) {
            handlePlan(text, local)
            return
        }
        if (!online.value) {
            val msg = "Internet nahi hai, aur ye command offline samajh nahi aayi. " +
                "Alarm, volume, torch, app kholna, call, settings aur good morning offline chalte hain."
            log(text, "Offline", msg, false)
            speakThenFinish(msg)
            return
        }
        when (val r = ai.interpret(text)) {
            is AiResult.Success -> handlePlan(text, r.plan)
            is AiResult.Failure -> {
                if (local != null) { // every AI failed, but the offline parser still understood it
                    handlePlan(text, local)
                    return
                }
                val msg = failureMessage(r.reason)
                log(text, "AI request", msg + (r.detail?.let { " [$it]" } ?: ""), false)
                speakThenFinish(msg)
                r.detail?.let { reply.value = "$msg\n$it" }
            }
        }
    }

    private suspend fun handlePlan(text: String, plan: ShraaPlan) {
        val raw = plan.actions.filter { it.type != ActionTypes.ANSWER_ONLY }
        // PLAY_MEDIA opens the app itself: drop a separate OPEN_APP for the same app, and make sure
        // the song is searched in the app that was named (not a default).
        val actions = raw.mapIndexedNotNull { i, a ->
            val next = raw.getOrNull(i + 1)
            val prev = raw.getOrNull(i - 1)
            when {
                a.type == ActionTypes.OPEN_APP && next != null && next.type == ActionTypes.PLAY_MEDIA &&
                    (next.app.isNullOrBlank() || next.app.equals(a.app ?: a.target, ignoreCase = true)) -> null
                a.type == ActionTypes.PLAY_MEDIA && a.app.isNullOrBlank() && prev != null && prev.type == ActionTypes.OPEN_APP ->
                    a.copy(app = prev.app ?: prev.target)
                else -> a
            }
        }
        if (plan.needsClarification || actions.isEmpty()) {
            log(text, if (plan.needsClarification) "Clarification" else "Answer", plan.speech, true)
            // Only a clarifying question keeps the mic open; everything else rests.
            speakThenFinish(plan.speech, continueChat = plan.needsClarification || settings.followUp)
            return
        }
        lastTurnAt = System.currentTimeMillis()
        actions.mapNotNull { it.app }.lastOrNull()?.let { lastApp = it }

        // 1) say what she is doing, 2) only then do it.
        val ack = plan.speech.ifBlank { "Haan, main kar rahi hu." }
        state.value = AssistantState.SPEAKING
        reply.value = ack
        tts.speak(ack) { scope.launch { runActions(text, actions) } }
    }

    // =========================================================================================
    // Doing
    // =========================================================================================

    private suspend fun runActions(text: String, actions: List<ShraaAction>) {
        try {
            execute(text, actions)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            speakThenFinish("Kuch gadbad ho gayi. Dobara bolo.")
        }
    }

    private suspend fun execute(text: String, actions: List<ShraaAction>) {
        state.value = AssistantState.EXECUTING
        val results = mutableListOf<ExecResult>()
        for ((i, a) in actions.withIndex()) {
            if (i > 0) delay(1200) // give the previous app time to open
            val r = if (a.type == ActionTypes.DAILY_BRIEF) {
                ExecResult(true, withContext(Dispatchers.IO) { brief.build() }, confirm = true)
            } else {
                executor.run(a)
            }
            results += r
            if (r.choices.isNotEmpty()) break // several contacts/apps match: ask before doing anything
        }

        val ask = results.lastOrNull()?.takeIf { it.choices.isNotEmpty() }
        if (ask != null) {
            log(text, "Confirm", ask.message, true)
            askChoice(ask)
            return
        }

        val failed = results.firstOrNull { !it.ok }
        log(
            text,
            actions.joinToString(" → ") { describe(it) },
            results.joinToString("; ") { it.message },
            failed == null
        )
        // The acknowledgement was already spoken: speak again only on failure or to confirm
        // (alarm set, reminder saved, the good-morning brief...).
        val confirmation = results.filter { it.ok && it.confirm }.joinToString(" ") { it.message }
        when {
            failed != null -> speakThenFinish(failed.message)
            confirmation.isNotBlank() -> speakThenFinish(confirmation, continueChat = settings.followUp)
            settings.followUp -> followUpListen()
            else -> endConversation()
        }
    }

    /** Reads the matching contacts / apps aloud and listens once for the user's pick. */
    private fun askChoice(r: ExecResult) {
        pendingChoice = r
        choiceTries = 0
        speakThenFinish(r.message, continueChat = true)
    }

    /** The user said "pehla", "doosra" or a name: run exactly that option. */
    private fun handleChoice(answer: String) {
        val pending = pendingChoice ?: return
        val index = NameMatch.pickChoice(answer, pending.choices.map { it.label })
        when {
            index != null -> {
                pendingChoice = null
                state.value = AssistantState.EXECUTING
                val chosen = pending.choices[index]
                val r = try {
                    chosen.perform()
                } catch (e: Exception) {
                    ExecResult(false, "Ye kaam nahi ho paya.")
                }
                log(answer, "Choice: ${chosen.label}", r.message, r.ok)
                if (r.choices.isNotEmpty()) askChoice(r) else speakThenFinish(r.message)
            }
            choiceTries < 1 -> {
                choiceTries++
                speakThenFinish("Samajh nahi aaya. Pehla, doosra, ya poora naam bolo.", continueChat = true)
            }
            else -> {
                pendingChoice = null
                speakThenFinish("Theek hai, cancel kar diya.")
            }
        }
    }

    // =========================================================================================
    // Speaking and resting
    // =========================================================================================

    private fun say(msg: String) {
        reply.value = msg
        tts.speak(msg)
    }

    /**
     * Speaks [msg], then rests: only "Shraa" wakes her again. With [continueChat] she listens once
     * more for the answer first (a clarifying question), then rests.
     */
    private fun speakThenFinish(msg: String, continueChat: Boolean = false) {
        reply.value = msg
        state.value = AssistantState.SPEAKING
        tts.speak(msg) {
            if (continueChat) followUpListen() else endConversation()
        }
    }

    /** Stay awake a moment for the next command, no "Shraa" needed. */
    private fun followUpListen() {
        conversation = true
        scope.launch {
            delay(400) // let the speaker tail die out so SHRAA does not hear herself
            busySince = System.currentTimeMillis()
            listenForCommand(followUp = true)
        }
    }

    /** Back to idle. */
    private fun endConversation() {
        conversation = false
        followUpMode = false
        pendingChoice = null
        lastFinish = System.currentTimeMillis()
        state.value = if (backgroundOn.value) AssistantState.STANDBY else AssistantState.READY
        busy = false
        if (backgroundOn.value) wakeEngine?.resume()
    }

    // =========================================================================================
    // Helpers
    // =========================================================================================

    private fun describe(a: ShraaAction) =
        listOfNotNull(a.type, a.app, a.query ?: a.target).joinToString(" ")

    private fun failureMessage(r: FailureReason) = when (r) {
        FailureReason.NO_API_KEY -> "Pehle Settings mein koi AI API key daalo."
        FailureReason.NO_INTERNET -> "Internet nahi hai."
        FailureReason.AUTH -> "AI API key galat lag rahi hai."
        FailureReason.RATE_LIMIT -> "Thodi der baad try karo."
        FailureReason.SERVER -> "AI abhi busy hai, thodi der baad dobara bolo."
        else -> "Kuch gadbad ho gayi. Dobara bolo."
    }

    private fun log(command: String, action: String, result: String, ok: Boolean) {
        history.add(HistoryItem(System.currentTimeMillis(), command, action, result, ok))
    }
}
