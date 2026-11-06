import json
from pathlib import Path

import pytest

FIX = Path(__file__).parent / "fixtures"


@pytest.fixture
def fixtures() -> Path:
    return FIX


def write_json(path: Path, utts, **kw) -> Path:
    path.write_text(
        json.dumps({"transcript": [dict(speaker_id=a, start_ms=b, end_ms=c, text=d) for a, b, c, d in utts]}, ensure_ascii=False),
        encoding="utf-8",
    )
    return path
