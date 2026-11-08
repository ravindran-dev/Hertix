package dev.ravindran.ythub.mobile

import android.icu.text.Transliterator

/** Tamil-to-Latin pronunciation transliteration used for the optional Tanglish lyric view. */
internal object Tanglish {
    private val transliterator by lazy { Transliterator.getInstance("Tamil-Latin; Latin-ASCII") }

    fun isTamil(text: String): Boolean = text.any { it.code in 0x0B80..0x0BFF }

    fun convert(text: String): String = if (!isTamil(text)) text
    else runCatching { transliterator.transliterate(text) }.getOrDefault(text)
}
