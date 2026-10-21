package de.hnshnsen.korrektur

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import java.io.File

/**
 * Modelle verwalten und ausprobieren, ohne die Tastatur. Die Prüfsätze liefern
 * Messwerte zum Vergleich zweier Modelle auf dem Gerät.
 */
class StartAktivitaet : Activity() {

    private val haupt = Handler(Looper.getMainLooper())
    private lateinit var zustand: TextView
    private lateinit var modellListe: RadioGroup
    private lateinit var eingabe: EditText
    private lateinit var ausgabe: TextView
    private var auftrag = 0
    private var protokoll = StringBuilder()

    private val auffrischen = object : Runnable {
        override fun run() {
            zustand.text = Korrektor.zustand
            haupt.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Korrektor.init(this)

        val spalte = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(24), dp(16), dp(24))
        }
        fun ueberschrift(text: String) = spalte.addView(TextView(this).apply {
            this.text = text
            textSize = 18f
            setPadding(0, dp(20), 0, dp(8))
        })
        fun knopf(text: String, wenn: () -> Unit) = Button(this).apply {
            this.text = text
            isAllCaps = false
            setOnClickListener { wenn() }
        }

        zustand = TextView(this).apply { textSize = 14f }
        spalte.addView(zustand)

        ueberschrift("Modell")
        modellListe = RadioGroup(this)
        spalte.addView(modellListe)
        val modellKnoepfe = LinearLayout(this)
        modellKnoepfe.addView(knopf("Hinzufügen …") { waehleDatei() })
        modellKnoepfe.addView(knopf("Gewähltes löschen") { loescheGewaehltes() })
        spalte.addView(modellKnoepfe)

        ueberschrift("Threads")
        val threadWahl = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
        for (n in listOf(2, 3, 4, 6, 8)) {
            threadWahl.addView(RadioButton(this).apply {
                id = View.generateViewId()
                text = "$n"
                isChecked = n == Korrektor.threadWahl.anzahl
                setOnClickListener { Korrektor.threadWahl = Korrektor.threadWahl.copy(anzahl = n) }
            })
        }
        spalte.addView(threadWahl)
        spalte.addView(CheckBox(this).apply {
            text = "Nur die schnellen Kerne"
            isChecked = Korrektor.threadWahl.nurGrosse
            setOnCheckedChangeListener { _, an -> Korrektor.threadWahl = Korrektor.threadWahl.copy(nurGrosse = an) }
        })
        spalte.addView(CheckBox(this).apply {
            text = "Wartende Threads kreisen lassen"
            isChecked = Korrektor.threadWahl.kreisen
            setOnCheckedChangeListener { _, an -> Korrektor.threadWahl = Korrektor.threadWahl.copy(kreisen = an) }
        })
        spalte.addView(knopf("Threads messen") { messeThreads() })

        ueberschrift("Ausprobieren")
        eingabe = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            minLines = 3
            gravity = Gravity.TOP
            hint = "Text mit Fehlern"
        }
        spalte.addView(eingabe)
        val probeKnoepfe = LinearLayout(this)
        probeKnoepfe.addView(knopf("Korrigieren") { korrigiereEingabe() })
        probeKnoepfe.addView(knopf("Abbrechen") { Korrektor.abbrechen(auftrag) })
        spalte.addView(probeKnoepfe)
        val pruefKnoepfe = LinearLayout(this)
        pruefKnoepfe.addView(knopf("Prüfsätze durchlaufen") { pruefsaetze() })
        pruefKnoepfe.addView(knopf("Ergebnis kopieren") { kopiere() })
        spalte.addView(pruefKnoepfe)
        ausgabe = TextView(this).apply {
            setTextIsSelectable(true)
            textSize = 14f
            setPadding(0, dp(12), 0, 0)
        }
        spalte.addView(ausgabe)

        setContentView(ScrollView(this).apply { addView(spalte) })
        zeigeModelle()
    }

    override fun onResume() {
        super.onResume()
        haupt.post(auffrischen)
        Korrektor.vorbereiten()
    }

    override fun onPause() {
        haupt.removeCallbacks(auffrischen)
        super.onPause()
    }

    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()

    private fun zeigeModelle() {
        modellListe.removeAllViews()
        val aktiv = Korrektor.aktivesModell
        val modelle = Korrektor.modelle()
        if (modelle.isEmpty()) {
            modellListe.addView(TextView(this).apply { text = "Noch keins. „Hinzufügen …“ und eine .gguf-Datei wählen." })
        }
        for (datei in modelle) {
            modellListe.addView(RadioButton(this).apply {
                id = View.generateViewId()
                text = "${datei.name} (${datei.length() / 1048576} MB)"
                tag = datei.name
                isChecked = datei.name == aktiv
                setOnClickListener { Korrektor.aktivesModell = datei.name }
            })
        }
    }

    // ---- Modelle hinzufügen und löschen ----

    private fun waehleDatei() {
        val absicht = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        @Suppress("DEPRECATION")
        startActivityForResult(absicht, 1)
    }

    @Deprecated("startActivityForResult genügt hier; keine AndroidX-Abhängigkeit")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data
        if (requestCode == 1 && resultCode == RESULT_OK && uri != null) kopiereModell(uri)
    }

    private fun kopiereModell(uri: Uri) {
        val name = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        } ?: "modell.gguf"
        if (!name.endsWith(".gguf", ignoreCase = true)) {
            zeige("„$name“ ist keine .gguf-Datei.")
            return
        }
        val ziel = File(Korrektor.ordner(), name)
        val teil = File(Korrektor.ordner(), "$name.teil")
        zeige("Kopiere $name …")
        Thread {
            val meldung = try {
                contentResolver.openInputStream(uri)!!.use { rein -> teil.outputStream().use { rein.copyTo(it, 1 shl 20) } }
                if (!teil.renameTo(ziel)) throw IllegalStateException("Umbenennen schlug fehl")
                "$name hinzugefügt."
            } catch (e: Exception) {
                teil.delete()
                "Kopieren schlug fehl: ${e.message}"
            }
            haupt.post {
                zeige(meldung)
                if (Korrektor.modelle().size == 1) Korrektor.aktivesModell = name
                zeigeModelle()
            }
        }.start()
    }

    private fun loescheGewaehltes() {
        val name = Korrektor.aktivesModell ?: return
        AlertDialog.Builder(this)
            .setMessage("$name vom Gerät löschen?")
            .setPositiveButton("Löschen") { _, _ ->
                Korrektor.aktivesModell = null
                File(Korrektor.ordner(), name).delete()
                zeigeModelle()
            }
            .setNegativeButton("Behalten", null)
            .show()
    }

    // ---- Ausprobieren ----

    private fun korrigiereEingabe() {
        val text = eingabe.text.toString()
        protokoll = StringBuilder()
        zeige("Korrigiere …")
        Korrektor.korrigiere(++auftrag, text) { ergebnis ->
            val zeilen = beschreibe(text, ergebnis)
            haupt.post {
                protokoll.append(zeilen)
                zeige(protokoll.toString())
            }
        }
    }

    private fun pruefsaetze() {
        val saetze = assets.open("pruefsaetze.txt").bufferedReader().readLines().filter { it.isNotBlank() }
        protokoll = StringBuilder("Modell: ${Korrektor.aktivesModell} · ${Korrektor.threadWahl.text()}\n\n")
        zeige(protokoll.toString() + "Läuft …")
        for (satz in saetze) {
            Korrektor.korrigiere(++auftrag, satz) { ergebnis ->
                val zeilen = beschreibe(satz, ergebnis)
                haupt.post {
                    protokoll.append(zeilen)
                    zeige(protokoll.toString())
                }
            }
        }
    }

    private fun messeThreads() {
        protokoll = StringBuilder("Modell: ${Korrektor.aktivesModell}\nThreads messen, ein Satz je Einstellung …\n\n")
        zeige(protokoll.toString())
        Korrektor.messeThreads(
            zeile = { z -> haupt.post { protokoll.append(z).append('\n'); zeige(protokoll.toString()) } },
            fertig = { haupt.post { protokoll.append("\nFertig."); zeige(protokoll.toString()) } },
        )
    }

    private fun beschreibe(text: String, ergebnis: Korrektor.Ergebnis): String = when (ergebnis) {
        is Korrektor.Ergebnis.Fertig -> buildString {
            append("vorher  ").append(text).append('\n')
            append("nachher ").append(ergebnis.korrigiert.trim()).append('\n')
            if (ergebnis.pruefung.zuStark) append("Nicht übernommen: ").append(ergebnis.pruefung.hinweis()).append('\n')
            append(ergebnis.messung.text()).append("\n\n")
        }
        is Korrektor.Ergebnis.Fehler -> "Fehler: ${ergebnis.meldung}\n\n"
        Korrektor.Ergebnis.Abgebrochen -> "Abgebrochen.\n\n"
    }

    private fun zeige(text: String) {
        ausgabe.text = text
    }

    private fun kopiere() {
        val ablage = getSystemService(ClipboardManager::class.java)
        ablage.setPrimaryClip(ClipData.newPlainText("Korrektur", (zustand.text.toString() + "\n\n" + ausgabe.text)))
    }
}
