package com.dave.framegrabber.telegram

/**
 * The post text, always the same shape:
 *
 *     Studio name - Model A & Model B
 *     ————————————————
 *     #tag1 #tag2 #tag3
 *
 * Plain text (no parse mode): Telegram shows exactly these characters, and makes the hashtags
 * clickable by itself.
 */
object PostCaption {
    const val SEPARATOR = "————————————————"
    /** Telegram's limit for a photo caption sent by a bot. */
    const val MAX = 1024
    const val PHOTOS = 6

    fun build(studio: String, models: List<String>, tags: List<String>): String {
        val head = buildString {
            append(studio.trim())
            val m = models.map { it.trim() }.filter { it.isNotEmpty() }
            if (m.isNotEmpty()) {
                if (isNotEmpty()) append(" - ")
                append(m.joinToString(" & "))
            }
        }
        return buildString {
            append(head)
            append('\n').append(SEPARATOR)
            if (tags.isNotEmpty()) append('\n').append(tags.joinToString(" ") { "#$it" })
        }
    }

    /** Length as Telegram counts it (UTF-16 units, same as Kotlin). */
    fun length(text: String) = text.length

    /**
     * A tag Telegram will treat as one hashtag: letters, digits and '_' only
     * ("big studio" -> "big_studio", "#Sci-Fi" -> "Sci_Fi"). Null if nothing is left.
     */
    fun normalizeTag(raw: String): String? {
        val t = raw.trim().trimStart('#')
            .replace(Regex("[^\\p{L}\\p{N}_]+"), "_")
            .replace(Regex("_+"), "_")
            .trim('_')
        if (t.isEmpty()) return null
        // only digits is not a hashtag in Telegram
        if (t.all { it.isDigit() || it == '_' }) return null
        return t.take(64)
    }
}
