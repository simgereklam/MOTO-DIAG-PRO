#!/usr/bin/env python3
"""Train a tiny Turkish Seyfi language model FROM RANDOM INITIALIZATION.

Tokenizer: UTF-8 byte tokenizer implemented here (no pretrained tokenizer).
Network: causal Transformer implemented with PyTorch (no pretrained weights).
Input: one Turkish training document per line, or JSONL with a "text" field.
This script creates training code only; it does not claim a model is trained.
"""
from __future__ import annotations
import argparse
import json
import math
import random
from pathlib import Path

import torch
from torch import nn

PAD, BOS, EOS = 0, 1, 2
VOCAB_SIZE = 259  # 3 special tokens + all 256 possible UTF-8 bytes


def encode(text: str, add_bos: bool = True, add_eos: bool = True) -> list[int]:
    ids = [b + 3 for b in text.encode("utf-8")]
    return ([BOS] if add_bos else []) + ids + ([EOS] if add_eos else [])


def decode(ids: list[int]) -> str:
    raw = bytes(i - 3 for i in ids if 3 <= i < VOCAB_SIZE)
    return raw.decode("utf-8", errors="replace")


def read_corpus(path: Path) -> str:
    docs: list[str] = []
    for line in path.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line:
            continue
        if path.suffix.lower() in {".jsonl", ".ndjson"}:
            item = json.loads(line)
            text = str(item.get("text", "")).strip()
        else:
            text = line
        if text:
            docs.append(text)
    if not docs:
        raise SystemExit("Corpus is empty. Add real Turkish training documents.")
    return "\n\n".join(docs)


class SeyfiTransformer(nn.Module):
    def __init__(self, width: int = 192, layers: int = 4, heads: int = 4,
                 max_seq: int = 512, dropout: float = 0.1):
        super().__init__()
        if width % heads:
            raise ValueError("width must be divisible by heads")
        self.max_seq = max_seq
        self.token = nn.Embedding(VOCAB_SIZE, width)
        self.position = nn.Embedding(max_seq, width)
        block = nn.TransformerEncoderLayer(
            d_model=width, nhead=heads, dim_feedforward=4 * width,
            dropout=dropout, activation="gelu", batch_first=True, norm_first=True
        )
        self.blocks = nn.TransformerEncoder(block, num_layers=layers)
        self.norm = nn.LayerNorm(width)
        self.output = nn.Linear(width, VOCAB_SIZE, bias=False)
        self.output.weight = self.token.weight

    def forward(self, tokens: torch.Tensor) -> torch.Tensor:
        batch, length = tokens.shape
        if length > self.max_seq:
            raise ValueError("sequence exceeds max_seq")
        positions = torch.arange(length, device=tokens.device).unsqueeze(0)
        x = self.token(tokens) + self.position(positions)
        causal = torch.triu(
            torch.ones(length, length, device=tokens.device, dtype=torch.bool),
            diagonal=1
        )
        x = self.blocks(x, mask=causal)
        return self.output(self.norm(x))


@torch.no_grad()
def generate(model: SeyfiTransformer, prompt: str, device: torch.device,
             max_new: int = 300, temperature: float = 0.8) -> str:
    model.eval()
    ids = encode(prompt, add_bos=True, add_eos=False)
    for _ in range(max_new):
        context = torch.tensor([ids[-model.max_seq:]], dtype=torch.long, device=device)
        logits = model(context)[0, -1] / max(temperature, 0.05)
        probs = torch.softmax(logits, dim=-1)
        next_id = int(torch.multinomial(probs, num_samples=1).item())
        if next_id == EOS:
            break
        ids.append(next_id)
    return decode(ids[1:])


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--data", required=True, help="UTF-8 .txt or JSONL corpus")
    ap.add_argument("--out", default="seyfi_transformer.pt")
    ap.add_argument("--steps", type=int, default=20000)
    ap.add_argument("--batch", type=int, default=16)
    ap.add_argument("--seq", type=int, default=512)
    ap.add_argument("--width", type=int, default=192)
    ap.add_argument("--layers", type=int, default=4)
    ap.add_argument("--heads", type=int, default=4)
    ap.add_argument("--lr", type=float, default=3e-4)
    ap.add_argument("--device", default="auto", choices=["auto", "cpu", "cuda", "mps"])
    ap.add_argument("--sample", default="Seyfi Usta, motosiklet neden tekliyor?")
    args = ap.parse_args()

    random.seed(42)
    torch.manual_seed(42)
    if args.device == "auto":
        device = torch.device("cuda" if torch.cuda.is_available() else
                              "mps" if torch.backends.mps.is_available() else "cpu")
    else:
        device = torch.device(args.device)
    text = read_corpus(Path(args.data))
    tokens = torch.tensor(encode(text), dtype=torch.long)
    if len(tokens) < args.seq + 2:
        raise SystemExit(f"Corpus too small: {len(tokens)} tokens; need more than {args.seq + 2}.")
    split = int(len(tokens) * 0.95)
    train_data, valid_data = tokens[:split], tokens[split:]
    if len(valid_data) < args.seq + 2:
        raise SystemExit("Validation text too short; add more independent training text.")

    model = SeyfiTransformer(args.width, args.layers, args.heads, args.seq).to(device)
    optimizer = torch.optim.AdamW(model.parameters(), lr=args.lr, weight_decay=0.1)
    print(f"Training from random initialization on {device}; bytes={len(tokens):,}; params={sum(p.numel() for p in model.parameters()):,}")

    def batch(source: torch.Tensor) -> tuple[torch.Tensor, torch.Tensor]:
        starts = torch.randint(0, len(source) - args.seq - 1, (args.batch,))
        x = torch.stack([source[i:i + args.seq] for i in starts]).to(device)
        y = torch.stack([source[i + 1:i + args.seq + 1] for i in starts]).to(device)
        return x, y

    for step in range(1, args.steps + 1):
        model.train()
        x, y = batch(train_data)
        logits = model(x)
        loss = nn.functional.cross_entropy(logits.reshape(-1, VOCAB_SIZE), y.reshape(-1))
        optimizer.zero_grad(set_to_none=True)
        loss.backward()
        nn.utils.clip_grad_norm_(model.parameters(), 1.0)
        optimizer.step()

        if step == 1 or step % 250 == 0:
            model.eval()
            with torch.no_grad():
                vx, vy = batch(valid_data)
                val_loss = nn.functional.cross_entropy(model(vx).reshape(-1, VOCAB_SIZE), vy.reshape(-1))
            print(f"step={step} train_loss={loss.item():.4f} val_loss={val_loss.item():.4f}")

    checkpoint = {
        "model_state": model.cpu().state_dict(),
        "config": {"vocab_size": VOCAB_SIZE, "width": args.width, "layers": args.layers,
                   "heads": args.heads, "max_seq": args.seq, "tokenizer": "seyfi_utf8_byte_v1"},
        "training": {"steps": args.steps, "corpus_bytes": len(tokens), "random_initialization": True},
        "warning": "Experimental from-scratch language model. Not a validated motorcycle diagnostic authority."
    }
    torch.save(checkpoint, args.out)
    print("Saved own from-scratch checkpoint:", args.out)
    model.to(device)
    print("Sample:", generate(model, args.sample, device))


if __name__ == "__main__":
    main()
