package com.abhirha.shraa.service

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.abhirha.shraa.apps.ConnectedAppsStore

/**
 * Lets SHRAA press Back/Home, scroll, tap a button by its text and type into the focused field of
 * the app that is on screen. It only acts inside CONNECTED apps (Back/Home/Recents/Notifications
 * work anywhere). It never reads or stores screen content.
 */
class ShraaAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    /** Returns (ok, short spoken message). */
    fun perform(cmd: String?, clickText: String?, typeText: String?): Pair<Boolean, String> {
        val c = cmd?.lowercase()?.trim().orEmpty()
        when (c) {
            "back" -> return global(GLOBAL_ACTION_BACK, "Peeche aa gayi.")
            "home" -> return global(GLOBAL_ACTION_HOME, "Home pe aa gayi.")
            "recents" -> return global(GLOBAL_ACTION_RECENTS, "Recent apps khol diye.")
            "notifications" -> return global(GLOBAL_ACTION_NOTIFICATIONS, "Notifications khol di.")
            "quicksettings" -> return global(GLOBAL_ACTION_QUICK_SETTINGS, "Quick settings khol di.")
            "lock" ->
                return if (Build.VERSION.SDK_INT >= 28) global(GLOBAL_ACTION_LOCK_SCREEN, "Phone lock kar diya.")
                else false to "Is Android version pe lock nahi ho sakta."
            "screenshot" ->
                return if (Build.VERSION.SDK_INT >= 28) global(GLOBAL_ACTION_TAKE_SCREENSHOT, "Screenshot le liya.")
                else false to "Is Android version pe screenshot nahi ho sakta."
        }

        val root = rootInActiveWindow ?: return false to "Screen padh nahi payi."
        val pkg = root.packageName?.toString()
        if (pkg != null && pkg != packageName && !ConnectedAppsStore(this).isConnected(pkg)) {
            return false to "Ye app SHRAA se connected nahi hai. Settings me connect karo."
        }

        return when (c) {
            "scroll_down", "scroll down", "niche" -> scroll(root, true)
            "scroll_up", "scroll up", "upar" -> scroll(root, false)
            "click", "tap", "press" -> click(root, clickText)
            "type", "write" -> type(root, typeText)
            else -> false to "Ye phone control command samajh nahi aayi."
        }
    }

    /**
     * After a share/chat screen was opened in [pkg]: optionally tap [contact] in the list, then tap
     * the Send button. Retries for about 14 seconds while the app loads. [onDone] runs on the main thread.
     */
    fun sendWhenReady(pkg: String, contact: String?, onDone: (Boolean) -> Unit) {
        if (!ConnectedAppsStore(this).isConnected(pkg)) { onDone(false); return }
        val h = Handler(Looper.getMainLooper())
        val who = contact?.takeIf { it.isNotBlank() }
        var contactDone = who == null
        var tries = 0
        val step = object : Runnable {
            override fun run() {
                tries++
                val root = rootInActiveWindow
                if (root != null && root.packageName?.toString() == pkg) {
                    if (!contactDone && who != null) {
                        if (tapText(root, who)) contactDone = true
                    } else if (tapSend(root)) {
                        onDone(true)
                        return
                    }
                }
                if (tries >= 20) { onDone(false); return }
                h.postDelayed(this, 700)
            }
        }
        h.postDelayed(step, 1500)
    }

    /**
     * Installs ANY app by name from the Play Store.
     * [direct] = the app's own Play Store page is open (package known): just tap Install; if that page does not
     * show up, [openSearch] opens the Play Store search instead. Search mode: tap the result whose title is
     * closest to [appName] (spelling-tolerant), then tap Install. [onDone] gets the sentence to speak.
     */
    fun installWhenReady(appName: String, direct: Boolean, openSearch: () -> Unit, onDone: (String) -> Unit) {
        val store = "com.android.vending"
        if (!ConnectedAppsStore(this).isConnected(store)) { onDone("Play Store connected nahi hai."); return }
        val h = Handler(Looper.getMainLooper())
        var onPage = direct          // true once the app's own page is (assumed) open
        var tries = 0
        var searchOpened = !direct
        val step = object : Runnable {
            override fun run() {
                tries++
                val root = rootInActiveWindow
                if (root != null && root.packageName?.toString() == store) {
                    if (!onPage) {
                        if (tapBestResult(root, appName)) onPage = true
                    } else if (tapLabel(root, INSTALL_WORDS)) {
                        // A confirmation (Accept / Install anyway) sometimes follows: press it, then report.
                        h.postDelayed({
                            val r = rootInActiveWindow
                            if (r != null && r.packageName?.toString() == store) tapLabel(r, DIALOG_WORDS)
                            onDone("Install shuru kar diya.")
                        }, 1500)
                        return
                    } else if (hasLabel(root, INSTALLED_WORDS)) {
                        onDone("Ye app pehle se installed hai.")
                        return
                    }
                }
                // Direct page never showed an Install/Open button: that package was wrong, search by name.
                if (direct && !searchOpened && tries >= 10) {
                    searchOpened = true
                    onPage = false
                    openSearch()
                }
                if (tries >= 40) {
                    onDone(if (onPage) "Install button nahi mila, Play Store me khud dabao." else "$appName Play Store me nahi mila, khud dhundho.")
                    return
                }
                h.postDelayed(this, 700)
            }
        }
        h.postDelayed(step, 2000)
    }

    private fun collectTexts(n: AccessibilityNodeInfo?, out: MutableList<AccessibilityNodeInfo>, depth: Int = 0) {
        if (n == null || depth > 30 || out.size > 400) return
        if (!n.text.isNullOrBlank()) out += n
        for (i in 0 until n.childCount) collectTexts(n.getChild(i), out, depth + 1)
    }

    /** Taps the search result whose title is closest to [name]. */
    private fun tapBestResult(root: AccessibilityNodeInfo, name: String): Boolean {
        val nodes = ArrayList<AccessibilityNodeInfo>()
        collectTexts(root, nodes)
        val ranked = nodes
            .map { it to com.abhirha.shraa.actions.NameMatch.similarity(name, it.text?.toString().orEmpty()) }
            .filter { it.second >= 0.6 && (it.first.text?.length ?: 0) <= 60 }
            .sortedByDescending { it.second }
        for ((node, _) in ranked) {
            if (clickableOf(node)?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true) return true
        }
        return false
    }

    // =========================================================================================
    // Search / play inside ANY app, by operating its own screen
    // =========================================================================================

    /**
     * Types [query] into the search box of [pkg] (the app must already be opening), submits it and, with
     * [play], opens the first matching result (a song starts playing in most music apps).
     * [alreadySearched] = the app already shows the results (e.g. YouTube opened by a search intent):
     * only the "open first result" part runs. [onDone] gets the sentence to speak.
     */
    fun searchInApp(pkg: String, query: String, play: Boolean, alreadySearched: Boolean, onDone: (String) -> Unit) {
        if (!ConnectedAppsStore(this).isConnected(pkg)) { onDone("Ye app SHRAA se connected nahi hai."); return }
        val h = Handler(Looper.getMainLooper())
        var phase = if (alreadySearched) 2 else 0 // 0 find box and type, 1 submit, 2 open result, 3 press Play
        var tries = 0
        var phaseTries = 0
        var fieldBottom = 0
        val step = object : Runnable {
            override fun run() {
                tries++
                phaseTries++
                val root = rootInActiveWindow
                if (root != null && root.packageName?.toString() == pkg) {
                    when (phase) {
                        0 -> {
                            val field = findFirst(root) { it.isEditable && it.isVisibleToUser }
                            if (field != null) {
                                fieldBottom = boundsOf(field).bottom
                                if (typeInto(field, query)) { phase = 1; phaseTries = 0 }
                            } else if (phaseTries % 3 == 1) {
                                tapSearchIcon(root) // the box shows up on the next tick
                            }
                        }
                        1 -> {
                            submitSearch(root, query)
                            phase = 2
                            phaseTries = 0
                            if (!play) { onDone("Search kar diya."); return }
                        }
                        2 -> if (phaseTries >= 3) {
                            if (pickResult(root, query, fieldBottom)) { phase = 3; phaseTries = 0 }
                            else if (phaseTries >= 9) { onDone("Search ho gaya, result khud chuno."); return }
                        }
                        3 -> if (phaseTries >= 2) {
                            tapLabel(root, PLAY_WORDS)
                            onDone("Chalane ki koshish ki.")
                            return
                        }
                    }
                }
                if (phase == 0 && phaseTries >= 14) { onDone("Is app me search box nahi mila, khud search karo."); return }
                if (tries >= 60) { onDone("Search poora nahi ho paya."); return }
                h.postDelayed(this, 700)
            }
        }
        h.postDelayed(step, 1800)
    }

    private fun boundsOf(n: AccessibilityNodeInfo): Rect = Rect().also { n.getBoundsInScreen(it) }

    private fun findFirst(n: AccessibilityNodeInfo?, depth: Int = 0, pred: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        if (n == null || depth > 30) return null
        if (pred(n)) return n
        for (i in 0 until n.childCount) findFirst(n.getChild(i), depth + 1, pred)?.let { return it }
        return null
    }

    private fun typeInto(field: AccessibilityNodeInfo, text: String): Boolean {
        field.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        field.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) }
        return field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    private fun tapSearchIcon(root: AccessibilityNodeInfo): Boolean {
        val hit = findFirst(root) { n ->
            val label = (n.contentDescription ?: n.text)?.toString()?.trim()?.lowercase().orEmpty()
            label.length in 1..30 && SEARCH_HINTS.any { it in label } && clickableOf(n) != null
        } ?: return false
        return clickableOf(hit)?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
    }

    /** Enter key if the box supports it, else a Search/Go button, else the first suggestion with the text. */
    private fun submitSearch(root: AccessibilityNodeInfo, query: String) {
        val field = findFirst(root) { it.isEditable && it.isVisibleToUser }
        if (Build.VERSION.SDK_INT >= 30 && field != null &&
            field.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
        ) return
        if (tapLabel(root, SEARCH_WORDS)) return
        val q = query.lowercase()
        val suggestion = findFirst(root) { n ->
            !n.isEditable && n.text?.toString()?.lowercase()?.contains(q) == true && clickableOf(n) != null
        }
        clickableOf(suggestion)?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    /** Opens the first result below the search bar whose text matches most words of [query]. */
    private fun pickResult(root: AccessibilityNodeInfo, query: String, fieldBottom: Int): Boolean {
        val nodes = ArrayList<AccessibilityNodeInfo>()
        collectTexts(root, nodes)
        val q = query.lowercase().trim()
        val words = q.split(Regex("\\s+")).filter { it.length >= 2 }
        if (words.isEmpty()) return false
        // The search bar itself (the box, or any text equal to the query) is never a result.
        var barBottom = fieldBottom
        for (n in nodes) if (n.isEditable || n.text?.toString()?.trim()?.lowercase() == q) barBottom = maxOf(barBottom, boundsOf(n).bottom)
        val hit = nodes
            .filter { n ->
                val t = n.text?.toString()?.lowercase().orEmpty()
                !n.isEditable && t.length in 2..90 && t != q && boundsOf(n).top >= barBottom &&
                    words.count { it in t } * 2 >= words.size
            }
            .sortedBy { boundsOf(it).top }
            .firstOrNull { clickableOf(it) != null } ?: return false
        return clickableOf(hit)?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
    }

    private fun labelOf(n: AccessibilityNodeInfo) =
        (n.contentDescription ?: n.text)?.toString()?.trim()?.lowercase().orEmpty()

    private fun tapLabel(root: AccessibilityNodeInfo, words: List<String>): Boolean {
        for (w in words) {
            val hit = root.findAccessibilityNodeInfosByText(w).firstOrNull { labelOf(it) in words && clickableOf(it) != null } ?: continue
            if (clickableOf(hit)?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true) return true
        }
        return false
    }

    private fun hasLabel(root: AccessibilityNodeInfo, words: List<String>): Boolean =
        words.any { w -> root.findAccessibilityNodeInfosByText(w).any { labelOf(it) in words } }

    private fun clickableOf(n: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        var node = n
        while (node != null && !node.isClickable) node = node.parent
        return node
    }

    private fun tapText(root: AccessibilityNodeInfo, text: String): Boolean {
        val node = root.findAccessibilityNodeInfosByText(text).firstNotNullOfOrNull { clickableOf(it) } ?: return false
        return node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    private fun tapSend(root: AccessibilityNodeInfo): Boolean {
        for (word in SEND_WORDS) {
            val hit = root.findAccessibilityNodeInfosByText(word).firstOrNull { n ->
                val label = (n.contentDescription ?: n.text)?.toString()?.trim()?.lowercase().orEmpty()
                label in SEND_WORDS && clickableOf(n) != null
            } ?: continue
            if (clickableOf(hit)?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true) return true
        }
        return false
    }

    private fun global(action: Int, ok: String): Pair<Boolean, String> =
        if (performGlobalAction(action)) true to ok else false to "Ye kaam nahi ho paya."

    private fun scroll(root: AccessibilityNodeInfo, forward: Boolean): Pair<Boolean, String> {
        val node = findScrollable(root) ?: return false to "Yahan scroll hone wali cheez nahi mili."
        val action = if (forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
        else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        return if (node.performAction(action)) true to (if (forward) "Neeche scroll kiya." else "Upar scroll kiya.")
        else false to "Aur scroll nahi ho sakta."
    }

    private fun findScrollable(n: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (n.isScrollable) return n
        for (i in 0 until n.childCount) {
            val child = n.getChild(i) ?: continue
            findScrollable(child)?.let { return it }
        }
        return null
    }

    private fun click(root: AccessibilityNodeInfo, text: String?): Pair<Boolean, String> {
        if (text.isNullOrBlank()) return false to "Kispe tap karna hai?"
        var node: AccessibilityNodeInfo? = root.findAccessibilityNodeInfosByText(text).firstOrNull()
            ?: return false to "$text screen pe nahi mila."
        while (node != null && !node.isClickable) node = node.parent
        return if (node != null && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) true to "$text daba diya."
        else false to "$text dab nahi paya."
    }

    private fun type(root: AccessibilityNodeInfo, text: String?): Pair<Boolean, String> {
        if (text.isNullOrBlank()) return false to "Kya likhna hai?"
        val field = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            ?: return false to "Koi text box select nahi hai."
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return if (field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) true to "Likh diya."
        else false to "Likh nahi paya."
    }

    companion object {
        private val INSTALL_WORDS = listOf("install", "इंस्टॉल करें", "इंस्टॉल")
        private val DIALOG_WORDS = listOf("accept", "install anyway")
        private val INSTALLED_WORDS = listOf("open", "play", "खोलें", "uninstall")
        private val PLAY_WORDS = listOf("play", "play now")
        private val SEARCH_WORDS = listOf("search", "go", "खोजें")
        private val SEARCH_HINTS = listOf("search", "खोज", "सर्च", "find")
        private val SEND_WORDS = listOf("send", "send message", "भेजें", "भेजे")

        @Volatile
        var instance: ShraaAccessibilityService? = null

        fun isEnabled(ctx: Context): Boolean {
            val me = ComponentName(ctx, ShraaAccessibilityService::class.java).flattenToString()
            val list = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            return list?.split(':')?.any { it.equals(me, ignoreCase = true) } == true
        }
    }
}
