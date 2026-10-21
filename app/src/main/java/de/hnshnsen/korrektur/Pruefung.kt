package de.hnshnsen.korrektur

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Entscheidet, ob eine Korrektur ungefragt eingesetzt werden darf.
 *
 * Erlaubt ist, was Rechtschreibung, Grammatik, Zeichensetzung und Groß-/
 * Kleinschreibung betrifft. Das heißt auf Wortebene: jedes Wort des Originals
 * findet sich in der Antwort wieder, höchstens um ein, zwei Buchstaben
 * verändert ("weis" → "weiß", "das" → "dass", "wärend" → "während"). Ein Wort,
 * das wegfällt oder gegen ein anderes getauscht wird ("offen" → "geöffnet"),
 * ist schon eine Umformulierung und wird gemeldet, ebenso ein hinzugefügtes
 * und ein verlorenes Emoji. Dazu die groben Grenzen aus dem Auftrag: mehr als
 * 30 % Längenunterschied oder ein zu großer Anteil geänderter Zeichen.
 */
data class Pruefung(
    val laengenAbweichung: Double,
    val editAnteil: Double,
    val entfernt: List<String>,
    val hinzu: List<String>,
    val verloreneEmojis: List<String>,
) {
    val zuStark: Boolean
        get() = laengenAbweichung > MAX_LAENGE || editAnteil > MAX_EDIT ||
            entfernt.isNotEmpty() || hinzu.isNotEmpty() || verloreneEmojis.isNotEmpty()

    /** Ein Satz für den Nutzer, leer wenn alles passt. */
    fun hinweis(): String {
        val teile = mutableListOf<String>()
        if (entfernt.isNotEmpty()) teile += "weggelassen oder ersetzt: " + entfernt.joinToString(", ")
        if (hinzu.isNotEmpty()) teile += "neu: " + hinzu.joinToString(", ")
        if (verloreneEmojis.isNotEmpty()) teile += "Emoji fehlt: " + verloreneEmojis.joinToString(" ")
        if (laengenAbweichung > MAX_LAENGE) teile += "Länge um %.0f %% verändert".format(laengenAbweichung * 100)
        if (editAnteil > MAX_EDIT) teile += "%.0f %% der Zeichen geändert".format(editAnteil * 100)
        return teile.joinToString("; ")
    }

    companion object {
        const val MAX_LAENGE = 0.30
        const val MAX_EDIT = 0.35

        private val wort = Regex("[\\p{L}\\p{N}'’]+")

        fun von(original: String, korrigiert: String): Pruefung {
            val a = original.trim()
            val b = korrigiert.trim()
            val laenge = abs(b.length - a.length).toDouble() / max(1, a.length)
            val edit = abstand(a, b).toDouble() / max(1, a.length)
            val (entfernt, hinzu) = wortAenderungen(a, b)
            val emojisB = Nachbearbeitung.emojis(b).toMutableList()
            val verloren = Nachbearbeitung.emojis(a).filter { !emojisB.remove(it) }
            return Pruefung(laenge, edit, entfernt, hinzu, verloren)
        }

        /** Wörter, die keinen nahen Partner gefunden haben, je Seite. */
        fun wortAenderungen(original: String, korrigiert: String): Pair<List<String>, List<String>> {
            val wa = wort.findAll(original).map { it.value }.toList()
            val wb = wort.findAll(korrigiert).map { it.value }.toList()
            // Längste gemeinsame Folge mit unscharfem Vergleich.
            val t = Array(wa.size + 1) { IntArray(wb.size + 1) }
            for (i in wa.indices.reversed()) for (j in wb.indices.reversed()) {
                t[i][j] = if (aehnlich(wa[i], wb[j])) t[i + 1][j + 1] + 1 else max(t[i + 1][j], t[i][j + 1])
            }
            val entfernt = mutableListOf<String>()
            val hinzu = mutableListOf<String>()
            var i = 0
            var j = 0
            while (i < wa.size || j < wb.size) {
                when {
                    i < wa.size && j < wb.size && aehnlich(wa[i], wb[j]) && t[i][j] == t[i + 1][j + 1] + 1 -> { i++; j++ }
                    j < wb.size && (i == wa.size || t[i][j + 1] >= t[i + 1][j]) -> hinzu += wb[j++]
                    else -> {
                        // Ein verdoppeltes Wort zu streichen ist eine Korrektur ("und und").
                        if (i == 0 || !wa[i].equals(wa[i - 1], ignoreCase = true)) entfernt += wa[i]
                        i++
                    }
                }
            }
            return entfernt to hinzu
        }

        fun aehnlich(a: String, b: String): Boolean {
            val x = a.lowercase()
            val y = b.lowercase()
            if (x == y) return true
            val erlaubt = if (min(x.length, y.length) >= 5) 2 else 1
            return abstand(x, y) <= erlaubt
        }

        /** Levenshtein-Abstand, zwei Zeilen. */
        fun abstand(a: String, b: String): Int {
            var vorher = IntArray(b.length + 1) { it }
            var jetzt = IntArray(b.length + 1)
            for (i in 1..a.length) {
                jetzt[0] = i
                for (j in 1..b.length) {
                    val kosten = if (a[i - 1] == b[j - 1]) 0 else 1
                    jetzt[j] = min(min(jetzt[j - 1] + 1, vorher[j] + 1), vorher[j - 1] + kosten)
                }
                val tausch = vorher; vorher = jetzt; jetzt = tausch
            }
            return vorher[b.length]
        }
    }
}
