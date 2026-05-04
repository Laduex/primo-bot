from __future__ import annotations

import asyncio
from pathlib import Path

from primobot_py.dashboard import (
    DashboardConfig,
    DashboardConfigStore,
    DiscordGuildAccess,
    DiscordGuildAccessCache,
    DashboardSession,
    DashboardSessionSigner,
)


def test_dashboard_session_signer_round_trip() -> None:
    signer = DashboardSessionSigner("test-secret")
    session = DashboardSession(
        state="state-123",
        user_id="42",
        username="primo-admin",
        global_name="Primo Admin",
        access_token="access-token",
        refresh_token="refresh-token",
        expires_at_epoch_ms=9_999_999_999_999,
    )

    encoded = signer.dumps(session)
    decoded = signer.loads(encoded)

    assert decoded == session


def test_dashboard_session_signer_rejects_tampering() -> None:
    signer = DashboardSessionSigner("test-secret")
    session = DashboardSession(
        state="state-123",
        user_id="42",
        username="primo-admin",
        global_name="Primo Admin",
        access_token="access-token",
        refresh_token="refresh-token",
        expires_at_epoch_ms=9_999_999_999_999,
    )

    encoded = signer.dumps(session) + "tampered"

    assert signer.loads(encoded) is None


def test_dashboard_config_store_initializes_with_expected_defaults(tmp_path: Path) -> None:
    asyncio.run(_test_dashboard_config_store_initializes_with_expected_defaults(tmp_path))


async def _test_dashboard_config_store_initializes_with_expected_defaults(tmp_path: Path) -> None:
    config_path = tmp_path / "dashboard-config.json"
    store = DashboardConfigStore(
        config_path=str(config_path),
        default_forum_auto_mention_targets_raw="1494503996357087443:1494215754084651089,1494215769347854356",
        default_meta_webhook_target_channel_id="1494175620287041536",
    )

    await store.initialize()
    snapshot = await store.get_snapshot()

    assert config_path.exists()
    assert snapshot.forumAutoMentionTargets == {
        "1494503996357087443": ["1494215754084651089", "1494215769347854356"]
    }
    assert snapshot.metaWebhookTargetChannelId == "1494175620287041536"


def test_dashboard_config_store_normalizes_values(tmp_path: Path) -> None:
    asyncio.run(_test_dashboard_config_store_normalizes_values(tmp_path))


async def _test_dashboard_config_store_normalizes_values(tmp_path: Path) -> None:
    config_path = tmp_path / "dashboard-config.json"
    store = DashboardConfigStore(
        config_path=str(config_path),
        default_forum_auto_mention_targets_raw="",
        default_meta_webhook_target_channel_id="",
    )

    await store.initialize()
    await store.replace_and_persist(
        DashboardConfig(
            forumAutoMentionTargets={
                "bad": ["1494215754084651089"],
                "1494503996357087443": ["1494215754084651089", "bad", "1494215754084651089"],
            },
            metaWebhookTargetChannelId=" bad ",
        )
    )

    snapshot = await store.get_snapshot()

    assert snapshot.forumAutoMentionTargets == {
        "1494503996357087443": ["1494215754084651089"]
    }
    assert snapshot.metaWebhookTargetChannelId == ""


def test_discord_guild_access_cache_expires_entries() -> None:
    cache = DiscordGuildAccessCache(ttl_seconds=60)
    guilds = [
        DiscordGuildAccess(
            id="1478671501338214410",
            name="Primo",
            icon="",
            permissions=32,
            owner=False,
        )
    ]

    cache.put(
        "access-token",
        guilds,
        now_epoch_ms=1_000,
        session_expires_at_epoch_ms=100_000,
    )

    cached = cache.get("access-token", now_epoch_ms=30_000)
    expired = cache.get("access-token", now_epoch_ms=62_000)

    assert cached == guilds
    assert expired is None
