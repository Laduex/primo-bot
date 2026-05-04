from __future__ import annotations

import asyncio
from pathlib import Path

from primobot_py.reminders import OrdersReminderConfig, OrdersReminderConfigStore, OrdersReminderRoute


def test_orders_reminder_store_initializes_with_expected_defaults(tmp_path: Path) -> None:
    asyncio.run(_test_orders_reminder_store_initializes_with_expected_defaults(tmp_path))


async def _test_orders_reminder_store_initializes_with_expected_defaults(tmp_path: Path) -> None:
    config_path = tmp_path / "orders-reminder-config.json"
    store = OrdersReminderConfigStore(
        str(config_path),
        True,
        "08:00",
        "Asia/Manila",
        "1494503996357087443:1494175620287041536:1494215754084651089",
    )

    await store.initialize()

    config = await store.get_snapshot()
    assert config_path.exists()
    assert config.enabled is True
    assert config.timezone == "Asia/Manila"
    assert config.hour == 8
    assert config.minute == 0
    assert config.messageTone == "casual"
    assert config.signature == "Thanks, Primo"
    assert len(config.routes) == 1


def test_orders_reminder_store_normalizes_routes_and_copy(tmp_path: Path) -> None:
    asyncio.run(_test_orders_reminder_store_normalizes_routes_and_copy(tmp_path))


async def _test_orders_reminder_store_normalizes_routes_and_copy(tmp_path: Path) -> None:
    config_path = tmp_path / "orders-reminder-config.json"
    store = OrdersReminderConfigStore(
        str(config_path),
        True,
        "08:00",
        "Asia/Manila",
        "",
    )
    await store.initialize()

    config = OrdersReminderConfig(
        enabled=True,
        timezone="Bad/Timezone",
        hour=31,
        minute=-1,
        routes=[
            OrdersReminderRoute("bad", "1494175620287041536", "1494215754084651089"),
            OrdersReminderRoute("1494503996357087443", "1494175620287041536", "1494215754084651089"),
        ],
        lastRunDateByRoute={"x": "2026-04-30"},
        messageTone=" FORMAL ",
        signature="  Signed  ",
    )

    await store.replace_and_persist(config)

    reloaded = await store.get_snapshot()
    assert reloaded.timezone == "Asia/Manila"
    assert reloaded.hour == 23
    assert reloaded.minute == 0
    assert reloaded.messageTone == "formal"
    assert reloaded.signature == "Signed"
    assert len(reloaded.routes) == 1
