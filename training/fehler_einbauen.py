#!/usr/bin/env python3
"""Macht aus sauberem Text Paare "mit Fehlern -> richtig" (Phase 4).

    python training/fehler_einbauen.py korpus/*.txt -o daten/synthetisch.jsonl

Eingabe: Textdateien, je Zeile ein Satz oder Absatz, so wie du schreibst
(Nachrichten, Mails). Sie sollten schon richtig sein - das Modell lernt, was
hier steht. Ausgabe: JSONL mit {"eingabe": ..., "ziel": ...}.

Eingebaut wird, was beim Tippen auf dem Handy passiert und was die
Satzkorrektur beheben soll: Kleinschreibung, fehlende Kommas, das/dass,
Nachbartasten (QWERTZ), Buchstabendreher, verschluckte und doppelte
Buchstaben, ss statt ß, fehlender Punkt am Ende. Nicht angefasst werden
Emojis, Zahlen und Wörter mit Großbuchstaben im Inneren (Namen wie "McDonalds",
Abkürzungen wie "LG"): die soll das Modell gerade stehen lassen.

Ein Teil der Zeilen bleibt unverändert, damit das Modell auch lernt, richtigen
Text in Ruhe zu lassen.

Nur Standardbibliothek.
"""

import argparse
import json
import random
import re
from pathlib import Path

# QWERTZ, wie auf dem Handy; je Taste die direkten Nachbarn.
REIHEN = ["qwertzuiopü", "asdfghjklöä", "yxcvbnm"]
NACHBARN = {}
for r, reihe in enumerate(REIHEN):
    for i, c in enumerate(reihe):
        n = set()
        for dr in (-1, 0, 1):
            rr = r + dr
            if 0 <= rr < len(REIHEN):
                for di in (-1, 0, 1):
                    ii = i + di + (0 if dr == 0 else (1 if dr == 1 and r == 0 else 0))
                    if 0 <= ii < len(REIHEN[rr]) and REIHEN[rr][ii] != c:
                        n.add(REIHEN[rr][ii])
        NACHBARN[c] = sorted(n)

WORT = re.compile(r"[A-Za-zÄÖÜäöüß]+")


def tippfehler(wort: str, z: random.Random) -> str:
    """Ein Fehler, wie ihn ein Daumen macht."""
    if len(wort) < 3:
        return wort
    i = z.randrange(len(wort))
    c = wort[i]
    art = z.random()
    if art < 0.45 and c.lower() in NACHBARN:          # Nachbartaste
        n = z.choice(NACHBARN[c.lower()])
        return wort[:i] + (n.upper() if c.isupper() else n) + wort[i + 1:]
    if art < 0.65 and i < len(wort) - 1:               # Dreher
        return wort[:i] + wort[i + 1] + wort[i] + wort[i + 2:]
    if art < 0.85:                                     # verschluckt
        return wort[:i] + wort[i + 1:]
    return wort[:i] + c + wort[i:]                     # doppelt


def verderbe(zeile: str, z: random.Random, staerke: float) -> str:
    text = zeile

    # Satzanfang und Substantive klein
    if z.random() < 0.6 * staerke:
        text = re.sub(r"(^|[.!?]\s+)([A-ZÄÖÜ])(?=[a-zäöüß])", lambda m: m.group(1) + m.group(2).lower(), text)
    if z.random() < 0.5 * staerke:
        text = re.sub(r"\b([A-ZÄÖÜ][a-zäöüß]+)\b",
                      lambda m: m.group(1).lower() if z.random() < 0.7 else m.group(1), text)

    # Kommas weg
    if z.random() < 0.6 * staerke:
        text = re.sub(r",(\s)", lambda m: m.group(1) if z.random() < 0.8 else "," + m.group(1), text)

    # das/dass
    if z.random() < 0.4 * staerke:
        text = re.sub(r"\bdass\b", "das", text) if z.random() < 0.7 else re.sub(r"\bdas\b", "dass", text, count=1)

    # ß -> ss
    if z.random() < 0.2 * staerke:
        text = text.replace("ß", "ss")

    # Tippfehler in einigen Wörtern (keine Namen mit Großbuchstaben im Inneren)
    def vielleicht(m):
        w = m.group(0)
        if any(c.isupper() for c in w[1:]) or z.random() > 0.12 * staerke:
            return w
        return tippfehler(w, z)
    text = WORT.sub(vielleicht, text)

    # Punkt am Ende weg
    if z.random() < 0.4 * staerke:
        text = re.sub(r"[.]\s*$", "", text)

    return text


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("dateien", nargs="+")
    p.add_argument("-o", "--ausgabe", required=True)
    p.add_argument("--je-zeile", type=int, default=2, help="so viele Fehlerfassungen je Zeile")
    p.add_argument("--unveraendert", type=float, default=0.15, help="Anteil Zeilen ohne Fehler")
    p.add_argument("--min-woerter", type=int, default=3)
    p.add_argument("--max-zeichen", type=int, default=600)
    p.add_argument("--saat", type=int, default=21)
    a = p.parse_args()

    z = random.Random(a.saat)
    n = 0
    with open(a.ausgabe, "w", encoding="utf-8") as aus:
        for datei in a.dateien:
            for zeile in Path(datei).read_text(encoding="utf-8").splitlines():
                zeile = zeile.strip()
                if len(zeile.split()) < a.min_woerter or len(zeile) > a.max_zeichen:
                    continue
                if z.random() < a.unveraendert:
                    paare = [zeile]
                else:
                    paare = {verderbe(zeile, z, z.uniform(0.5, 1.5)) for _ in range(a.je_zeile)}
                for eingabe in paare:
                    aus.write(json.dumps({"eingabe": eingabe, "ziel": zeile, "quelle": "synthetisch"},
                                         ensure_ascii=False) + "\n")
                    n += 1
    print(f"{n} Paare -> {a.ausgabe}")


if __name__ == "__main__":
    main()
