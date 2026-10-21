"""Anweisung und Beispiele, genau wie die App sie nutzt.

Gelesen wird direkt aus app/src/main/cpp/kern.cpp, damit Training und App
nie auseinanderlaufen: ein Modell, das mit anderer Anweisung trainiert wurde,
als es auf dem Handy bekommt, lernt das Falsche.
"""

import re
from pathlib import Path

KERN = Path(__file__).resolve().parent.parent / "app" / "src" / "main" / "cpp" / "kern.cpp"

_LITERAL = r'"(?:[^"\\]|\\.)*"'


def _text(literale: str) -> str:
    """Verbindet C-Zeichenkettenliterale ("a" "b") und löst Escapes auf."""
    teile = re.findall(_LITERAL, literale)
    roh = "".join(t[1:-1] for t in teile)
    return (roh.replace('\\"', '"').replace("\\n", "\n").replace("\\t", "\t")
            .replace("\\x01", "\x01").replace("\\\\", "\\"))


def lade():
    quelle = KERN.read_text(encoding="utf-8")
    a = re.search(r"const char \*ANWEISUNG =(.*?);", quelle, re.S)
    b = re.search(r"const char \*BEISPIELE\[\]\[2\] = \{(.*?)\n\};", quelle, re.S)
    if not a or not b:
        raise RuntimeError(f"Anweisung oder Beispiele in {KERN} nicht gefunden")
    paar = re.compile(r"\{\s*((?:%s\s*)+),\s*((?:%s\s*)+)\}" % (_LITERAL, _LITERAL))
    beispiele = [(_text(x), _text(y)) for x, y in paar.findall(b.group(1))]
    return _text(a.group(1)), beispiele


def nachrichten(eingabe: str, ziel: str | None = None):
    """Der Verlauf, wie die App ihn baut: Anweisung, Beispiele, dann der Text."""
    anweisung, beispiele = lade()
    verlauf = [{"role": "system", "content": anweisung}]
    for frage, antwort in beispiele:
        verlauf += [{"role": "user", "content": frage}, {"role": "assistant", "content": antwort}]
    verlauf.append({"role": "user", "content": eingabe})
    if ziel is not None:
        verlauf.append({"role": "assistant", "content": ziel})
    return verlauf


if __name__ == "__main__":
    anweisung, beispiele = lade()
    print(anweisung, "\n")
    for frage, antwort in beispiele:
        print(f"{frage!r}\n  -> {antwort!r}")
