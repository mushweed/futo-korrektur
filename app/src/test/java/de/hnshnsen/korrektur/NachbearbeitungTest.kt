package de.hnshnsen.korrektur

import org.junit.Assert.assertEquals
import org.junit.Test

class NachbearbeitungTest {

    private fun bereinige(original: String, roh: String) = Nachbearbeitung.bereinige(original, roh)

    @Test
    fun emojiAmEndeKommtZurueck() {
        // So geschehen bei EuroLLM-1.7B: das Emoji fiel einfach weg.
        assertEquals(
            "Ich weiß nicht, ob ich das heute noch schaffe. 😩",
            bereinige("ich weis nicht ob ich das heute noch schaffe 😩", "Ich weiß nicht, ob ich das heute noch schaffe."),
        )
    }

    @Test
    fun vorhandenesEmojiBleibtEinmal() {
        assertEquals("Bin gleich da 🙈", bereinige("bin gleich da 🙈", "Bin gleich da 🙈"))
        assertEquals("Super 👍🏽", bereinige("super 👍🏽", "Super 👍🏽"))
    }

    @Test
    fun leerraumDesFeldesBleibt() {
        assertEquals("Hallo, du ", bereinige("hallo du ", "Hallo, du"))
        assertEquals("\nHallo, du", bereinige("\nhallo du", "Hallo, du"))
    }

    @Test
    fun denkblockUndVorspannFallenWeg() {
        assertEquals("Hallo, du", bereinige("hallo du", "<think>\n\n</think>\n\nHallo, du"))
        assertEquals("Hallo, du", bereinige("hallo du", "Korrigierter Text: Hallo, du"))
    }

    @Test
    fun anfuehrungszeichenNurWennNichtImOriginal() {
        assertEquals("Hallo, du", bereinige("hallo du", "\"Hallo, du\""))
        assertEquals("„Hallo, du“", bereinige("„hallo du“", "„Hallo, du“"))
    }

    @Test
    fun emojiSchluss() {
        assertEquals(" 😅🙈", Nachbearbeitung.emojiSchluss("na gut 😅🙈"))
        assertEquals("", Nachbearbeitung.emojiSchluss("na gut"))
        assertEquals(" ❤️", Nachbearbeitung.emojiSchluss("danke ❤️"))
    }
}
