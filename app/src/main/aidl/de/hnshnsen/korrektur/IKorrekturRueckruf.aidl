package de.hnshnsen.korrektur;

oneway interface IKorrekturRueckruf {
    // zuStark: Die Antwort weicht so weit ab, dass sie nicht ungefragt
    // eingesetzt werden soll; hinweis sagt, warum. messwerte ist Klartext.
    void fertig(int auftrag, String korrigiert, boolean zuStark, String hinweis, String messwerte);

    void abgebrochen(int auftrag);

    void fehler(int auftrag, String meldung);
}
