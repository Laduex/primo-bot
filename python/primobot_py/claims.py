from __future__ import annotations

from dataclasses import asdict, dataclass
from datetime import UTC, datetime, timedelta
import hashlib
import json
import os
from pathlib import Path
from typing import Any


@dataclass(frozen=True, slots=True)
class ClaimRecord:
    key: str
    created_at: str
    expires_at: str | None


class CrossProcessClaimStore:
    def __init__(self, base_path: str | Path | None = None) -> None:
        if base_path is None:
            data_root = Path("/data") if Path("/data").exists() else Path("data")
            base_path = data_root / "coordination"
        self._base_path = Path(base_path)

    def try_claim(
        self, namespace: str, key: str, ttl: timedelta | None = None
    ) -> bool:
        if not namespace.strip() or not key.strip():
            return False

        claim_path = self._claim_path(namespace, key)
        claim_path.parent.mkdir(parents=True, exist_ok=True)
        expires_at = datetime.now(UTC) + ttl if ttl is not None else None
        payload = ClaimRecord(
            key=key,
            created_at=self._timestamp(datetime.now(UTC)),
            expires_at=self._timestamp(expires_at) if expires_at is not None else None,
        )

        for _ in range(2):
            try:
                fd = os.open(
                    claim_path,
                    os.O_CREAT | os.O_EXCL | os.O_WRONLY,
                    0o644,
                )
            except FileExistsError:
                if ttl is None or not self._is_stale(claim_path):
                    return False
                self.release(namespace, key)
                continue

            with os.fdopen(fd, "w", encoding="utf-8") as handle:
                json.dump(asdict(payload), handle)
            return True

        return False

    def release(self, namespace: str, key: str) -> None:
        claim_path = self._claim_path(namespace, key)
        try:
            claim_path.unlink()
        except FileNotFoundError:
            return

    def _is_stale(self, claim_path: Path) -> bool:
        try:
            payload = json.loads(claim_path.read_text(encoding="utf-8"))
        except Exception:
            return True

        expires_at = self._parse_timestamp(payload.get("expires_at"))
        if expires_at is None:
            return False
        return expires_at <= datetime.now(UTC)

    def _claim_path(self, namespace: str, key: str) -> Path:
        safe_namespace = namespace.strip().replace("/", "-")
        digest = hashlib.sha256(key.encode("utf-8")).hexdigest()
        return self._base_path / safe_namespace / f"{digest}.claim"

    @staticmethod
    def _timestamp(value: datetime) -> str:
        return value.astimezone(UTC).isoformat().replace("+00:00", "Z")

    @staticmethod
    def _parse_timestamp(raw: Any) -> datetime | None:
        if not isinstance(raw, str) or not raw.strip():
            return None
        try:
            return datetime.fromisoformat(raw.replace("Z", "+00:00")).astimezone(UTC)
        except ValueError:
            return None
