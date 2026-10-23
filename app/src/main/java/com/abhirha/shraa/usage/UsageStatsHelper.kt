package com.abhirha.shraa.usage

import android.app.AppOpsManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import java.util.Calendar

data class AppUsage(val packageName: String, val label: String, val millis: Long)

data class ScreenTimeData(
    val totalToday: Long,
    /** Yesterday up to the same time of day, so the comparison is fair. */
    val totalYesterdaySameTime: Long,
    val topApps: List<AppUsage>
)

data class DayUsage(val dayStart: Long, val millis: Long)

/**
 * Screen time from Android's official UsageStatsManager (the user must grant Usage Access).
 * Counts foreground-app time only (launcher and System UI excluded). Never fabricates data.
 */
class UsageStatsHelper(context: Context) {
    private val ctx = context.applicationContext
    private val usm get() = ctx.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager

    @Suppress("DEPRECATION")
    fun hasPermission(): Boolean {
        val ops = ctx.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = if (Build.VERSION.SDK_INT >= 29) {
            ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName)
        } else {
            ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName)
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    /** Today's total, yesterday's total up to now, and the top 3 apps. Null without permission. */
    fun load(): ScreenTimeData? {
        if (!hasPermission()) return null
        val now = System.currentTimeMillis()
        val startToday = startOfDay(0)
        val today = foreground(startToday, now)
        val yesterday = foreground(startToday - DAY_MS, now - DAY_MS)
        return ScreenTimeData(today.values.sum(), yesterday.values.sum(), ranked(today, 3))
    }

    /** Foreground time per day for the last [days] days, oldest first (today = last, partial). */
    fun lastDays(days: Int = 7): List<DayUsage>? {
        if (!hasPermission()) return null
        val now = System.currentTimeMillis()
        return (days - 1 downTo 0).map { back ->
            val start = startOfDay(back)
            val end = if (back == 0) now else startOfDay(back - 1)
            DayUsage(start, foreground(start, end).values.sum())
        }
    }

    /** Most used apps over the last [days] days (1 = today), most used first. */
    fun topApps(days: Int, limit: Int): List<AppUsage>? {
        if (!hasPermission()) return null
        return ranked(foreground(startOfDay(days - 1), System.currentTimeMillis()), limit)
    }

    // ---------- helpers ----------

    /** Foreground millis per package in [start, end), without the launcher and System UI. */
    private fun foreground(start: Long, end: Long): Map<String, Long> {
        val skip = skipPackages()
        return usm.queryAndAggregateUsageStats(start, end)
            .filterKeys { it !in skip }
            .mapValues { it.value.totalTimeInForeground }
    }

    private fun ranked(usage: Map<String, Long>, limit: Int): List<AppUsage> =
        usage.filterValues { it > 0 }
            .entries.sortedByDescending { it.value }
            .take(limit)
            .map { AppUsage(it.key, label(it.key), it.value) }

    /** Midnight [daysBack] days ago (0 = today). */
    private fun startOfDay(daysBack: Int): Long = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        add(Calendar.DAY_OF_YEAR, -daysBack)
    }.timeInMillis

    private fun skipPackages(): Set<String> {
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val pkg = ctx.packageManager.resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY)
            ?.activityInfo?.packageName
        return setOfNotNull(pkg, "com.android.systemui")
    }

    private fun label(pkg: String): String = try {
        val pm = ctx.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    } catch (e: Exception) {
        pkg
    }

    private companion object {
        const val DAY_MS = 24L * 60 * 60 * 1000
    }
}
