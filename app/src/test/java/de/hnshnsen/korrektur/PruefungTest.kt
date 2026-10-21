package de.hnshnsen.korrektur

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Die Fälle stammen aus dem ersten Probelauf mit EuroLLM-1.7B (21.10.2026). */
class PruefungTest {

    private fun pruefe(vorher: String, nachher: String) = Pruefung.von(vorher, nachher)

    @Test
    fun reineKorrekturGehtDurch() {
        val p = pruefe(
            "ich komme heute später nach hause weil der zug ausgefallen ist",
            "Ich komme heute später nach Hause, weil der Zug ausgefallen ist.",
        )
        assertFalse(p.hinweis(), p.zuStark)
    }

    @Test
    fun rechtschreibungImWortGehtDurch() {
        // das→dass, weis→weiß, wärend→während, hab→habe: je ein Buchstabe
        assertFalse(pruefe("Hast du dran gedacht das wir morgen kommen?", "Hast du dran gedacht, dass wir morgen kommen?").zuStark)
        assertFalse(pruefe("die kinder spielen wärend wir kochen", "Die Kinder spielen, während wir kochen.").zuStark)
        assertFalse(pruefe("Ich hab das Paket abgeschickt.", "Ich habe das Paket abgeschickt.").zuStark)
    }

    @Test
    fun verdoppeltesWortDarfWeg() {
        val p = pruefe(
            "Wir haben uns lange unterhalten und und sind dann gegangen.",
            "Wir haben uns lange unterhalten und sind dann gegangen.",
        )
        assertFalse(p.hinweis(), p.zuStark)
    }

    @Test
    fun verlorenesWortWirdGemeldet() {
        val p = pruefe(
            "Das Meeting wurde verschoben, sag bescheid ob dir das past.",
            "Das Meeting wurde verschoben, sag Bescheid, ob das passt.",
        )
        assertTrue(p.zuStark)
        assertEquals(listOf("dir"), p.entfernt)
    }

    @Test
    fun ersetztesWortWirdGemeldet() {
        val p = pruefe("seit wann ist der laden den sonntags offen?", "Seit wann ist der Laden sonntags geöffnet?")
        assertTrue(p.zuStark)
        assertEquals(listOf("den", "offen"), p.entfernt)
        assertEquals(listOf("geöffnet"), p.hinzu)
    }

    @Test
    fun verlorenesEmojiWirdGemeldet() {
        val p = pruefe("so ein mist 😩 echt", "So ein Mist, echt.")
        assertTrue(p.zuStark)
        assertEquals(listOf("😩"), p.verloreneEmojis)
    }

    @Test
    fun grobeGrenzen() {
        assertTrue(pruefe("kurz", "ein ganz anderer und viel längerer Text").zuStark)
    }

    @Test
    fun abstand() {
        assertEquals(0, Pruefung.abstand("haus", "haus"))
        assertEquals(1, Pruefung.abstand("weis", "weiß"))
        assertEquals(3, Pruefung.abstand("kitten", "sitting"))
    }
}
