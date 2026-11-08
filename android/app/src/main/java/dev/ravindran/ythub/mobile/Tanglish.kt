package dev.ravindran.ythub.mobile

import android.icu.text.Transliterator
import android.os.Build

/** Tamil-to-Latin pronunciation transliteration used for the optional Tanglish lyric view. */
internal object Tanglish {
    private val transliterator by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) Transliterator.getInstance("Tamil-Latin; Latin-ASCII") else null
    }

    fun isTamil(text: String): Boolean = text.any { it.code in 0x0B80..0x0BFF }

    fun convert(text: String): String {
        if (!isTamil(text)) return text
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching { transliterator?.transliterate(text) }.getOrNull()?.let { return it }
        }
        return transliterateBasicTamil(text)
    }

    /** Small API-26–28 fallback; maps Tamil graphemes into familiar ASCII Tanglish sounds. */
    private fun transliterateBasicTamil(input: String): String {
        val consonants = mapOf(
            'க' to "k", 'ங' to "ng", 'ச' to "ch", 'ஞ' to "nj", 'ட' to "t", 'ண' to "n",
            'த' to "th", 'ந' to "n", 'ப' to "p", 'ம' to "m", 'ய' to "y", 'ர' to "r",
            'ல' to "l", 'வ' to "v", 'ழ' to "zh", 'ள' to "l", 'ற' to "r", 'ன' to "n",
            'ஜ' to "j", 'ஷ' to "sh", 'ஸ' to "s", 'ஹ' to "h"
        )
        val vowels = mapOf(
            'அ' to "a", 'ஆ' to "aa", 'இ' to "i", 'ஈ' to "ee", 'உ' to "u", 'ஊ' to "oo",
            'எ' to "e", 'ஏ' to "ae", 'ஐ' to "ai", 'ஒ' to "o", 'ஓ' to "oa", 'ஔ' to "au"
        )
        val signs = mapOf(
            'ா' to "aa", 'ி' to "i", 'ீ' to "ee", 'ு' to "u", 'ூ' to "oo", 'ெ' to "e",
            'ே' to "ae", 'ை' to "ai", 'ொ' to "o", 'ோ' to "oa", 'ௌ' to "au"
        )
        val out = StringBuilder(input.length * 2)
        var i = 0
        while (i < input.length) {
            val ch = input[i]
            val consonant = consonants[ch]
            if (consonant != null) {
                val next = input.getOrNull(i + 1)
                when {
                    next == '்' -> { out.append(consonant); i += 2 }
                    next != null && next in signs -> { out.append(consonant).append(signs.getValue(next)); i += 2 }
                    else -> { out.append(consonant).append('a'); i++ }
                }
                continue
            }
            when {
                ch in vowels -> out.append(vowels.getValue(ch))
                ch == '்' -> Unit
                ch == 'ஃ' -> out.append("ah")
                else -> out.append(ch)
            }
            i++
        }
        return out.toString()
    }
}
