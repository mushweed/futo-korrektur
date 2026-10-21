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

    /** So lange bleibt das Modell nach dem letzten Auftrag geladen. */
    private const val LEERLAUF_MINUTEN = 15L

    private lateinit var app: Context
    private val arbeiter = Executors.newSingleThreadScheduledExecutor { Thread(it, "korrektur") }
    private val sperre = Any()
    private var kern: Kern? = null
    private var kernDatei: File? = null
    private var kernThreads = 0
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

    /** Der Tensor G4 hat einen großen und drei mittlere Kerne; mehr Threads helfen selten. */
    var threads: Int
        get() = einstellungen.getInt("threads", 4)
        set(n) {
            einstellungen.edit().putInt("threads", n).apply()
            arbeiter.execute { entlade("Threads geändert") }
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

    fun korrigiere(auftrag: Int, text: String, fertig: (Ergebnis) -> Unit) {
        arbeiter.execute {
            val ergebnis = try {
                bearbeite(auftrag, text)
            } catch (e: Exception) {
                Ergebnis.Fehler(e.message ?: e.javaClass.simpleName)
            } finally {
                laufend.set(0)
                abgebrochen.remove(auftrag)
            }
            planeEntladen()
            fertig(ergebnis)
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
        val n = threads
        kern?.let { if (kernDatei == datei && kernThreads == n) return it }
        entlade("")
        zustand = "Lade $name …"
        val neu = Kern.laden(datei, n)
        synchronized(sperre) {
            kern = neu
            kernDatei = datei
            kernThreads = n
        }
        zustand = "Geladen: ${neu.info()}"
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
