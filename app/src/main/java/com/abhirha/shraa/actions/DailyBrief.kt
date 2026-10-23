package com.abhirha.shraa.actions

import android.Manifest
import android.app.AlarmManager
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

/**
 * "Good morning" brief: time, next alarm, calendar events of the next 24 hours and weather.
 * Time and alarm always work (also offline). Calendar needs the Calendar permission; weather needs
 * Location permission + internet (Open-Meteo: free, no key, last known coarse location only).
 * Blocking: call it off the main thread.
 */
class DailyBrief(context: Context) {
    private val ctx = context.applicationContext
    private val http = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .callTimeout(6, TimeUnit.SECONDS)
        .build()

    fun build(): String {
        val notes = mutableListOf<String>() // hints about missing permissions / connectivity
        val parts = mutableListOf("Abhi ${SpokenTime.nowSentence()}", alarmLine())
        calendarLine(notes)?.let { parts += it }
        weatherLine(notes)?.let { parts += it }
        if (notes.isNotEmpty()) parts += notes.joinToString(" ")
        return parts.joinToString(" ")
    }

    private fun granted(p: String) =
        ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED

    private fun localTime(millis: Long): LocalDateTime =
        LocalDateTime.ofInstant(Instant.ofEpochMilli(millis), ZoneId.systemDefault())

    private fun alarmLine(): String {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val next = am.nextAlarmClock ?: return "Koi alarm set nahi hai."
        return "Agla alarm ${SpokenTime.full(localTime(next.triggerTime))} ka hai."
    }

    private fun calendarLine(notes: MutableList<String>): String? {
        if (!granted(Manifest.permission.READ_CALENDAR)) {
            notes += "Calendar ke liye Settings me Calendar permission allow karo."
            return null
        }
        return try {
            val start = System.currentTimeMillis()
            val uri = CalendarContract.Instances.CONTENT_URI.buildUpon()
                .also { ContentUris.appendId(it, start); ContentUris.appendId(it, start + DAY_MS) }
                .build()
            val items = mutableListOf<String>()
            ctx.contentResolver.query(
                uri,
                arrayOf(
                    CalendarContract.Instances.TITLE,
                    CalendarContract.Instances.BEGIN,
                    CalendarContract.Instances.ALL_DAY
                ),
                null, null, CalendarContract.Instances.BEGIN + " ASC"
            )?.use { c ->
                while (c.moveToNext() && items.size < MAX_EVENTS) {
                    val title = c.getString(0)?.takeIf { it.isNotBlank() } ?: "Event"
                    items += if (c.getInt(2) == 1) "$title poora din"
                    else "$title ${SpokenTime.clock(localTime(c.getLong(1)))}"
                }
            }
            if (items.isEmpty()) "Aane wale 24 ghante me calendar me koi event nahi hai."
            else "Aane wale 24 ghante me ${items.size} event hain: " + items.joinToString(", ") + "."
        } catch (e: Exception) {
            null
        }
    }

    private fun weatherLine(notes: MutableList<String>): String? {
        if (!granted(Manifest.permission.ACCESS_COARSE_LOCATION)) {
            notes += "Weather ke liye Settings me Location allow karo."
            return null
        }
        return try {
            val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            val loc = listOf(LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
                .mapNotNull { p -> try { lm.getLastKnownLocation(p) } catch (_: Exception) { null } }
                .maxByOrNull { it.time }
            if (loc == null) {
                notes += "Weather ke liye phone ki location on karke ek baar Maps kholo."
                return null
            }
            val url = "https://api.open-meteo.com/v1/forecast?latitude=${loc.latitude}&longitude=${loc.longitude}" +
                "&current=temperature_2m,weather_code&daily=temperature_2m_max,precipitation_probability_max" +
                "&timezone=auto&forecast_days=1"
            http.newCall(Request.Builder().url(url).build()).execute().use { r ->
                if (!r.isSuccessful) return null
                val o = JSONObject(r.body?.string().orEmpty())
                val cur = o.getJSONObject("current")
                val temp = cur.getDouble("temperature_2m").roundToInt()
                val word = weatherWord(cur.getInt("weather_code"))
                val daily = o.optJSONObject("daily")
                val max = daily?.optJSONArray("temperature_2m_max")?.optDouble(0)
                    ?.takeIf { !it.isNaN() }?.roundToInt()
                val rain = daily?.optJSONArray("precipitation_probability_max")?.optInt(0, -1) ?: -1
                buildString {
                    append("Mausam: $word, abhi $temp degree")
                    if (max != null) append(", aaj zyada se zyada $max degree")
                    append(".")
                    if (rain >= 0) append(" Baarish ki sambhavna $rain percent.")
                }
            }
        } catch (e: IOException) {
            notes += "Weather ke liye internet chahiye."
            null
        } catch (e: Exception) {
            null
        }
    }

    private fun weatherWord(code: Int) = when (code) {
        0 -> "aasman saaf hai"
        1, 2, 3 -> "thode baadal hain"
        45, 48 -> "kohra hai"
        in 51..57 -> "halki boondabaandi hai"
        in 61..67 -> "baarish ho rahi hai"
        in 71..77 -> "barfbaari ho rahi hai"
        in 80..82 -> "baarish ki bauchhar hai"
        in 95..99 -> "toofan ka mausam hai"
        else -> "mausam mila-jula hai"
    }

    private companion object {
        const val DAY_MS = 24L * 60 * 60 * 1000
        const val MAX_EVENTS = 3
    }
}
