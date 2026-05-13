from __future__ import annotations

import asyncio

from primobot_py.ops_alerts import (
    EMBED_DESCRIPTION_LIMIT,
    OpsAlertRequest,
    OpsAlertService,
    chunk_text,
    format_alert,
    normalize_severity,
)


class FakeChannel:
    def __init__(self) -> None:
        self.embeds = []

    async def send(self, *args: object, **kwargs: object) -> None:
        self.embeds.append(kwargs["embed"])


class FakeBot:
    def __init__(self, channel: FakeChannel | None) -> None:
        self.channel = channel

    def get_channel(self, channel_id: int) -> FakeChannel | None:
        return self.channel


def test_normalize_severity_maps_backup_status_words() -> None:
    assert normalize_severity("failed") == "ERROR"
    assert normalize_severity("complete") == "SUCCESS"
    assert normalize_severity("") == "INFO"


def test_format_alert_wraps_backup_messages_for_readability() -> None:
    alert = format_alert(
        OpsAlertRequest(
            severity="success",
            title="SQLite backups completed",
            message="All production SQLite backups completed. bakes: /one; roastery: /two",
        )
    )

    assert alert.title == "[SUCCESS] SQLite backups completed"
    assert alert.message.startswith("```text\n")
    assert "\n- bakes:" in alert.message
    assert "\n- roastery:" in alert.message


def test_chunk_text_splits_long_messages() -> None:
    chunks = chunk_text("x" * (EMBED_DESCRIPTION_LIMIT + 1), EMBED_DESCRIPTION_LIMIT)

    assert len(chunks) == 2
    assert len(chunks[0]) == EMBED_DESCRIPTION_LIMIT
    assert chunks[1] == "x"


def test_ops_alert_service_sends_embed_to_configured_channel() -> None:
    asyncio.run(_test_ops_alert_service_sends_embed_to_configured_channel())


async def _test_ops_alert_service_sends_embed_to_configured_channel() -> None:
    channel = FakeChannel()
    service = OpsAlertService(FakeBot(channel), "1498489442141343954")  # type: ignore[arg-type]

    result = await service.send_alert(
        OpsAlertRequest(
            severity="success",
            title="SQLite backup notification test",
            message="Backup service notification path is working.",
        )
    )

    assert result.sent is True
    assert len(channel.embeds) == 1
    assert channel.embeds[0].title == "[SUCCESS] SQLite backup notification test"
