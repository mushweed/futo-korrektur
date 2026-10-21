package de.hnshnsen.korrektur

import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Binder
import android.os.IBinder
import android.os.Process
import android.os.RemoteException
import java.security.MessageDigest

/**
 * Der gebundene Dienst für die Tastatur. Annehmen darf er nur Aufrufe vom
 * eigenen Fork der Tastatur, erkannt an Paketname und Signatur.
 */
class KorrekturDienst : Service() {

    companion object {
        const val AKTION = "de.hnshnsen.korrektur.KORRIGIEREN"

        /** Der Fork, mit und ohne Endung der Bauvariante. */
        private val TASTATUREN = setOf("de.hnshnsen.keyboard", "de.hnshnsen.keyboard.unstable")

        /** SHA-256 des Schlüssels, mit dem der Fork signiert wird (java/shared.keystore). */
        private const val TASTATUR_ZERTIFIKAT =
            "28BBFE4A7B97E74681DC55C2FBB6CCB8D6C74963733F6AF6AE74D8C3A6E879FD"
    }

    override fun onCreate() {
        super.onCreate()
        Korrektor.init(this)
    }

    override fun onBind(intent: Intent): IBinder = binder

    private val binder = object : IKorrektur.Stub() {
        override fun version() = 1

        override fun vorbereiten() {
            if (erlaubt()) Korrektor.vorbereiten()
        }

        override fun korrigiere(auftrag: Int, text: String?, rueckruf: IKorrekturRueckruf?) {
            if (rueckruf == null) return
            if (!erlaubt()) return melde { rueckruf.fehler(auftrag, "Dieser App ist die Korrektur nicht freigegeben") }
            if (text == null) return melde { rueckruf.fehler(auftrag, "Kein Text") }
            Korrektor.korrigiere(auftrag, text, vonTastatur = true) { ergebnis ->
                melde {
                    when (ergebnis) {
                        is Korrektor.Ergebnis.Fertig -> rueckruf.fertig(
                            auftrag, ergebnis.korrigiert, ergebnis.pruefung.zuStark,
                            ergebnis.pruefung.hinweis(), ergebnis.messung.text(),
                        )
                        is Korrektor.Ergebnis.Fehler -> rueckruf.fehler(auftrag, ergebnis.meldung)
                        Korrektor.Ergebnis.Abgebrochen -> rueckruf.abgebrochen(auftrag)
                    }
                }
            }
        }

        override fun abbrechen(auftrag: Int) {
            if (erlaubt()) Korrektor.abbrechen(auftrag)
        }
    }

    /** Die Tastatur kann inzwischen weg sein; dann hört eben niemand mehr zu. */
    private fun melde(rueckmeldung: () -> Unit) {
        try {
            rueckmeldung()
        } catch (_: RemoteException) {
        }
    }

    private fun erlaubt(): Boolean {
        val uid = Binder.getCallingUid()
        if (uid == Process.myUid()) return true
        val pakete = packageManager.getPackagesForUid(uid) ?: return false
        return pakete.any { it in TASTATUREN && signiertWieFork(it) }
    }

    private fun signiertWieFork(paket: String): Boolean = try {
        val info = packageManager.getPackageInfo(paket, PackageManager.GET_SIGNING_CERTIFICATES)
        val signaturen = info.signingInfo?.apkContentsSigners.orEmpty()
        signaturen.any { s ->
            MessageDigest.getInstance("SHA-256").digest(s.toByteArray())
                .joinToString("") { "%02X".format(it) } == TASTATUR_ZERTIFIKAT
        }
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }
}
