#!/usr/bin/env python3
"""LoRA-Feinjustierung des Satzmodells auf deine Paare (Phase 4).

    python training/lora_trainieren.py ^
        --modell utter-project/EuroLLM-1.7B-Instruct ^
        --daten daten/synthetisch.jsonl daten/satzpaare.jsonl daten/satzkorrekturen.jsonl ^
        --ausgabe lauf/eurollm-fabian

Daten: JSONL mit {"eingabe", "ziel"} (fehler_einbauen.py, lernen/aufbereiten.py
im Fork) oder die satzkorrekturen.jsonl aus dem Fork - davon zählen nur die
übernommenen. Je Beispiel wird der Verlauf genau so gebaut wie in der App
(prompt.py); gelernt wird nur die Antwort.

Danach: zusammenfuehren.py, dann nach GGUF (siehe README.md).

Braucht: torch (mit CUDA), transformers, peft, datasets, accelerate.
"""

import argparse
import json
import random
from pathlib import Path

import torch
from datasets import Dataset
from peft import LoraConfig, get_peft_model
from transformers import (AutoModelForCausalLM, AutoTokenizer, Trainer, TrainingArguments)

from prompt import nachrichten


def lies_paare(dateien):
    paare = []
    for d in dateien:
        for zeile in Path(d).read_text(encoding="utf-8").splitlines():
            if not zeile.strip():
                continue
            e = json.loads(zeile)
            if "vorschlag" in e:  # satzkorrekturen.jsonl aus dem Fork
                if e.get("ergebnis") == "uebernommen":
                    paare.append((e["original"], e["vorschlag"]))
            else:
                paare.append((e["eingabe"], e["ziel"]))
    return list(dict.fromkeys(paare))  # ohne Doppelte, Reihenfolge bleibt


def baue_beispiel(tok, eingabe, ziel, max_laenge):
    """Token und Labels: nur die Antwort zählt, der Verlauf davor ist -100."""
    extra = {"enable_thinking": False}  # Qwen3 & Co.; andere Vorlagen ignorieren es
    voll = tok.apply_chat_template(nachrichten(eingabe, ziel), tokenize=False, **extra)
    anfang = tok.apply_chat_template(nachrichten(eingabe), tokenize=False, add_generation_prompt=True, **extra)
    if not voll.startswith(anfang):
        raise RuntimeError("Chat-Vorlage: Verlauf mit Antwort beginnt nicht mit dem Verlauf ohne")
    a = tok(anfang, add_special_tokens=False)["input_ids"]
    v = tok(voll, add_special_tokens=False)["input_ids"]
    # Wie in der App: llama.cpp setzt BOS davor, wenn das Modell es verlangt.
    if tok.bos_token_id is not None and getattr(tok, "add_bos_token", False) and (not a or a[0] != tok.bos_token_id):
        a, v = [tok.bos_token_id] + a, [tok.bos_token_id] + v
    if len(v) > max_laenge:
        return None
    return {"input_ids": v, "attention_mask": [1] * len(v), "labels": [-100] * len(a) + v[len(a):]}


def sammler(tok):
    def sammle(stapel):
        laenge = max(len(b["input_ids"]) for b in stapel)
        pad = tok.pad_token_id if tok.pad_token_id is not None else tok.eos_token_id
        def auf(liste, wert):
            return liste + [wert] * (laenge - len(liste))
        return {
            "input_ids": torch.tensor([auf(b["input_ids"], pad) for b in stapel]),
            "attention_mask": torch.tensor([auf(b["attention_mask"], 0) for b in stapel]),
            "labels": torch.tensor([auf(b["labels"], -100) for b in stapel]),
        }
    return sammle


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--modell", default="utter-project/EuroLLM-1.7B-Instruct")
    p.add_argument("--daten", nargs="+", required=True)
    p.add_argument("--ausgabe", required=True)
    p.add_argument("--epochen", type=float, default=2)
    p.add_argument("--lernrate", type=float, default=1e-4)
    p.add_argument("--rang", type=int, default=16)
    p.add_argument("--stapel", type=int, default=8)
    p.add_argument("--sammeln", type=int, default=2, help="Gradient Accumulation")
    p.add_argument("--max-laenge", type=int, default=1024)
    p.add_argument("--pruefanteil", type=float, default=0.05, help="zurückgehalten für bewerten.py")
    p.add_argument("--saat", type=int, default=21)
    a = p.parse_args()

    paare = lies_paare(a.daten)
    random.Random(a.saat).shuffle(paare)
    n_pruef = int(len(paare) * a.pruefanteil)
    pruef, lern = paare[:n_pruef], paare[n_pruef:]
    aus = Path(a.ausgabe)
    aus.mkdir(parents=True, exist_ok=True)
    with open(aus / "pruefpaare.jsonl", "w", encoding="utf-8") as f:
        for e, z in pruef:
            f.write(json.dumps({"eingabe": e, "ziel": z}, ensure_ascii=False) + "\n")

    tok = AutoTokenizer.from_pretrained(a.modell)
    beispiele = [b for b in (baue_beispiel(tok, e, z, a.max_laenge) for e, z in lern) if b]
    print(f"{len(paare)} Paare: {len(beispiele)} zum Lernen, {len(pruef)} zum Prüfen")

    cuda = torch.cuda.is_available()
    bf16 = cuda and torch.cuda.is_bf16_supported()
    modell = AutoModelForCausalLM.from_pretrained(
        a.modell, dtype=torch.bfloat16 if bf16 else (torch.float16 if cuda else torch.float32))
    modell.gradient_checkpointing_enable()
    modell.enable_input_require_grads()
    modell = get_peft_model(modell, LoraConfig(
        r=a.rang, lora_alpha=2 * a.rang, lora_dropout=0.05, task_type="CAUSAL_LM",
        target_modules=["q_proj", "k_proj", "v_proj", "o_proj", "gate_proj", "up_proj", "down_proj"],
    ))
    modell.print_trainable_parameters()

    trainer = Trainer(
        model=modell,
        args=TrainingArguments(
            output_dir=str(aus / "zwischenstaende"),
            num_train_epochs=a.epochen,
            learning_rate=a.lernrate,
            per_device_train_batch_size=a.stapel,
            gradient_accumulation_steps=a.sammeln,
            lr_scheduler_type="cosine",
            # selbst gerechnet: warmup_ratio gibt es in transformers 5 nicht mehr
            warmup_steps=max(1, int(0.03 * a.epochen * len(beispiele) / (a.stapel * a.sammeln))),
            logging_steps=20,
            save_steps=500,
            save_total_limit=2,
            bf16=bf16,
            fp16=cuda and not bf16,
            report_to=[],  # nichts nach außen melden
            remove_unused_columns=False,
            seed=a.saat,
        ),
        train_dataset=Dataset.from_list(beispiele),
        data_collator=sammler(tok),
    )
    trainer.train()
    modell.save_pretrained(aus / "adapter")
    tok.save_pretrained(aus / "adapter")
    (aus / "basis.txt").write_text(a.modell, encoding="utf-8")
    print(f"Adapter -> {aus / 'adapter'}")


if __name__ == "__main__":
    main()
