from __future__ import annotations

import asyncio
from pathlib import Path

from primobot_py.sales import SalesAccountConfig, SalesReportConfig, SalesReportConfigStore


def test_sales_report_config_store_initializes_with_expected_defaults(tmp_path: Path) -> None:
    asyncio.run(_test_sales_report_config_store_initializes_with_expected_defaults(tmp_path))


async def _test_sales_report_config_store_initializes_with_expected_defaults(tmp_path: Path) -> None:
    config_path = tmp_path / "sales-report-config.json"
    store = SalesReportConfigStore(
        str(config_path),
        True,
        "Asia/Manila",
        "09:00,12:00,15:00,18:00,21:00",
        "",
        "",
        "",
        "casual",
        "Thanks, Primo",
    )

    await store.initialize()

    config = await store.get_snapshot()
    assert config_path.exists()
    assert config.enabled is True
    assert config.timezone == "Asia/Manila"
    assert config.times == ["09:00", "12:00", "15:00", "18:00"]
    assert config.overviewTime == "21:00"
    assert config.messageTone == "casual"
    assert config.signature == "Thanks, Primo"


def test_sales_report_config_store_normalizes_times_and_accounts(tmp_path: Path) -> None:
    asyncio.run(_test_sales_report_config_store_normalizes_times_and_accounts(tmp_path))


async def _test_sales_report_config_store_normalizes_times_and_accounts(tmp_path: Path) -> None:
    config_path = tmp_path / "sales-report-config.json"
    store = SalesReportConfigStore(
        str(config_path),
        True,
        "Asia/Manila",
        "09:00",
        "",
        "",
        "",
        "casual",
        "Thanks, Primo",
    )
    await store.initialize()

    config = SalesReportConfig(
        enabled=True,
        timezone="Asia/Manila",
        times=["15:00", "09:00", "09:00", "bad"],
        overviewTime="",
        targetChannelId="",
        overviewTargetChannelId="",
        messageTone="casual",
        signature="Thanks, Primo",
        accounts=[
            SalesAccountConfig(
                id="utak-main",
                platform="UTAK",
                name="Main UTAK",
                username="user",
                password="pass",
            )
        ],
        lastRunDateBySlot={},
    )

    await store.replace_and_persist(config)

    reloaded = await store.get_snapshot()
    assert reloaded.times == ["09:00"]
    assert reloaded.overviewTime == "15:00"
    assert len(reloaded.accounts) == 1
    assert reloaded.accounts[0].id == "utak-main"


def test_sales_report_config_store_migrates_legacy_schedule(tmp_path: Path) -> None:
    asyncio.run(_test_sales_report_config_store_migrates_legacy_schedule(tmp_path))


async def _test_sales_report_config_store_migrates_legacy_schedule(tmp_path: Path) -> None:
    config_path = tmp_path / "legacy" / "sales-report-config.json"
    config_path.parent.mkdir(parents=True, exist_ok=True)
    config_path.write_text(
        """
        {
          "enabled": true,
          "timezone": "Asia/Manila",
          "times": ["10:00", "12:00", "20:00"],
          "targetChannelId": "123456789012345678",
          "overviewTime": "",
          "overviewTargetChannelId": "",
          "messageTone": "casual",
          "signature": "Thanks, Primo",
          "accounts": [],
          "lastRunDateBySlot": {
            "10:00": "2026-04-23",
            "20:00": "2026-04-23",
            "update:12:00": "2026-04-23",
            "bad-key": "2026-04-23"
          }
        }
        """,
        encoding="utf-8",
    )

    store = SalesReportConfigStore(
        str(config_path),
        True,
        "Asia/Manila",
        "09:00",
        "",
        "",
        "",
        "casual",
        "Thanks, Primo",
    )

    await store.initialize()
    config = await store.get_snapshot()

    assert config.times == ["10:00", "12:00"]
    assert config.overviewTime == "20:00"
    assert config.overviewTargetChannelId == "123456789012345678"
    assert "20:00" in config.lastRunDateBySlot
    assert "bad-key" not in config.lastRunDateBySlot
