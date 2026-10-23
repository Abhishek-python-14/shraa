package com.abhirha.shraa.voice

import java.util.Locale

/**
 * The wake phrase is "Shraa suno" (like "Hey Google"). Only that phrase, at the very START of an
 * utterance, wakes the assistant; the text after it (if any) is returned as the command.
 * "Shraa" alone, or anything else, does NOT wake her, so normal talking, TV or noise stay ignored.
 *
 * Strict mode (default) accepts only spellings of the name itself (shra, shraa, shraaa, श्रा ...).
 * Loose mode (Settings) also accepts common mishearings such as "sara suno".
 */
object WakeWord {
    private val strictLatin = Regex("^(shrr?|srr?)a{1,4}h?$")          // shra, shraa, shrraa, sra, srah ...
    private val strictDeva = Regex("^श्रा+ह?$|^श्रआ$")                    // श्रा, श्राा, श्राह
    private val mergedLatin = Regex("^(shrr?|srr?)a{1,4}h?(suno|sunno|sunoo|suniye|sun)$") // "shraasuno"
    private val loose = setOf(
        "sara", "sarah", "sahra", "shara", "sharaa", "shira", "saraa",
        "सारा", "सरा", "शरा", "शारा"
    )
    private val sunoWords = setOf(
        "suno", "sunno", "sunoo", "sun", "suniye", "suniyo", "suneeye",
        "सुनो", "सुन", "सुनिए", "सुनिये", "सुनियो"
    )

    private const val MAX_WORD_POSITION = 2 // "shraa" must be the 1st or 2nd word spoken
    private val edge = " ,.!?-।\"'".toCharArray()
    private val word = Regex("\\S+")

    private fun isName(token: String, looseMode: Boolean): Boolean =
        strictLatin.matches(token) || strictDeva.matches(token) || (looseMode && token in loose)

    /** Null = no wake phrase. "" = wake phrase only. Otherwise the command text after it. */
    fun extractCommand(text: String, looseMode: Boolean = false): String? {
        val tokens = word.findAll(text).toList()
        for ((index, m) in tokens.withIndex()) {
            if (index >= MAX_WORD_POSITION) break
            val token = m.value.trim(*edge).lowercase(Locale.ROOT)
            if (token.isEmpty()) continue
            if (mergedLatin.matches(token)) return text.substring(m.range.last + 1).trim(*edge)
            if (isName(token, looseMode)) {
                val next = tokens.getOrNull(index + 1) ?: return null
                val nextToken = next.value.trim(*edge).lowercase(Locale.ROOT)
                if (nextToken in sunoWords) return text.substring(next.range.last + 1).trim(*edge)
                return null
            }
        }
        return null
    }
}
