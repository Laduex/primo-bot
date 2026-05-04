from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime
from zoneinfo import ZoneInfo

import discord


MANILA_ZONE = ZoneInfo("Asia/Manila")
EMBED_DESCRIPTION_LIMIT = 3800


@dataclass(frozen=True, slots=True)
class OpsAlertRequest:
    severity: str = "info"
    title: str = "Production alert"
    message: str = "(no details)"


@dataclass(frozen=True, slots=True)
class OpsAlertResult:
    sent: bool
    message: str


class OpsAlertService:
    def __init__(self, bot: discord.Client, target_channel_id: str) -> None:
        self._bot = bot
        self._target_channel_id = (target_channel_id or "").strip()

    async def send_alert(self, request: OpsAlertRequest) -> OpsAlertResult:
        if not self._target_channel_id:
            return OpsAlertResult(False, "OPS_ALERT_CHANNEL_ID is not configured")

        target = self._bot.get_channel(int(self._target_channel_id))
        if not isinstance(target, (discord.TextChannel, discord.Thread)):
            return OpsAlertResult(False, "target Discord channel was not found")

        alert = self._format_alert(request)
        for index, chunk in enumerate(_chunk_text(alert.message, EMBED_DESCRIPTION_LIMIT), start=1):
            title = alert.title if index == 1 else f"{alert.title} (continued)"
            embed = discord.Embed(title=title, description=chunk, color=alert.color)
            embed.add_field(name="Severity", value=alert.severity, inline=True)
            embed.add_field(name="Time", value=alert.timestamp, inline=True)
            embed.set_footer(text="Production operations alert")
            await target.send(embed=embed)

        return OpsAlertResult(True, "alert sent")

    def _format_alert(self, request: OpsAlertRequest) -> "_AlertView":
        severity = _normalize_severity(request.severity)
        title = _clean(request.title, "Production alert")
        message = _format_message(_clean(request.message, "(no details)"))
        timestamp = datetime.now(MANILA_ZONE).strftime("%b %-d, %Y %-I:%M %p PHT")
        return _AlertView(
            title=f"[{severity}] {title}",
            severity=severity,
            timestamp=timestamp,
            message=message,
            color=_color_for(severity),
        )


@dataclass(frozen=True, slots=True)
class _AlertView:
    title: str
    severity: str
    timestamp: str
    message: str
    color: int


def _clean(value: str | None, fallback: str) -> str:
    if value is None or not value.strip():
        return fallback
    return value.strip()


def _normalize_severity(severity: str | None) -> str:
    value = _clean(severity, "info").lower()
    if value in {"critical", "fatal"}:
        return "CRITICAL"
    if value in {"error", "failed", "failure"}:
        return "ERROR"
    if value in {"warning", "warn"}:
        return "WARNING"
    if value in {"success", "ok", "complete", "completed"}:
        return "SUCCESS"
    return "INFO"


def _color_for(severity: str) -> int:
    if severity in {"CRITICAL", "ERROR"}:
        return 0xC42B1C
    if severity == "WARNING":
        return 0xD68800
    if severity == "SUCCESS":
        return 0x238636
    return 0x1F6FEB


def _format_message(message: str) -> str:
    normalized = (
        message.replace("; roastery:", "\n- roastery:")
        .replace("; finlandia:", "\n- finlandia:")
        .replace("; jimenez:", "\n- jimenez:")
        .replace("; recipe-calculator:", "\n- recipe-calculator:")
        .replace(" bakes:", "\n- bakes:")
        .replace(". Failed apps:", ".\n\nFailed apps:")
        .replace(". Inspect manifests", ".\nInspect manifests")
    )
    if normalized != message or "backup" in normalized.lower():
        return f"```text\n{normalized}\n```"
    return normalized


def _chunk_text(value: str, limit: int) -> list[str]:
    if len(value) <= limit:
        return [value]
    chunks: list[str] = []
    start = 0
    while start < len(value):
        chunks.append(value[start : start + limit])
        start += limit
    return chunks
