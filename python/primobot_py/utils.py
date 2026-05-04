from __future__ import annotations

from collections.abc import Iterable
from datetime import time
from decimal import Decimal
import re
from zoneinfo import ZoneInfo, ZoneInfoNotFoundError

SNOWFLAKE_RE = re.compile(r"\d+")


def chunk_message(content: str | None, max_length: int) -> list[str]:
    if content is None or not content.strip():
        return ["(no content)"]

    if len(content) <= max_length:
        return [content]

    chunks: list[str] = []
    current: list[str] = []
    current_len = 0

    for line in content.split("\n"):
        candidate_len = len(line) if not current else current_len + 1 + len(line)
        if candidate_len <= max_length:
            current.append(line)
            current_len = candidate_len
            continue

        if current:
            chunks.append("\n".join(current))
            current = []
            current_len = 0

        if len(line) <= max_length:
            current.append(line)
            current_len = len(line)
            continue

        start = 0
        while start < len(line):
            end = min(start + max_length, len(line))
            chunks.append(line[start:end])
            start = end

    if current:
        chunks.append("\n".join(current))

    return chunks or ["(no content)"]


def resolve_zoneinfo(raw_timezone: str | None, fallback: str = "Asia/Manila") -> ZoneInfo:
    try:
        return ZoneInfo((raw_timezone or "").strip() or fallback)
    except ZoneInfoNotFoundError:
        return ZoneInfo(fallback)


def is_valid_timezone(raw_timezone: str | None) -> bool:
    if raw_timezone is None or not raw_timezone.strip():
        return False
    try:
        ZoneInfo(raw_timezone.strip())
        return True
    except ZoneInfoNotFoundError:
        return False


def normalize_snowflake(raw_value: str | None) -> str:
    value = (raw_value or "").strip()
    if not value:
        return ""
    if value.isdigit():
        return value
    match = SNOWFLAKE_RE.search(value)
    return match.group(0) if match else ""


def is_snowflake(raw_value: str | None) -> bool:
    value = (raw_value or "").strip()
    return bool(value and SNOWFLAKE_RE.fullmatch(value))


def normalize_hhmm(raw_value: str | None) -> str:
    value = (raw_value or "").strip()
    if not value:
        return ""
    if not re.fullmatch(r"\d{1,2}:\d{2}", value):
        return ""
    hour_text, minute_text = value.split(":", 1)
    hour = int(hour_text)
    minute = int(minute_text)
    if hour < 0 or hour > 23 or minute < 0 or minute > 59:
        return ""
    return f"{hour:02d}:{minute:02d}"


def parse_hhmm(raw_value: str | None) -> time | None:
    normalized = normalize_hhmm(raw_value)
    if not normalized:
        return None
    hour_text, minute_text = normalized.split(":", 1)
    return time(hour=int(hour_text), minute=int(minute_text))


def format_php(value: Decimal | None) -> str:
    safe = Decimal("0") if value is None else value
    return f"PHP {safe:,.2f}"


def dedupe_keep_order(values: Iterable[str]) -> list[str]:
    seen: set[str] = set()
    ordered: list[str] = []
    for value in values:
        if value in seen:
            continue
        seen.add(value)
        ordered.append(value)
    return ordered
