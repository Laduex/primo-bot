from __future__ import annotations

from datetime import timedelta
import time

from primobot_py.claims import CrossProcessClaimStore


def test_try_claim_blocks_until_release(tmp_path) -> None:
    store = CrossProcessClaimStore(tmp_path)

    assert store.try_claim("sales", "2026-05-04|update:12:00") is True
    assert store.try_claim("sales", "2026-05-04|update:12:00") is False

    store.release("sales", "2026-05-04|update:12:00")

    assert store.try_claim("sales", "2026-05-04|update:12:00") is True


def test_try_claim_reclaims_stale_entries(tmp_path) -> None:
    store = CrossProcessClaimStore(tmp_path)

    assert store.try_claim("sales", "message-1", timedelta(milliseconds=50)) is True
    time.sleep(0.08)

    assert store.try_claim("sales", "message-1", timedelta(milliseconds=50)) is True
