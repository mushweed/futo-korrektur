package de.hnshnsen.korrektur

/**
 * Macht aus der rohen Modellantwort den Text, der eingesetzt werden kann.
 * Reine Rechnung, geprüft in NachbearbeitungTest.
 */
object Nachbearbeitung {

    private val denkblock = Regex("<think>.*?</think>", RegexOption.DOT_MATCHES_ALL)
    private val vorspann = Regex("^(korrigierte[rs]? text|korrektur|korrigiert)\\s*:\\s*", RegexOption.IGNORE_CASE)
    private val anfuehrungen = listOf("\"" to "\"", "„" to "“", "“" to "”", "»" to "«", "«" to "»", "'" to "'")

    fun bereinige(original: String, roh: String): String {
        var text = roh.replace(denkblock, "").trim()
        text = text.replace(vorspann, "")
        for ((auf, zu) in anfuehrungen) {
            val urTrim = original.trim()
            if (text.length >= 2 && text.startsWith(auf) && text.endsWith(zu) &&
                !(urTrim.startsWith(auf) && urTrim.endsWith(zu))
            ) {
                text = text.substring(auf.length, text.length - zu.length).trim()
            }
        }
        text = emojisAmEndeErhalten(original, text)
        // Leerraum um den Text gehört dem Feld, nicht der Korrektur: ein
        // Leerzeichen am Ende steht da, weil gleich weitergetippt wird.
        val davor = original.takeWhile { it.isWhitespace() }
        val danach = original.takeLastWhile { it.isWhitespace() }
        return davor + text.trim() + danach
    }

    /**
     * Kleine Modelle lassen Emojis am Satzende gern fallen. Fehlt der ganze
     * Emoji-Schluss des Originals in der Antwort, kommt er wieder dran.
     */
    fun emojisAmEndeErhalten(original: String, text: String): String {
        val schluss = emojiSchluss(original.trimEnd())
        if (schluss.isEmpty()) return text
        val emojis = schluss.trim()
        if (text.trimEnd().endsWith(emojis)) return text
        if (text.contains(emojis)) return text
        return text.trimEnd() + schluss
    }

    /** Der Emoji-Schluss samt Leerraum davor, etwa " 😩" oder " 😅🙈". */
    internal fun emojiSchluss(text: String): String {
        var i = text.length
        while (i > 0) {
            val cp = Character.codePointBefore(text, i)
            if (!istEmojiTeil(cp) && !Character.isWhitespace(cp)) break
            i -= Character.charCount(cp)
        }
        val schluss = text.substring(i)
        return if (schluss.any { !it.isWhitespace() }) schluss else ""
    }

    fun istEmojiTeil(cp: Int): Boolean =
        cp >= 0x1F000 ||                // Bildzeichen, Smileys, Flaggenbuchstaben
            cp in 0x2600..0x27BF ||     // Symbole und Dingbats (☀ ✌ ❤)
            cp in 0x2B00..0x2BFF ||     // Pfeile und Sterne (⭐)
            cp == 0x200D ||             // Verbinder in zusammengesetzten Emojis
            cp == 0xFE0F                // Darstellung als Emoji

    fun emojis(text: String): List<String> {
        val liste = mutableListOf<String>()
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            if (istEmojiTeil(cp) && cp != 0x200D && cp != 0xFE0F) liste += String(Character.toChars(cp))
            i += Character.charCount(cp)
        }
        return liste
    }
}
