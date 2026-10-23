package com.abhirha.shraa.actions

import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

/** Hinglish spoken forms of times and dates, used for confirmations ("kal subah 6 baje"). */
object SpokenTime {
    fun part(hour: Int) = when (hour) {
        in 4..11 -> "subah"
        in 12..15 -> "dopahar"
        in 16..19 -> "shaam"
        else -> "raat"
    }

    private fun h12(hour: Int) = (hour + 11) % 12 + 1

    /** "6 baje" / "5 baj kar 30 minute". */
    fun clock(dt: LocalDateTime): String =
        if (dt.minute == 0) "${h12(dt.hour)} baje" else "${h12(dt.hour)} baj kar ${dt.minute} minute"

    /** "subah ke 6 baje hain." / "shaam ke 5 baj kar 30 minute ho rahe hain." */
    fun nowSentence(now: LocalDateTime = LocalDateTime.now()): String =
        if (now.minute == 0) "${part(now.hour)} ke ${h12(now.hour)} baje hain."
        else "${part(now.hour)} ke ${h12(now.hour)} baj kar ${now.minute} minute ho rahe hain."

    /** "kal subah 6 baje", "aaj shaam 5 baj kar 30 minute", "roz subah 6 baje". */
    fun full(dt: LocalDateTime, daily: Boolean = false, now: LocalDateTime = LocalDateTime.now()): String {
        val day = if (daily) "roz" else when (ChronoUnit.DAYS.between(now.toLocalDate(), dt.toLocalDate())) {
            0L -> "aaj"
            1L -> "kal"
            2L -> "parso"
            else -> "${dt.dayOfMonth}/${dt.monthValue}"
        }
        return "$day ${part(dt.hour)} ${clock(dt)}"
    }
}
