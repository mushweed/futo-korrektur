// Die Schnittstelle, über die die Tastatur (de.hnshnsen.keyboard) korrigieren
// lässt. Eine Kopie dieser Datei liegt im Fork; beide müssen gleich bleiben.
// Neue Methoden nur ans Ende, und version() hochzählen.
package de.hnshnsen.korrektur;

import de.hnshnsen.korrektur.IKorrekturRueckruf;

interface IKorrektur {
    // 1: vorbereiten, korrigiere, abbrechen
    int version();

    // Modell schon laden, etwa sobald ein Textfeld aufgeht.
    oneway void vorbereiten();

    // Die Antwort kommt über den Rückruf, mit derselben Auftragsnummer.
    oneway void korrigiere(int auftrag, String text, IKorrekturRueckruf rueckruf);

    oneway void abbrechen(int auftrag);
}
