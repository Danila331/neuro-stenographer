"""Strict validation of the output JSON (ARCHITECTURE.md section 1 + config/transcript.schema.json)."""

from __future__ import annotations

import json
import re
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

from .transcript import StrictJSONError, read_text_strict, strict_loads

SPEAKER_RE = re.compile(r"^speaker_([1-9][0-9]*)$")
KEYS = {"speaker_id", "start_ms", "end_ms", "text"}
# Cyrillic written as a \uXXXX escape inside the raw file (forbidden by the ТЗ contract).
CYR_ESCAPE_RE = re.compile(r"\\u04[0-9a-fA-F]{2}|\\u0301|\\u2116", re.I)


def _find_schema() -> Path | None:
    here = Path(__file__).resolve()
    for p in here.parents:
        cand = p / "config" / "transcript.schema.json"
        if cand.is_file():
            return cand
    return None


# Embedded copy used when the repo layout is not available (e.g. installed wheel).
_EMBEDDED_SCHEMA: dict[str, Any] = {
    "type": "object",
    "additionalProperties": False,
    "required": ["transcript"],
    "properties": {
        "transcript": {
            "type": "array",
            "items": {
                "type": "object",
                "additionalProperties": False,
                "required": ["speaker_id", "start_ms", "end_ms", "text"],
                "properties": {
                    "speaker_id": {"type": "string", "pattern": "^speaker_[1-9][0-9]*$"},
                    "start_ms": {"type": "integer", "minimum": 0},
                    "end_ms": {"type": "integer", "minimum": 1},
                    "text": {"type": "string", "minLength": 1, "pattern": "^\\S(.*\\S)?$"},
                },
            },
        }
    },
}


def load_schema() -> dict[str, Any]:
    p = _find_schema()
    if p is not None:
        return json.loads(p.read_text(encoding="utf-8"))
    return _EMBEDDED_SCHEMA


@dataclass
class ValidationResult:
    errors: list[str] = field(default_factory=list)
    warnings: list[str] = field(default_factory=list)

    @property
    def ok(self) -> bool:
        return not self.errors


def _is_int(v: Any) -> bool:
    return isinstance(v, int) and not isinstance(v, bool)


def validate_text(raw_text: str, duration_ms: int | None = None) -> ValidationResult:
    res = ValidationResult()
    err = res.errors.append

    if CYR_ESCAPE_RE.search(raw_text):
        err("Cyrillic is written with \\u escapes; the file must contain raw UTF-8 characters")

    try:
        obj = strict_loads(raw_text)
    except StrictJSONError as e:
        err(str(e))
        return res
    except json.JSONDecodeError as e:
        err(f"invalid JSON: {e}")
        return res
    except RecursionError:
        err("JSON is nested too deeply")
        return res

    # Layer 1: explicit checks (stricter / better messages than the schema).
    if not isinstance(obj, dict):
        err("top level must be an object {\"transcript\": [...]}")
        return res
    extra = set(obj) - {"transcript"}
    if extra:
        err(f"unexpected top-level keys: {sorted(extra)}")
    items = obj.get("transcript")
    if "transcript" not in obj:
        err("missing top-level key 'transcript'")
        return res
    if not isinstance(items, list):
        err("'transcript' must be an array")
        return res

    seen_order: list[str] = []
    prev: tuple[int, int] | None = None
    for i, it in enumerate(items):
        loc = f"transcript[{i}]"
        if not isinstance(it, dict):
            err(f"{loc}: must be an object")
            continue
        keys = set(it)
        if keys != KEYS:
            missing, extra_k = sorted(KEYS - keys), sorted(keys - KEYS)
            err(f"{loc}: keys must be exactly {sorted(KEYS)} (missing={missing}, extra={extra_k})")
        sp, st, en, tx = (it.get(k) for k in ("speaker_id", "start_ms", "end_ms", "text"))

        if not isinstance(sp, str) or not SPEAKER_RE.match(sp):
            err(f"{loc}: speaker_id must match speaker_N (N>=1), got {sp!r}")
        elif sp not in seen_order:
            seen_order.append(sp)
            want = f"speaker_{len(seen_order)}"
            if sp != want:
                err(
                    f"{loc}: speakers must be numbered by first appearance: "
                    f"{len(seen_order)}-th new speaker is {sp!r}, expected {want!r}"
                )

        ints_ok = True
        for name, v in (("start_ms", st), ("end_ms", en)):
            if not _is_int(v):
                ints_ok = False
                kind = type(v).__name__
                err(f"{loc}: {name} must be an integer (got {kind}: {v!r})")
        if ints_ok:
            if st < 0:
                err(f"{loc}: start_ms {st} < 0")
            if not st < en:
                err(f"{loc}: need start_ms < end_ms (got {st} >= {en})")
            if duration_ms is not None and en > duration_ms:
                err(f"{loc}: end_ms {en} > duration_ms {duration_ms}")
            if prev is not None and (st, en) < prev:
                err(f"{loc}: not sorted by (start_ms, end_ms): {(st, en)} after {prev}")
            prev = (st, en)

        if not isinstance(tx, str):
            err(f"{loc}: text must be a string")
        else:
            if tx == "" or tx.strip() == "":
                err(f"{loc}: text must be non-empty")
            elif tx != tx.strip():
                err(f"{loc}: text has leading/trailing whitespace: {tx!r}")
            if "\n" in tx or "\r" in tx:
                err(f"{loc}: text contains a line break")

    # Layer 2: JSON schema (config/transcript.schema.json) as a cross-check; it can only add
    # errors the explicit checks missed, so it runs only when they found nothing.
    if res.errors:
        return res
    try:
        import jsonschema

        validator = jsonschema.Draft202012Validator(load_schema())
        for e in sorted(validator.iter_errors(obj), key=lambda e: list(map(str, e.absolute_path))):
            path = "$" + "".join(f"[{p!r}]" if isinstance(p, str) else f"[{p}]" for p in e.absolute_path)
            err(f"schema: {path}: {e.message[:160]}")
    except ImportError:  # pragma: no cover
        res.warnings.append("jsonschema not installed; schema layer skipped")
    return res


def validate_file(path: Path, duration_ms: int | None = None) -> ValidationResult:
    try:
        text = read_text_strict(path)
    except FileNotFoundError:
        r = ValidationResult()
        r.errors.append(f"file not found: {path}")
        return r
    except StrictJSONError as e:
        r = ValidationResult()
        r.errors.append(str(e))
        return r
    return validate_text(text, duration_ms)
