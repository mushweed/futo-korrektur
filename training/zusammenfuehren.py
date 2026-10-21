#!/usr/bin/env python3
"""Führt den LoRA-Adapter mit dem Grundmodell zusammen, als normales Modell.

    python training/zusammenfuehren.py lauf/eurollm-fabian

Ergebnis: lauf/eurollm-fabian/zusammen/ - daraus wird mit llama.cpp ein GGUF
(siehe README.md). Die App braucht damit keine Adapter-Unterstützung.
"""

import shutil
import sys
from pathlib import Path

import torch
from peft import PeftModel
from huggingface_hub import snapshot_download
from transformers import AutoModelForCausalLM

# Unverändert aus dem Grundmodell kopiert, nicht neu geschrieben: der
# GGUF-Konverter von llama.cpp verlangt eine ältere transformers-Fassung als
# das Training, und die liest einen von transformers 5 geschriebenen
# Tokenizer nicht.
TOKENIZER = ["tokenizer.json", "tokenizer_config.json", "special_tokens_map.json", "tokenizer.model",
             "chat_template.jinja", "added_tokens.json", "vocab.json", "merges.txt"]


def main():
    if len(sys.argv) != 2:
        print(__doc__)
        sys.exit(2)
    lauf = Path(sys.argv[1])
    basis = (lauf / "basis.txt").read_text(encoding="utf-8").strip()
    modell = AutoModelForCausalLM.from_pretrained(basis, dtype=torch.float16)
    modell = PeftModel.from_pretrained(modell, lauf / "adapter").merge_and_unload()
    ziel = lauf / "zusammen"
    modell.save_pretrained(ziel, safe_serialization=True)
    quelle = Path(basis) if Path(basis).is_dir() else Path(snapshot_download(basis, allow_patterns=TOKENIZER))
    for name in TOKENIZER:
        if (quelle / name).exists():
            shutil.copy(quelle / name, ziel / name)
    print(f"Zusammengeführt -> {ziel}")


if __name__ == "__main__":
    main()
