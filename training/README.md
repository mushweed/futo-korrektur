# Satzmodell auf eigene Texte feinjustieren (Phase 4)

Auf dem Gaming-PC, mit NVIDIA-Karte. Alles bleibt lokal: Korpus, Protokolle
und Zwischenstände gehören **nicht** ins Repo (`daten/` und `lauf/` stehen in
`.gitignore`).

In der Cloud-Sitzung einmal durchgespielt (21.10.2026, auf dem Prozessor, mit
SmolLM2-135M statt EuroLLM): trainieren, zusammenführen, bewerten, nach GGUF
umwandeln und mit `werkzeug/probe` laden — die Kette läuft. Wie gut das
Ergebnis mit EuroLLM und deinen Daten wird, zeigt erst der Lauf auf dem PC.

## Einmal einrichten (PowerShell)

Zwei Python-Umgebungen, weil der GGUF-Konverter von llama.cpp eine ältere
transformers-Fassung festschreibt (4.57) als das Training braucht (5):

```powershell
cd futo-korrektur
python -m venv .venv          # Training
.venv\Scripts\pip install torch --index-url https://download.pytorch.org/whl/cu124
.venv\Scripts\pip install "transformers>=5" peft datasets accelerate
python -m venv .venv-gguf     # nur für die Umwandlung
.venv-gguf\Scripts\pip install -r llama.cpp\requirements\requirements-convert_hf_to_gguf.txt
```

Unten steht `python` für `.venv\Scripts\python`, außer bei der Umwandlung.

Für `llama-quantize` die passenden Windows-Programme zu llama.cpp **b11490**
von der Release-Seite holen (`llama-b11490-bin-win-cuda-…-x64.zip`) und nach
`werkzeug\llama\` entpacken. Die App ist auf denselben Stand festgelegt.

## Daten zusammentragen

Drei Quellen, alle als JSONL mit `eingabe` und `ziel`:

1. **Aus deinen Texten, künstlich verdorben** — das meiste Material:
   ```powershell
   python training\fehler_einbauen.py D:\korpus\*.txt -o daten\synthetisch.jsonl
   ```
   Die Texte sollten schon richtig sein; gelernt wird, was dort steht.
2. **Von der Tastatur** — so hast du wirklich getippt, so stand es am Ende da.
   Export am Handy unter Einstellungen → Vorhersage → Korrektur und Lernen →
   Exportieren, per USB-Kabel auf den PC, dann im Fork:
   ```powershell
   python ..\futo-fork\lernen\aufbereiten.py tastatur-lernen-*.zip daten\tastatur
   ```
   Davon braucht das Training `satzpaare.jsonl` und `satzkorrekturen.jsonl`
   (aus Letzterer zählen nur übernommene Korrekturen).
3. Optional mehr eigene Texte als Paare „richtig → richtig“, damit das Modell
   Fertiges in Ruhe lässt — `fehler_einbauen.py` erledigt das mit
   `--unveraendert`.

## Trainieren, zusammenführen, prüfen

```powershell
python training\lora_trainieren.py --modell utter-project/EuroLLM-1.7B-Instruct `
    --daten daten\synthetisch.jsonl daten\tastatur\satzpaare.jsonl daten\tastatur\satzkorrekturen.jsonl `
    --ausgabe lauf\eurollm-fabian
python training\zusammenfuehren.py lauf\eurollm-fabian
python training\bewerten.py lauf\eurollm-fabian\pruefpaare.jsonl `
    utter-project/EuroLLM-1.7B-Instruct lauf\eurollm-fabian\zusammen
```

`bewerten.py` vergleicht vorher und nachher auf zurückgehaltenen Paaren:
wie oft genau richtig, wie oft Richtiges unverändert bleibt, und wie oft die
App die Antwort zurückhalten würde (dieselbe Regel wie `Pruefung.kt`).
Besser heißt: „genau“ steigt, „gemeldet“ fällt.

Ein paar tausend Paare reichen für einen spürbaren Unterschied. Mit 12 GB
Grafikspeicher und den Grundeinstellungen (Rang 16, Stapel 8 × 2) rechne ich
mit grob einer halben bis einer Stunde je 10 000 Paare — gemessen ist das nicht.

## Ins GGUF und aufs Handy

```powershell
.venv-gguf\Scripts\python llama.cpp\convert_hf_to_gguf.py lauf\eurollm-fabian\zusammen --outtype f16 --outfile lauf\eurollm-fabian\f16.gguf
werkzeug\llama\llama-quantize.exe lauf\eurollm-fabian\f16.gguf lauf\eurollm-fabian\EuroLLM-fabian.Q4_K_M.gguf Q4_K_M
```

Die Datei per USB aufs Handy, in der App Korrektur „Hinzufügen …“, wählen,
„Prüfsätze durchlaufen“. Das alte Modell kann daneben liegen bleiben, um zu
vergleichen.

## Was gleich bleiben muss

* **Anweisung und Beispiele** liest `prompt.py` direkt aus
  `app/src/main/cpp/kern.cpp`. Wer sie dort ändert, trainiert beim nächsten
  Mal von selbst mit den neuen — ein schon trainiertes Modell kennt aber nur
  die alten.
* Gelernt wird nur die Antwort, nicht der Verlauf davor (`labels = -100`).
* Gierig dekodiert wird in der App wie in `bewerten.py`.
