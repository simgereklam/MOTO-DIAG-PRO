#!/usr/bin/env python3
"""Train a first Seyfi Usta fault classifier from REAL, labelled ECU sessions.

Input CSV: one row per time sample, columns:
session_id,fault_label,timestamp,rpm,tps,coolant,map,voltage,stft,ltft,...
Do not label synthetic or guessed readings as real. Split by session_id to prevent
samples from the same ride leaking into both train and test sets.
"""
from __future__ import annotations
import argparse
from pathlib import Path
import json
import pandas as pd
from sklearn.ensemble import RandomForestClassifier, ExtraTreesClassifier
from sklearn.impute import SimpleImputer
from sklearn.pipeline import Pipeline
from sklearn.metrics import classification_report, confusion_matrix, accuracy_score
from sklearn.model_selection import GroupShuffleSplit
import joblib

META = {"session_id", "fault_label", "timestamp", "vehicle", "model_year", "dtc"}

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--csv", required=True, help="Labelled ECU sample CSV")
    ap.add_argument("--out", default="seyfi_fault_model.joblib")
    ap.add_argument("--trees", type=int, default=400)
    args = ap.parse_args()

    df = pd.read_csv(args.csv)
    required = {"session_id", "fault_label"}
    missing = required - set(df.columns)
    if missing:
        raise SystemExit(f"Missing columns: {sorted(missing)}")
    df = df.dropna(subset=["session_id", "fault_label"])
    df = df[df.fault_label.astype(str).str.strip().ne("")]
    feature_cols = [c for c in df.columns if c not in META and pd.api.types.is_numeric_dtype(df[c])]
    if not feature_cols:
        raise SystemExit("No numeric sensor columns found.")
    counts = df.groupby("fault_label").session_id.nunique()
    too_small = counts[counts < 3]
    if not too_small.empty:
        raise SystemExit("Need at least 3 independent labelled sessions per fault class before training: " + too_small.to_dict().__str__())

    X = df[feature_cols]
    y = df["fault_label"].astype(str)
    groups = df["session_id"].astype(str)
    split = GroupShuffleSplit(n_splits=1, test_size=0.25, random_state=42)
    train_idx, test_idx = next(split.split(X, y, groups))
    model = Pipeline([
        ("imputer", SimpleImputer(strategy="median")),
        ("classifier", ExtraTreesClassifier(
            n_estimators=args.trees, class_weight="balanced",
            min_samples_leaf=2, random_state=42, n_jobs=-1
        ))
    ])
    model.fit(X.iloc[train_idx], y.iloc[train_idx])
    pred = model.predict(X.iloc[test_idx])
    print("Held-out session accuracy:", round(accuracy_score(y.iloc[test_idx], pred), 4))
    print(classification_report(y.iloc[test_idx], pred, zero_division=0))
    print("Confusion matrix labels:", sorted(y.unique()))
    print(confusion_matrix(y.iloc[test_idx], pred, labels=sorted(y.unique())))
    bundle = {"model": model, "features": feature_cols, "classes": sorted(y.unique()),
              "training_sessions": int(groups.iloc[train_idx].nunique()),
              "test_sessions": int(groups.iloc[test_idx].nunique()),
              "warning": "Experimental motorcycle classifier. Never use as sole evidence for part replacement or riding safety."}
    joblib.dump(bundle, args.out)
    Path(args.out + ".json").write_text(json.dumps({k:v for k,v in bundle.items() if k not in {"model"}}, indent=2), encoding="utf-8")
    print("Saved:", args.out)

if __name__ == "__main__":
    main()
