# Projektwissen: Korrektur

Begleit-App zu Fabians Tastatur-Fork (`mushweed/futo-fork`). Sie korrigiert
einen Text lokal mit einem kleinen Sprachmodell und bietet das der Tastatur
über einen gebundenen Dienst an. Entschieden am 21.10.2026 (Weg A): eigene
App mit aktuellem llama.cpp, nicht FUTOs altes llama.cpp in der Tastatur —
schneller auf dem Gerät, Modell und Engine frei tauschbar, und dieser Code
steht nicht unter FUTOs Lizenz.

**Deutsch, geduzt.** Keine Ausrufezeichen, keine Emoji — in der App wie im
Gespräch.

## Was immer gilt

* **Alles lokal.** Kein `INTERNET` im Manifest, keine Telemetrie, nichts
  wird nachgeladen. llama.cpp wird ohne curl und OpenSSL gebaut
  (`app/src/main/cpp/llama-optionen.cmake`).
* **Modelle liegen nie im Repo** (`*.gguf` steht in `.gitignore`). Die App
  kopiert eine vom Nutzer gewählte Datei nach `files/modelle/`.
* **Nur die Tastatur darf rufen.** `KorrekturDienst` prüft Paketname
  (`de.hnshnsen.keyboard`, auch `.unstable`) und den SHA-256 ihres
  Signierschlüssels (FUTOs `java/shared.keystore`). Ändert sich der
  Schlüssel des Forks, muss `TASTATUR_ZERTIFIKAT` mit.
* **Die AIDL-Dateien gibt es zweimal**, hier und im Fork. Sie müssen gleich
  bleiben. Neue Methoden nur ans Ende, `version()` hochzählen.
* **Texte reisen über JNI als UTF-8-Bytes**, nie über `NewStringUTF` — das
  zerlegt Emojis außerhalb der Grundebene.
* **Abbrechen:** `korrigiere()` im Kern setzt den Abbruch nicht zurück; das
  tut `zuruecksetzen()` vor der Prüfung, ob der Auftrag schon abgebrochen
  ist. Sonst ginge ein Abbruch verloren, der kurz vor dem Start eintrifft.
* **Signiert wird mit `app/korrektur.keystore` aus dem Repo**, Debug wie
  Release — jede Fassung lässt sich über die vorige installieren, egal wo
  gebaut. Das Repo ist privat.
* **llama.cpp ist Submodul**, festgelegt auf `b11490` (07.10.2026). Gebaut
  nur für arm64 mit `armv8.6-a+dotprod+i8mm` (Tensor G4).

## Die Version

Steht nur in `app/build.gradle` (`def VERSION`), Schreibweise `Haupt.Neben`
mit zweistelliger Nebennummer: 0.01, 0.02 … `versionCode` = Haupt × 100 +
Neben. Angehoben wird mit jeder Änderung, die aufs Gerät geht.

## Bauen und prüfen

* **Auf GitHub:** jeder Push baut (`.github/workflows/apk.yml`, auf Fabians
  Wunsch automatisch) und legt die APK als Release `apk-<Zweig>` ab. Fabian
  lädt sie am Pixel aus dem Browser.
* **In einer Cloud-Sitzung:** Android-SDK samt NDK 28.2 und CMake 3.22.1
  installieren, dann `./gradlew testDebugUnitTest assembleDebug`.
  Maven Central antwortet dort bei vielen gleichzeitigen Abrufen mit 429 —
  `--max-workers=1` hilft.
* **Der Kern ohne Android:** `werkzeug/` baut `probe` für den Rechner und
  läuft die Prüfsätze aus `app/src/main/assets/pruefsaetze.txt` durch. So
  lassen sich Prompt und Modelle vergleichen, ohne Handy.

## Prompt und Prüfung

Anweisung und Beispiele stehen in `kern.cpp`. Sie liegen im festen Anfang,
der einmal eingelesen im Speicher bleibt; je Auftrag wird nur der Text
dahinter gelesen. Mehr Beispiele kosten also keine Zeit pro Satz, nur beim
Laden.

Kleine Modelle formulieren trotzdem um. Deshalb entscheidet `Pruefung.kt`,
ob eine Antwort ungefragt eingesetzt werden darf: jedes Wort des Originals
muss sich wiederfinden, höchstens um ein, zwei Buchstaben verändert; ein
gestrichenes Doppelwort ist erlaubt. Weggelassene, ersetzte oder neue Wörter
und verlorene Emojis werden gemeldet (`zuStark` + Hinweis). Ein Emoji am
Satzende, das fehlt, setzt `Nachbearbeitung.kt` von selbst wieder an.

## Messwerte

Probelauf am 21.10.2026 mit den zwölf Prüfsätzen, auf einem x86-Rechner mit
4 Threads (nicht dem Handy):

| Modell | Datei | Laden | je Satz | Qualität |
|---|---|---|---|---|
| EuroLLM-1.7B-Instruct Q4_K_M | 1,0 GB | 3,5 s | ca. 1,1 s | gut: Kommas, dass/das, Groß-/Kleinschreibung. Lässt aber ein Emoji am Ende fallen, streicht einmal „dir“, macht aus „offen“ „geöffnet“ |
| Qwen3-1.7B Q4_K_M | 1,1 GB | 4,2 s | ca. 1,5 s | zu zurückhaltend: viele Fehler bleiben stehen |

**Auf dem Pixel 9 Pro XL, erster Versuch (0.01, 8 Threads):** Einlesen
6,2 Token/s, Antwort 0,8 Token/s — rund zwanzigmal langsamer als erwartet.
Übersetzt war richtig (`-O3`, `armv8.6-a+dotprod+i8mm`). Verdacht: llama.cpp
lässt wartende Threads aktiv kreisen; auf acht Kernen, vier davon Sparkerne,
wartet jeder Schritt auf den langsamsten. Seit 0.02 lassen sich Anzahl,
„nur schnelle Kerne“ und „kreisen“ ohne Neuladen umstellen
(`Kern::threads`, eigener ggml-Threadpool), und „Threads messen“ vergleicht
alle Varianten an einem Satz. Grundeinstellung bis zur Messung: 4 Threads,
nur schnelle Kerne, schlafend.

## Offen

* Die Action „Satz korrigieren“ im Fork (Phase 2) — sie bindet diesen
  Dienst über die Aktion `de.hnshnsen.korrektur.KORRIGIEREN` und braucht im
  Manifest `<queries>` für `de.hnshnsen.korrektur`.
* KleidiAI (schnellere ARM-Kerne in ggml) ist aus: es lädt beim Bauen Quellen
  nach. Lohnt sich vielleicht, wenn die Messwerte vom Gerät da sind.
* Phase 4: das Modell per LoRA auf Fabians eigene Texte feinjustieren —
  damit es Wörter und Emojis zuverlässig stehen lässt.
