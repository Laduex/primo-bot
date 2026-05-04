from __future__ import annotations

import asyncio
from datetime import UTC, datetime
from unittest.mock import AsyncMock, Mock

from primobot_py.claims import CrossProcessClaimStore
from primobot_py.sales import (
    DispatchResult,
    SalesAggregatorService,
    SalesReportConfig,
    SalesReportExecutorService,
    SalesReportMessageBuilder,
    SalesReportSchedulerService,
)


class _FakeAuthor:
    def __init__(self, author_id: int) -> None:
        self.id = author_id


class _FakeMessage:
    def __init__(self, author_id: int, content: str, created_at: datetime) -> None:
        self.author = _FakeAuthor(author_id)
        self.content = content
        self.created_at = created_at


class _FakeGuild:
    def __init__(self, member_id: int) -> None:
        self.me = _FakeAuthor(member_id)


class _FakeTextChannel:
    def __init__(self, member_id: int, messages: list[_FakeMessage]) -> None:
        self.guild = _FakeGuild(member_id)
        self._messages = messages

    def history(self, limit: int):
        async def _iterate():
            for message in self._messages[:limit]:
                yield message

        return _iterate()


def _build_executor() -> SalesReportExecutorService:
    return SalesReportExecutorService(
        SalesAggregatorService([]),
        SalesReportMessageBuilder(),
        CrossProcessClaimStore(base_path="data/test-sales-dispatch-dedupe"),
    )


def test_recent_dispatch_match_detects_identical_recent_bot_chunks() -> None:
    executor = _build_executor()
    now = datetime.now(UTC)
    channel = _FakeTextChannel(
        member_id=42,
        messages=[
            _FakeMessage(42, "chunk-2", now),
            _FakeMessage(42, "chunk-1", now),
        ],
    )

    matched = asyncio.run(
        executor._matches_recent_bot_dispatch(channel, ["chunk-1", "chunk-2"])
    )

    assert matched is True


def test_recent_dispatch_match_rejects_non_matching_history() -> None:
    executor = _build_executor()
    now = datetime.now(UTC)
    channel = _FakeTextChannel(
        member_id=42,
        messages=[
            _FakeMessage(42, "different", now),
            _FakeMessage(42, "chunk-1", now),
        ],
    )

    matched = asyncio.run(
        executor._matches_recent_bot_dispatch(channel, ["chunk-1", "chunk-2"])
    )

    assert matched is False


def test_scheduler_keeps_slot_reserved_when_duplicate_is_suppressed() -> None:
    now = datetime.now(UTC).astimezone()
    slot = now.strftime("%H:%M")
    today_text = now.date().isoformat()

    config = SalesReportConfig(
        enabled=True,
        timezone=str(now.tzinfo),
        times=[slot],
        overviewTime="",
    )
    config_store = Mock()
    config_store.get_snapshot = AsyncMock(return_value=config)
    config_store.replace_and_persist = AsyncMock()

    executor_service = Mock()
    executor_service.execute = AsyncMock(
        return_value=DispatchResult(
            "DUPLICATE_SUPPRESSED",
            "1493429088176832662",
            "",
            "Duplicate scheduled dispatch suppressed",
            3,
            0,
        )
    )

    claim_store = Mock()
    claim_store.try_claim.return_value = True

    guild = Mock()
    bot = Mock()
    bot.get_guild.return_value = guild

    scheduler = SalesReportSchedulerService(
        bot,
        config_store,
        executor_service,
        "1478671501338214410",
        claim_store,
    )

    asyncio.run(scheduler.run_sales_tick())

    claim_store.try_claim.assert_called_once_with(
        "sales-schedule",
        f"{today_text}|update:{slot}",
        ANY_TIMEDELTA,
    )
    config_store.replace_and_persist.assert_awaited_once()
    claim_store.release.assert_not_called()


class _AnyTimedelta:
    def __eq__(self, other: object) -> bool:
        return other is not None


ANY_TIMEDELTA = _AnyTimedelta()
