package com.abhirha.shraa.actions

import java.text.Normalizer
import java.util.Locale

/**
 * Name matching that survives speech-to-text quirks: Hindi vs English script ("राहुल" = "Rahul"),
 * spelling variants and small recognition errors. Used for contacts and installed apps.
 */
object NameMatch {

    // Devanagari consonants -> rough Latin sound ('C' stands for "ch").
    private val deva: Map<Char, String> = mapOf(
        'क' to "k", 'ख' to "k", 'ग' to "g", 'घ' to "g", 'ङ' to "n",
        'च' to "C", 'छ' to "C", 'ज' to "j", 'झ' to "j", 'ञ' to "n",
        'ट' to "t", 'ठ' to "t", 'ड' to "d", 'ढ' to "d", 'ण' to "n",
        'त' to "t", 'थ' to "t", 'द' to "d", 'ध' to "d", 'न' to "n",
        'प' to "p", 'फ' to "f", 'ब' to "b", 'भ' to "b", 'म' to "m",
        'य' to "y", 'र' to "r", 'ल' to "l", 'व' to "v",
        'श' to "s", 'ष' to "s", 'स' to "s", 'ह' to ""
    )

    private val latinRewrites = listOf(
        "sh" to "s", "ch" to "C", "ph" to "f", "kh" to "k", "gh" to "g", "th" to "t",
        "dh" to "d", "bh" to "b", "jh" to "j", "ck" to "k", "x" to "ks", "q" to "k",
        "w" to "v", "z" to "j", "c" to "k"
    )

    private val ordinals: Map<String, Int> = mapOf(
        "pehla" to 0, "pahla" to 0, "pehle" to 0, "first" to 0, "1" to 0, "ek" to 0, "one" to 0,
        "पहला" to 0, "पहले" to 0, "एक" to 0,
        "doosra" to 1, "dusra" to 1, "doosre" to 1, "second" to 1, "2" to 1, "do" to 1, "two" to 1,
        "दूसरा" to 1, "दूसरे" to 1, "दो" to 1,
        "teesra" to 2, "tisra" to 2, "teesre" to 2, "third" to 2, "3" to 2, "teen" to 2, "three" to 2,
        "तीसरा" to 2, "तीसरे" to 2, "तीन" to 2,
        "chautha" to 3, "chauthe" to 3, "fourth" to 3, "4" to 3, "chaar" to 3, "char" to 3, "four" to 3,
        "चौथा" to 3, "चौथे" to 3, "चार" to 3,
        "paanchva" to 4, "panchva" to 4, "paanchwa" to 4, "fifth" to 4, "5" to 4, "paanch" to 4, "five" to 4,
        "पांचवा" to 4, "पाँचवा" to 4, "पांच" to 4, "पाँच" to 4
    )
    private val lastWords = setOf("last", "aakhri", "akhri", "आखिरी", "आख़िरी")

    private val nonWord = Regex("[^\\p{L}\\p{N}\\p{M}]+")

    /** Lower-case, punctuation -> spaces. Keeps letters (any script), digits and combining marks. */
    fun norm(s: String): String = s.lowercase(Locale.ROOT).replace(nonWord, " ").trim()

    private fun tokens(s: String): List<String> = norm(s).split(' ').filter { it.isNotEmpty() }

    /** Sound-alike key: the same for "Rahul" and "राहुल", "WhatsApp" and "व्हाट्सएप". */
    fun phonetic(word: String): String {
        val decomposed = Normalizer.normalize(word.lowercase(Locale.ROOT), Normalizer.Form.NFD)
        val sb = StringBuilder()
        for (ch in decomposed) {
            val d = deva[ch]
            when {
                d != null -> sb.append(d)
                ch in '\u0900'..'\u097F' -> Unit                // vowels, matras, virama, nukta
                ch in 'a'..'z' || ch in '0'..'9' -> sb.append(ch)
                else -> Unit                                    // accents, punctuation
            }
        }
        var s = sb.toString()
        for ((from, to) in latinRewrites) s = s.replace(from, to)
        if (s.endsWith("y")) s = s.dropLast(1) // "mummy" ~ "मम्मी"
        val out = StringBuilder()
        for (ch in s) {
            if (ch in "aeiouh") continue
            if (out.isNotEmpty() && out.last() == ch) continue
            out.append(ch)
        }
        return out.toString()
    }

    private fun keyMatch(a: String, b: String): Boolean {
        if (a.isEmpty() || b.isEmpty()) return false
        if (a == b) return true
        return minOf(a.length, b.length) >= 3 && editDistance(a, b) <= 1
    }

    private fun editDistance(a: String, b: String): Int {
        val dp = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            var prev = dp[0]
            dp[0] = i
            for (j in 1..b.length) {
                val tmp = dp[j]
                dp[j] = minOf(dp[j] + 1, dp[j - 1] + 1, prev + if (a[i - 1] == b[j - 1]) 0 else 1)
                prev = tmp
            }
        }
        return dp[b.length]
    }

    /**
     * 4 = same name, 3 = every spoken word starts a word of the candidate ("rahul" ~ "Rahul Sharma"),
     * 2 = spoken text is inside the candidate or the whole name sounds the same ("राहुल" ~ "Rahul"),
     * 1 = each spoken word sounds like a word of the candidate, 0 = no match.
     */
    fun rank(query: String, candidate: String): Int {
        val q = norm(query)
        val c = norm(candidate)
        if (q.isEmpty() || c.isEmpty()) return 0
        if (q == c) return 4
        val qt = q.split(' ')
        val ct = c.split(' ')
        if (qt.all { w -> ct.any { it.startsWith(w) } }) return 3
        if (q.length >= 3 && c.contains(q)) return 2
        if (keyMatch(phonetic(q.replace(" ", "")), phonetic(c.replace(" ", "")))) return 2
        val keys = ct.map { phonetic(it) }
        if (qt.all { w -> phonetic(w).let { k -> keys.any { keyMatch(k, it) } } }) return 1
        return 0
    }

    private fun sim(a: String, b: String): Double =
        if (a.isEmpty() || b.isEmpty()) 0.0 else 1.0 - editDistance(a, b).toDouble() / maxOf(a.length, b.length)

    /**
     * 0.0..1.0 "how close is this to what was said", for spelling mistakes and mishearings
     * ("spotfy" ~ "Spotify", "jio savan" ~ "JioSaavn"). Used for "did you mean ...?" suggestions.
     */
    fun similarity(query: String, candidate: String): Double {
        val q = norm(query).replace(" ", "")
        val c = norm(candidate).replace(" ", "")
        if (q.isEmpty() || c.isEmpty()) return 0.0
        var best = sim(q, c)
        val pq = phonetic(q)
        val pc = phonetic(c)
        if (pq.length >= 3 && pc.length >= 3) best = maxOf(best, sim(pq, pc) * 0.95)
        if (q.length >= 3 && c.startsWith(q)) best = maxOf(best, 0.8)
        val ct = norm(candidate).split(' ')
        if (ct.size > 1) {
            for (w in norm(query).split(' ')) for (t in ct) {
                if (w.length >= 3 && t.length >= 3) best = maxOf(best, sim(w, t) * 0.9)
            }
        }
        return best
    }

    private fun tokenMatch(spoken: String, option: String): Boolean {
        if (spoken == option) return true
        if (spoken.length >= 3 && (option.startsWith(spoken) || spoken.startsWith(option))) return true
        return keyMatch(phonetic(spoken), phonetic(option))
    }

    /**
     * Which of [labels] did the user mean by [spoken]? Understands the full name, part of it, or
     * "pehla / doosra / teesra / first / second / 1 / 2 / last". Null if unclear or ambiguous.
     */
    fun pickChoice(spoken: String, labels: List<String>): Int? {
        val st = tokens(spoken)
        if (st.isEmpty() || labels.isEmpty()) return null

        val scores = labels.map { label ->
            val ot = tokens(label)
            if (ot.isEmpty()) 0.0 else ot.count { o -> st.any { tokenMatch(it, o) } }.toDouble() / ot.size
        }
        val best = scores.max()
        if (best > 0.0 && scores.count { it == best } == 1) return scores.indexOf(best)

        if (st.any { it in lastWords }) return labels.lastIndex
        st.firstNotNullOfOrNull { ordinals[it] }?.let { if (it < labels.size) return it }
        return null
    }
}
