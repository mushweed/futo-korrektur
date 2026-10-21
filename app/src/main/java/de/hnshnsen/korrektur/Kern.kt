package de.hnshnsen.korrektur

import java.io.Closeable
import java.io.File

/** Die nativen Aufrufe, siehe cpp/jni.cpp. Texte reisen als UTF-8-Bytes. */
internal object KernNativ {
    init {
        System.loadLibrary("korrektur")
    }

    @JvmStatic external fun laden(pfad: ByteArray, threads: Int, kontext: Int, zwischenablage: ByteArray): Long
    @JvmStatic external fun korrigiere(zeiger: Long, text: ByteArray, maxToken: Int): ByteArray?
    @JvmStatic external fun messung(zeiger: Long): LongArray
    @JvmStatic external fun info(zeiger: Long): ByteArray
    @JvmStatic external fun threads(zeiger: Long, anzahl: Int, poll: Int, grosse: Boolean)
    @JvmStatic external fun abbrechen(zeiger: Long)
    @JvmStatic external fun zuruecksetzen(zeiger: Long)
    @JvmStatic external fun freigeben(zeiger: Long)
}

/**
 * Wie gerechnet wird. Auf dem Pixel entscheidet das über Faktor zehn und mehr:
 * kreisende Threads auf Sparkernen warten bei jedem Schritt aufeinander.
 */
data class ThreadWahl(val anzahl: Int, val nurGrosse: Boolean, val kreisen: Boolean) {
    fun text() = "$anzahl Threads, " + (if (nurGrosse) "schnelle Kerne" else "alle Kerne") +
        (if (kreisen) ", kreisend" else ", schlafend")
}

data class Messung(
    val ladenMs: Long,
    val promptToken: Int,
    val promptMs: Long,
    val antwortToken: Int,
    val antwortMs: Long,
) {
    fun text(): String {
        fun proSekunde(token: Int, ms: Long) = if (ms > 0) "%.1f T/s".format(token * 1000.0 / ms) else "–"
        val teile = mutableListOf<String>()
        if (ladenMs > 0) teile += "Laden $ladenMs ms"
        teile += "Einlesen $promptToken Token in $promptMs ms (${proSekunde(promptToken, promptMs)})"
        teile += "Antwort $antwortToken Token in $antwortMs ms (${proSekunde(antwortToken, antwortMs)})"
        teile += "zusammen ${promptMs + antwortMs} ms"
        return teile.joinToString(" · ")
    }
}

/** Ein geladenes Modell. Nicht threadsicher bis auf [abbrechen]. */
internal class Kern private constructor(private var zeiger: Long) : Closeable {

    companion object {
        fun laden(datei: File, threads: Int, zwischenablage: File, kontext: Int = 2048): Kern =
            Kern(KernNativ.laden(datei.absolutePath.toByteArray(), threads, kontext, zwischenablage.absolutePath.toByteArray()))
    }

    /** null heißt abgebrochen; ein Fehler kommt als Ausnahme. */
    fun korrigiere(text: String, maxToken: Int): String? =
        KernNativ.korrigiere(zeiger, text.toByteArray(Charsets.UTF_8), maxToken)?.toString(Charsets.UTF_8)

    fun messung(): Messung = KernNativ.messung(zeiger).let {
        Messung(it[0], it[1].toInt(), it[2], it[3].toInt(), it[4])
    }

    fun info(): String = KernNativ.info(zeiger).toString(Charsets.UTF_8)

    /** Ohne neu zu laden; siehe Kern::threads in kern.h. */
    fun threads(wahl: ThreadWahl) = KernNativ.threads(zeiger, wahl.anzahl, if (wahl.kreisen) 50 else 0, wahl.nurGrosse)
    fun abbrechen() = KernNativ.abbrechen(zeiger)
    fun zuruecksetzen() = KernNativ.zuruecksetzen(zeiger)

    override fun close() {
        if (zeiger != 0L) KernNativ.freigeben(zeiger)
        zeiger = 0L
    }
}
