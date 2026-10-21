#!/usr/bin/env python3
"""Vergleicht Modelle auf den zurückgehaltenen Prüfpaaren, auf dem PC.

    python training/bewerten.py lauf/eurollm-fabian/pruefpaare.jsonl ^
        utter-project/EuroLLM-1.7B-Instruct lauf/eurollm-fabian/zusammen

Gezählt wird:
  genau      die Antwort ist Zeichen für Zeichen das Ziel
  in Ruhe    war die Eingabe schon richtig, blieb sie unverändert
  gemeldet   die App hätte die Antwort nicht ungefragt eingesetzt (Wörter weg,
             ersetzt oder neu, Emoji verloren - dieselbe Regel wie Pruefung.kt)
Gierig dekodiert wie in der App.
"""

import json
import re
import sys
from pathlib import Path

import torch
from transformers import AutoModelForCausalLM, AutoTokenizer

from prompt import nachrichten

WORT = re.compile(r"[\w'’]+")
EMOJI = re.compile("[\U0001F000-\U0001FAFF☀-➿⬀-⯿]")


def abstand(a, b):
    vorher = list(range(len(b) + 1))
    for i in range(1, len(a) + 1):
        jetzt = [i] + [0] * len(b)
        for j in range(1, len(b) + 1):
            jetzt[j] = min(jetzt[j - 1] + 1, vorher[j] + 1, vorher[j - 1] + (a[i - 1] != b[j - 1]))
        vorher = jetzt
    return vorher[-1]


def aehnlich(a, b):
    a, b = a.lower(), b.lower()
    return a == b or abstand(a, b) <= (2 if min(len(a), len(b)) >= 5 else 1)


def zu_stark(original, antwort):
    """Wie Pruefung.kt: unscharfe längste gemeinsame Wortfolge, Doppelwörter dürfen weg."""
    wa, wb = WORT.findall(original), WORT.findall(antwort)
    t = [[0] * (len(wb) + 1) for _ in range(len(wa) + 1)]
    for i in range(len(wa) - 1, -1, -1):
        for j in range(len(wb) - 1, -1, -1):
            t[i][j] = t[i + 1][j + 1] + 1 if aehnlich(wa[i], wb[j]) else max(t[i + 1][j], t[i][j + 1])
    passend = t[0][0]
    doppelt = sum(1 for i in range(1, len(wa)) if wa[i].lower() == wa[i - 1].lower())
    if len(wa) - passend > doppelt or len(wb) - passend > 0:
        return True
    emojis = EMOJI.findall(antwort)
    for e in EMOJI.findall(original):
        if e in emojis:
            emojis.remove(e)
        else:
            return True
    laenge = abs(len(antwort) - len(original)) / max(1, len(original))
    return laenge > 0.30 or abstand(original, antwort) / max(1, len(original)) > 0.35


def antworte(tok, modell, eingabe):
    # return_dict: so liefern transformers 4 und 5 dasselbe
    ids = tok.apply_chat_template(nachrichten(eingabe), add_generation_prompt=True, return_tensors="pt",
                                  return_dict=True, enable_thinking=False)["input_ids"].to(modell.device)
    aus = modell.generate(ids, max_new_tokens=len(eingabe) // 2 + 64, do_sample=False)
    text = tok.decode(aus[0][ids.shape[1]:], skip_special_tokens=True)
    return re.sub(r"<think>.*?</think>", "", text, flags=re.S).strip()


def main():
    if len(sys.argv) < 3:
        print(__doc__)
        sys.exit(2)
    paare = [json.loads(z) for z in Path(sys.argv[1]).read_text(encoding="utf-8").splitlines() if z.strip()]
    for name in sys.argv[2:]:
        tok = AutoTokenizer.from_pretrained(name)
        modell = AutoModelForCausalLM.from_pretrained(
            name, dtype=torch.float16 if torch.cuda.is_available() else torch.float32,
            device_map="auto" if torch.cuda.is_available() else None)
        genau = ruhe = ruhe_n = gemeldet = 0
        for p in paare:
            a = antworte(tok, modell, p["eingabe"])
            genau += a == p["ziel"]
            if p["eingabe"] == p["ziel"]:
                ruhe_n += 1
                ruhe += a == p["eingabe"]
            gemeldet += zu_stark(p["eingabe"], a)
        n = max(1, len(paare))
        print(f"{name}: genau {genau / n:.0%}, in Ruhe {ruhe}/{ruhe_n}, gemeldet {gemeldet / n:.0%} ({len(paare)} Paare)")
        del modell
        if torch.cuda.is_available():
            torch.cuda.empty_cache()


if __name__ == "__main__":
    main()
