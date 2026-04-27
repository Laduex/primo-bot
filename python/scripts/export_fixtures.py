#!/usr/bin/env python3
from __future__ import annotations

import json
import sys
from dataclasses import asdict
from datetime import datetime, time
from decimal import Decimal
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
FIXTURE_ROOT = REPO_ROOT / "tools" / "parity" / "fixtures" / "python"
PYTHON_ROOT = REPO_ROOT / "python"

if str(PYTHON_ROOT) not in sys.path:
    sys.path.insert(0, str(PYTHON_ROOT))

from primobot_py.reminders import OrdersReminderMessageBuilder, ReminderThread
from primobot_py.sales import (
    SalesAccountResult,
    SalesPlatform,
    SalesReportMessageBuilder,
    SalesReportSnapshot,
    SkuSalesEntry,
)
from primobot_py.vat import VatBasis, VatCalculator


def write_fixture(relative_path: str, payload: dict) -> None:
    target = FIXTURE_ROOT / relative_path
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(json.dumps(payload, indent=2, sort_keys=True), encoding="utf-8")


def export_vat_fixtures() -> None:
    calculator = VatCalculator()

    exclusive = calculator.calculate(Decimal("1000.00"), Decimal("12"), VatBasis.EXCLUSIVE)
    inclusive = calculator.calculate(Decimal("1120.00"), Decimal("12"), VatBasis.INCLUSIVE)

    write_fixture(
        "commands/vat/exclusive_basic.json",
        {
            "input": {"amount": "1000.00", "vatRatePercent": "12", "basis": "EXCLUSIVE"},
            "output": {
                "basisLabel": exclusive.basis_label,
                "netAmount": f"{exclusive.net_amount:.2f}",
                "vatAmount": f"{exclusive.vat_amount:.2f}",
                "grossAmount": f"{exclusive.gross_amount:.2f}",
            },
        },
    )

    write_fixture(
        "commands/vat/inclusive_basic.json",
        {
            "input": {"amount": "1120.00", "vatRatePercent": "12", "basis": "INCLUSIVE"},
            "output": {
                "basisLabel": inclusive.basis_label,
                "netAmount": f"{inclusive.net_amount:.2f}",
                "vatAmount": f"{inclusive.vat_amount:.2f}",
                "grossAmount": f"{inclusive.gross_amount:.2f}",
            },
        },
    )


def export_reminder_fixtures() -> None:
    builder = OrdersReminderMessageBuilder()

    write_fixture(
        "commands/orders-reminder/greeting_buckets.json",
        {
            "cases": [
                {"time": "05:00", "greeting": builder.resolve_greeting(time(5, 0))},
                {"time": "12:00", "greeting": builder.resolve_greeting(time(12, 0))},
                {"time": "18:00", "greeting": builder.resolve_greeting(time(18, 0))},
            ]
        },
    )

    message = builder.build_reminder_message(
        mention_role_id="1494215754084651089",
        greeting="Good Morning",
        forum_name="roastery-orders",
        guild_id="1478671501338214410",
        open_threads=[
            ReminderThread(
                thread_id="1495615105767837808",
                name="April 20 | Monday | Mechanika Order",
            )
        ],
        signature="Thanks, Primo",
        tone="casual",
    )

    write_fixture(
        "commands/orders-reminder/message_casual_single_thread.json",
        {
            "input": {
                "mentionRoleId": "1494215754084651089",
                "greeting": "Good Morning",
                "forumName": "roastery-orders",
                "guildId": "1478671501338214410",
                "openThreads": [asdict(ReminderThread("1495615105767837808", "April 20 | Monday | Mechanika Order"))],
                "signature": "Thanks, Primo",
                "tone": "casual",
            },
            "output": {"message": message},
        },
    )


def export_sales_fixtures() -> None:
    builder = SalesReportMessageBuilder()

    write_fixture(
        "commands/sales-report/greeting_buckets.json",
        {
            "cases": [
                {"time": "05:00", "greeting": builder.resolve_greeting(datetime(2026, 4, 22, 5, 0))},
                {"time": "12:00", "greeting": builder.resolve_greeting(datetime(2026, 4, 22, 12, 0))},
                {"time": "18:00", "greeting": builder.resolve_greeting(datetime(2026, 4, 22, 18, 0))},
            ]
        },
    )

    casual_snapshot = SalesReportSnapshot(
        generated_at=datetime(2026, 4, 22, 9, 0),
        account_results=[
            SalesAccountResult(
                account_id="utak-main",
                account_name="UTAK Main",
                platform=SalesPlatform.UTAK,
                metric_label="Total Net Sales",
                amount=Decimal("1000.50"),
                success=True,
                error_message=None,
                sku_sales=[
                    SkuSalesEntry("LAT12::Cafe", "12oz Hot Latte (Cafe)", Decimal("660.00")),
                    SkuSalesEntry("BG001::Cafe", "Roasted Tomato & Cheese Bagel (Cafe)", Decimal("500.00")),
                ],
            ),
            SalesAccountResult(
                account_id="lyv-main",
                account_name="Loyverse Main",
                platform=SalesPlatform.LOYVERSE,
                metric_label="Gross Sales",
                amount=Decimal("0"),
                success=False,
                error_message="Token expired",
                sku_sales=[],
            ),
        ],
        grand_total=Decimal("1000.50"),
    )

    casual_message = builder.build_message(casual_snapshot, "casual", "Thanks, Primo", False)
    write_fixture(
        "commands/sales-report/message_casual_with_failure.json",
        {
            "input": {
                "tone": "casual",
                "signature": "Thanks, Primo",
                "dailyOverview": False,
                "generatedAt": "2026-04-22T09:00:00",
            },
            "output": {"message": casual_message},
        },
    )

    sku_sales = [
        SkuSalesEntry("sku-01", "SKU 01", Decimal("1000")),
        SkuSalesEntry("sku-02", "SKU 02", Decimal("990")),
        SkuSalesEntry("sku-03", "SKU 03", Decimal("980")),
        SkuSalesEntry("sku-04", "SKU 04", Decimal("970")),
        SkuSalesEntry("sku-05", "SKU 05", Decimal("960")),
        SkuSalesEntry("sku-06", "SKU 06", Decimal("950")),
        SkuSalesEntry("sku-07", "SKU 07", Decimal("940")),
        SkuSalesEntry("sku-08", "SKU 08", Decimal("930")),
        SkuSalesEntry("sku-09", "SKU 09", Decimal("920")),
        SkuSalesEntry("sku-10", "SKU 10", Decimal("910")),
        SkuSalesEntry("sku-11", "SKU 11", Decimal("900")),
    ]

    overview_snapshot = SalesReportSnapshot(
        generated_at=datetime(2026, 4, 22, 21, 0),
        account_results=[
            SalesAccountResult(
                account_id="utak-main",
                account_name="UTAK Main",
                platform=SalesPlatform.UTAK,
                metric_label="Total Net Sales",
                amount=Decimal("10450.00"),
                success=True,
                error_message=None,
                sku_sales=sku_sales,
            )
        ],
        grand_total=Decimal("10450.00"),
    )

    overview_message = builder.build_message(overview_snapshot, "formal", "Thanks, Primo", True)
    write_fixture(
        "commands/sales-report/message_formal_daily_overview_top10.json",
        {
            "input": {
                "tone": "formal",
                "signature": "Thanks, Primo",
                "dailyOverview": True,
                "generatedAt": "2026-04-22T21:00:00",
            },
            "output": {"message": overview_message},
        },
    )


def main() -> int:
    export_vat_fixtures()
    export_reminder_fixtures()
    export_sales_fixtures()
    print(f"Wrote python fixtures to: {FIXTURE_ROOT}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
