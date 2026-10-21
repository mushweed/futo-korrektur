package de.hnshnsen.korrektur

import android.content.Context
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Hält das Modell und arbeitet Aufträge nacheinander ab, auf einem eigenen
 * Thread. Dienst und Startbildschirm teilen sich diese eine Instanz.
 */
object Korrektor {

    sealed interface Ergebnis {
        data class Fertig(val korrigiert: String, val roh: String, val pruefung: Pruefung, val messung: Messung) : Ergebnis
        data class Fehler(val meldung: String) : Ergebnis
        data object Abgebrochen : Ergebnis
    }

    /** Länger am Stück korrigiert die Tastatur nicht (Absatz vor dem Cursor). */
    const val MAX_ZEICHEN = 1200

    /**
     * So lange bleibt das Modell nach dem letzten Auftrag geladen. Neu laden
     * kostete auf dem Pixel 25 s; ein Gigabyte von sechzehn ist der kleinere Preis.
     */
    private const val LEERLAUF_MINUTEN = 120L

    private lateinit var app: Context
    private val arbeiter = Executors.newSingleThreadScheduledExecutor { Thread(it, "korrektur") }
    private val sperre = Any()
    private var kern: Kern? = null
    private var kernDatei: File? = null
    private var entladen: ScheduledFuture<*>? = null
    private val laufend = AtomicInteger(0)
    private val abgebrochen: MutableSet<Int> = ConcurrentHashMap.newKeySet()

    @Volatile var zustand: String = "Kein Modell geladen"
        private set

    fun init(context: Context) {
        if (!::app.isInitialized) app = context.applicationContext
    }

    private val einstellungen get() = app.getSharedPreferences("korrektur", Context.MODE_PRIVATE)

    fun ordner(): File = File(app.filesDir, "modelle").apply { mkdirs() }

    fun modelle(): List<File> =
        ordner().listFiles { f -> f.isFile && f.name.endsWith(".gguf") }?.sortedBy { it.name.lowercase() } ?: emptyList()

    var aktivesModell: String?
        get() = einstellungen.getString("modell", null) ?: modelle().firstOrNull()?.name
        set(name) {
            einstellungen.edit().putString("modell", name).apply()
            arbeiter.execute { entlade("Modell gewechselt") }
        }

    /** Der Tensor G4 hat einen großen, drei mittlere und vier Sparkerne. */
    var threadWahl: ThreadWahl
        get() = ThreadWahl(
            einstellungen.getInt("threads", 4),
            einstellungen.getBoolean("nur_grosse", true),
            einstellungen.getBoolean("kreisen", false),
        )
        set(w) {
            einstellungen.edit().putInt("threads", w.anzahl).putBoolean("nur_grosse", w.nurGrosse)
                .putBoolean("kreisen", w.kreisen).apply()
            arbeiter.execute { kern?.let { wende(it, w) } }
        }

    private fun wende(k: Kern, w: ThreadWahl) {
        k.threads(w)
        zustand = "Geladen: ${k.info()}"
    }

    /** Ein Satz, viele Einstellungen: misst, was auf diesem Gerät am schnellsten ist. */
    fun messeThreads(zeile: (String) -> Unit, fertig: () -> Unit) {
        arbeiter.execute {
            val vorher = threadWahl
            try {
                val k = sicherGeladen()
                val satz = "ich komme heute später nach hause weil der zug ausgefallen ist"
                val wahlen = listOf(
                    ThreadWahl(2, true, false), ThreadWahl(3, true, false), ThreadWahl(4, true, false),
                    ThreadWahl(4, true, true), ThreadWahl(4, false, false), ThreadWahl(6, false, false),
                    ThreadWahl(8, false, false), ThreadWahl(8, false, true),
                )
                for (w in wahlen) {
                    k.threads(w)
                    k.zuruecksetzen()
                    val antwort = k.korrigiere(satz, 40)
                    val m = k.messung()
                    zeile(
                        if (antwort == null) "${w.text()}: abgebrochen"
                        else "%s: Einlesen %.1f T/s, Antwort %.1f T/s, zusammen %d ms".format(
                            w.text(), m.promptToken * 1000.0 / maxOf(1, m.promptMs),
                            m.antwortToken * 1000.0 / maxOf(1, m.antwortMs), m.promptMs + m.antwortMs,
                        )
                    )
                }
            } catch (e: Exception) {
                zeile("Fehler: ${e.message}")
            } finally {
                kern?.let { wende(it, vorher) }
                fertig()
            }
        }
    }

    fun vorbereiten() {
        arbeiter.execute {
            try {
                sicherGeladen()
                planeEntladen()
            } catch (_: Exception) {
                // Der nächste Auftrag meldet den Fehler, hier hört niemand zu.
            }
        }
    }

    /** Die letzten Aufträge der Tastatur, für den Startbildschirm. */
    private val tastaturProtokoll = ArrayDeque<String>()

    fun tastaturProtokoll(): List<String> = synchronized(tastaturProtokoll) { tastaturProtokoll.toList() }

    /** [vonTastatur]: Lage und Messwerte ins Tastatur-Protokoll schreiben. */
    fun korrigiere(auftrag: Int, text: String, vonTastatur: Boolean = false, fertig: (Ergebnis) -> Unit) {
        val eingang = System.currentTimeMillis()
        arbeiter.execute {
            val lageVorher = if (vonTastatur) Lage.jetzt() else ""
            val ergebnis = try {
                bearbeite(auftrag, text)
            } catch (e: Exception) {
                Ergebnis.Fehler(e.message ?: e.javaClass.simpleName)
            } finally {
                laufend.set(0)
                abgebrochen.remove(auftrag)
            }
            if (vonTastatur) protokolliere(eingang, text, ergebnis, lageVorher)
            planeEntladen()
            fertig(ergebnis)
        }
    }

    private fun protokolliere(eingang: Long, text: String, ergebnis: Ergebnis, lageVorher: String) {
        val uhr = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.GERMANY).format(java.util.Date(eingang))
        val eintrag = buildString {
            append(uhr).append(" · ").append(System.currentTimeMillis() - eingang).append(" ms seit Eingang\n")
            append("Text: ").append(text.take(60)).append('\n')
            when (ergebnis) {
                is Ergebnis.Fertig -> append(ergebnis.messung.text()).append('\n')
                is Ergebnis.Fehler -> append("Fehler: ").append(ergebnis.meldung).append('\n')
                Ergebnis.Abgebrochen -> append("Abgebrochen\n")
            }
            append("Vorher: ").append(lageVorher).append('\n')
            append("Nachher: ").append(Lage.jetzt())
        }
        synchronized(tastaturProtokoll) {
            tastaturProtokoll.addFirst(eintrag)
            while (tastaturProtokoll.size > 10) tastaturProtokoll.removeLast()
        }
    }

    fun abbrechen(auftrag: Int) {
        abgebrochen.add(auftrag)
        if (laufend.get() == auftrag) synchronized(sperre) { kern?.abbrechen() }
    }

    private fun bearbeite(auftrag: Int, text: String): Ergebnis {
        if (text.isBlank()) return Ergebnis.Fehler("Kein Text")
        if (text.length > MAX_ZEICHEN) return Ergebnis.Fehler("Text ist länger als $MAX_ZEICHEN Zeichen")
        val k = sicherGeladen()
        laufend.set(auftrag)
        k.zuruecksetzen()
        if (auftrag in abgebrochen) return Ergebnis.Abgebrochen
        val maxToken = minOf(text.length / 2 + 64, 768)
        val roh = k.korrigiere(text.trim(), maxToken) ?: return Ergebnis.Abgebrochen
        val korrigiert = Nachbearbeitung.bereinige(text, roh)
        if (korrigiert.isBlank()) return Ergebnis.Fehler("Das Modell hat nichts geantwortet")
        return Ergebnis.Fertig(korrigiert, roh, Pruefung.von(text, korrigiert), k.messung())
    }

    /** Nur auf dem Arbeiter-Thread aufrufen. */
    private fun sicherGeladen(): Kern {
        val name = aktivesModell ?: throw IllegalStateException("Kein Modell gewählt – in der App Korrektur eines hinzufügen")
        val datei = File(ordner(), name)
        if (!datei.isFile) throw IllegalStateException("Modell $name fehlt")
        kern?.let { if (kernDatei == datei) return it }
        entlade("")
        zustand = "Lade $name …"
        val wahl = threadWahl
        val neu = Kern.laden(datei, wahl.anzahl, File(app.cacheDir, "anfang").apply { mkdirs() })
        synchronized(sperre) {
            kern = neu
            kernDatei = datei
        }
        wende(neu, wahl)
        return neu
    }

    private fun planeEntladen() {
        entladen?.cancel(false)
        entladen = arbeiter.schedule({ entlade("Nach $LEERLAUF_MINUTEN Minuten ohne Auftrag entladen") }, LEERLAUF_MINUTEN, TimeUnit.MINUTES)
    }

    private fun entlade(grund: String) {
        synchronized(sperre) {
            kern?.close()
            kern = null
            kernDatei = null
        }
        if (grund.isNotEmpty()) zustand = grund
    }
}
