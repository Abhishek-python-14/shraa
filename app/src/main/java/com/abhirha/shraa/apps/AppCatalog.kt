package com.abhirha.shraa.apps

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Build
import java.util.Locale

enum class AppCategory(val label: String) {
    MUSIC("Music"), VIDEO("Video"), GAME("Games"), SOCIAL("Social"), OTHER("Other")
}

data class InstalledApp(val label: String, val pkg: String, val category: AppCategory)

/** Every app with a launcher icon on this phone, sorted A-Z, with a best-effort category. */
object AppCatalog {

    fun load(context: Context): List<InstalledApp> {
        val ctx = context.applicationContext
        val pm = ctx.packageManager
        val main = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(main, 0)
            .asSequence()
            .filter { it.activityInfo.packageName != ctx.packageName }
            .distinctBy { it.activityInfo.packageName }
            .map {
                val label = it.loadLabel(pm).toString()
                val info = it.activityInfo.applicationInfo
                InstalledApp(label, it.activityInfo.packageName, categoryOf(info, label))
            }
            .sortedBy { it.label.lowercase(Locale.ROOT) }
            .toList()
    }

    private val musicWords = listOf(
        "spotify", "music", "gaana", "saavn", "wynk", "hungama", "resso", "audio", "podcast", "radio", "fm"
    )
    private val videoWords = listOf(
        "youtube", "netflix", "video", "hotstar", "primevideo", "mxplayer", "vlc", "jiocinema", "zee5",
        "sonyliv", "movie", "tv", "twitch"
    )
    private val gameWords = listOf(
        "game", "pubg", "bgmi", "freefire", "ludo", "candy", "clash", "supercell", "minecraft", "roblox",
        "ludoking", "carrom", "chess", "subway", "pokemon"
    )
    private val socialWords = listOf(
        "whatsapp", "instagram", "facebook", "telegram", "snapchat", "twitter", "linkedin", "reddit",
        "sharechat", "discord", "signal", "messenger", "threads"
    )

    private fun categoryOf(info: ApplicationInfo, label: String): AppCategory {
        if (Build.VERSION.SDK_INT >= 26) {
            when (info.category) {
                ApplicationInfo.CATEGORY_AUDIO -> return AppCategory.MUSIC
                ApplicationInfo.CATEGORY_VIDEO -> return AppCategory.VIDEO
                ApplicationInfo.CATEGORY_GAME -> return AppCategory.GAME
                ApplicationInfo.CATEGORY_SOCIAL -> return AppCategory.SOCIAL
            }
        }
        val key = (info.packageName + " " + label).lowercase(Locale.ROOT)
        return when {
            socialWords.any { it in key } -> AppCategory.SOCIAL
            musicWords.any { it in key } -> AppCategory.MUSIC
            videoWords.any { it in key } -> AppCategory.VIDEO
            gameWords.any { it in key } -> AppCategory.GAME
            else -> AppCategory.OTHER
        }
    }
}
