from __future__ import annotations

import asyncio
from datetime import datetime
from pathlib import Path

from primobot_py.claims import CrossProcessClaimStore
from primobot_py.meta_unread import (
    MetaGraphApiClient,
    MetaGraphApiException,
    MetaPageAccess,
    MetaUnreadConfig,
    MetaUnreadConfigStore,
    MetaUnreadConversation,
    MetaUnreadMessageBuilder,
    MetaUnreadSchedulerService,
    MetaUnreadSnapshot,
)


def test_meta_unread_store_initializes_with_expected_defaults(tmp_path: Path) -> None:
    asyncio.run(_test_meta_unread_store_initializes_with_expected_defaults(tmp_path))


async def _test_meta_unread_store_initializes_with_expected_defaults(tmp_path: Path) -> None:
    config_path = tmp_path / "meta-unread-config.json"
    store = MetaUnreadConfigStore(
        str(config_path),
        True,
        20,
        "1494175620287041536",
    )

    await store.initialize()

    config = await store.get_snapshot()
    assert config_path.exists()
    assert config.enabled is True
    assert config.targetChannelId == "1494175620287041536"
    assert config.intervalMinutes == 20
    assert config.lastRunAtEpochMs == 0


def test_meta_unread_store_normalizes_config(tmp_path: Path) -> None:
    asyncio.run(_test_meta_unread_store_normalizes_config(tmp_path))


async def _test_meta_unread_store_normalizes_config(tmp_path: Path) -> None:
    config_path = tmp_path / "meta-unread-config.json"
    store = MetaUnreadConfigStore(
        str(config_path),
        False,
        15,
        "",
    )
    await store.initialize()

    await store.replace_and_persist(
        MetaUnreadConfig(
            enabled=True,
            targetChannelId=" bad ",
            intervalMinutes=999,
            lastRunAtEpochMs=-10,
        )
    )

    reloaded = await store.get_snapshot()
    assert reloaded.enabled is True
    assert reloaded.targetChannelId == ""
    assert reloaded.intervalMinutes == 60
    assert reloaded.lastRunAtEpochMs == 0


def test_meta_unread_message_builder_formats_digest() -> None:
    run_at = datetime.fromisoformat("2026-04-30T10:00:00+08:00")
    snapshot = MetaUnreadSnapshot(
        pages_scanned=2,
        unread_thread_count=2,
        unread_message_count=7,
        conversations=[
            MetaUnreadConversation(
                page_id="page-1",
                page_name="Primo Cafe",
                platform="facebook",
                conversation_id="conv-1",
                sender_name="Alex",
                snippet="We've received your message and will get back to you shortly.",
                unread_count=5,
                updated_time="2026-04-30T01:30:00+00:00",
            ),
            MetaUnreadConversation(
                page_id="page-2",
                page_name="Primo Roastery",
                platform="instagram",
                conversation_id="conv-2",
                sender_name="Bianca",
                snippet="Need help with my order",
                unread_count=2,
                updated_time="2026-04-29T22:00:00+00:00",
            ),
        ],
        warnings=["Instagram unread check failed for `Primo Roastery`: status=400 code=190"],
    )

    content = MetaUnreadMessageBuilder().build_digest(snapshot, run_at)

    assert "**Meta Unread Digest**" in content
    assert "- Messenger: threads `1`, messages `5`" in content
    assert "- Instagram: threads `1`, messages `2`" in content
    assert "Snippet: (auto-reply acknowledgement)" in content
    assert "Days Ago: `30m`" in content
    assert "Days Ago: `4h`" in content
    assert "**Instagram Warnings**" in content


def test_meta_graph_api_client_retries_timeout_error() -> None:
    client = _RetryingMetaGraphApiClient(
        "https://graph.facebook.com",
        "v24.0",
        "user-token",
        "",
    )

    unread = client.list_unread_conversations(
        MetaPageAccess(
            page_id="297424713461571",
            page_name="Primal Brew Roastery",
            page_access_token="page-token",
            instagram_account_id="",
            instagram_username="",
        ),
        "instagram",
    )

    assert len(unread) == 1
    assert unread[0].conversation_id == "ig-1"
    assert client.attempts == 3


def test_meta_graph_api_client_does_not_retry_permission_error() -> None:
    client = _PermissionErrorMetaGraphApiClient(
        "https://graph.facebook.com",
        "v24.0",
        "user-token",
        "",
    )

    try:
        client.list_unread_conversations(
            MetaPageAccess(
                page_id="297424713461571",
                page_name="Primal Brew Roastery",
                page_access_token="page-token",
                instagram_account_id="",
                instagram_username="",
            ),
            "instagram",
        )
    except MetaGraphApiException as ex:
        assert ex.code == 230
    else:
        raise AssertionError("Expected MetaGraphApiException")

    assert client.attempts == 1


def test_meta_unread_scheduler_due_logic() -> None:
    scheduler = MetaUnreadSchedulerService(
        bot=_StubBot(),
        config_store=_StubStore(),
        executor_service=_StubExecutor(),
        default_guild_id="",
        claim_store=CrossProcessClaimStore(),
    )

    assert scheduler.is_due(MetaUnreadConfig(lastRunAtEpochMs=0, intervalMinutes=15), 10_000) is True
    assert (
        scheduler.is_due(
            MetaUnreadConfig(lastRunAtEpochMs=60_000, intervalMinutes=15),
            60_000 + 14 * 60_000,
        )
        is False
    )
    assert (
        scheduler.is_due(
            MetaUnreadConfig(lastRunAtEpochMs=60_000, intervalMinutes=15),
            60_000 + 15 * 60_000,
        )
        is True
    )


class _StubBot:
    guilds: list[object] = []

    def get_guild(self, _: int) -> None:
        return None


class _StubStore:
    async def get_snapshot(self) -> MetaUnreadConfig:
        return MetaUnreadConfig()

    async def replace_and_persist(self, updated_config: MetaUnreadConfig) -> MetaUnreadConfig:
        return updated_config


class _StubExecutor:
    async def execute(self, guild: object, config: MetaUnreadConfig) -> object:
        return object()


class _RetryingMetaGraphApiClient(MetaGraphApiClient):
    def __init__(self, api_base_url: str, graph_version: str, user_access_token: str, app_secret: str) -> None:
        super().__init__(api_base_url, graph_version, user_access_token, app_secret)
        self.attempts = 0

    def _call_graph(self, path: str, access_token: str, params: dict[str, str]) -> dict[str, object]:
        self.attempts += 1
        if self.attempts < 3:
            raise MetaGraphApiException(400, -2, None, "OAuthException", "trace123", "Timeout")
        return {
            "data": [
                {
                    "id": "ig-1",
                    "updated_time": "2026-04-30T01:30:00+00:00",
                    "snippet": "Need help",
                    "unread_count": 2,
                    "senders": {"data": [{"name": "Alex"}]},
                }
            ]
        }


class _PermissionErrorMetaGraphApiClient(MetaGraphApiClient):
    def __init__(self, api_base_url: str, graph_version: str, user_access_token: str, app_secret: str) -> None:
        super().__init__(api_base_url, graph_version, user_access_token, app_secret)
        self.attempts = 0

    def _call_graph(self, path: str, access_token: str, params: dict[str, str]) -> dict[str, object]:
        self.attempts += 1
        raise MetaGraphApiException(
            403,
            230,
            None,
            "OAuthException",
            "trace456",
            "Requires instagram_manage_messages permission",
        )
