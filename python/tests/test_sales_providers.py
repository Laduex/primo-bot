from __future__ import annotations

from decimal import Decimal
from datetime import date
from zoneinfo import ZoneInfo

from primobot_py.sales import LoyverseApiSalesProvider, UtakBrowserSalesProvider


def test_loyverse_provider_aggregates_sku_sales_from_line_items() -> None:
    payload = """
    {
      "receipts": [
        {
          "created_at": "2026-04-23T02:00:00Z",
          "line_items": [
            {
              "sku": "BG001",
              "item_name": "Roasted Tomato & Cheese Bagel",
              "variant_name": "Cafe",
              "gross_total_money": { "amount": 50000 }
            },
            {
              "sku": "LAT12",
              "item_name": "12oz Hot Latte",
              "variant_name": "Cafe",
              "gross_total_money": { "amount": 49500 }
            }
          ]
        },
        {
          "created_at": "2026-04-23T05:00:00Z",
          "line_items": [
            {
              "sku": "LAT12",
              "item_name": "12oz Hot Latte",
              "variant_name": "Cafe",
              "quantity": "3",
              "price": "165"
            }
          ]
        },
        {
          "created_at": "2026-04-23T06:00:00Z",
          "receipt_type": "REFUND",
          "line_items": [
            {
              "sku": "BG001",
              "item_name": "Roasted Tomato & Cheese Bagel",
              "variant_name": "Cafe",
              "gross_total_money": { "amount": 50000 }
            }
          ]
        }
      ],
      "cursor": ""
    }
    """

    provider = LoyverseApiSalesProvider()
    result = provider.parse_sales_page_for_date(
        payload,
        date(2026, 4, 23),
        ZoneInfo("Asia/Manila"),
    )

    assert result.amount == Decimal("1490.00")
    assert result.included_count == 2
    by_name = {entry.display_name: entry.sales_amount for entry in result.sku_sales}
    assert by_name["Roasted Tomato & Cheese Bagel (Cafe)"] == Decimal("500.00")
    assert by_name["12oz Hot Latte (Cafe)"] == Decimal("990.00")


def test_utak_provider_aggregates_sales_and_sku_totals() -> None:
    payload = """
    {
      "1776813312": {
        "total": 995,
        "items": [
          { "id": "BG001", "title": "Roasted Tomato & Cheese Bagel", "option": "Cafe", "quantity": 2, "price": 250 },
          { "id": "LAT12", "title": "12oz Hot Latte", "option": "Cafe", "quantity": 3, "price": 165 }
        ]
      },
      "1776813412": {
        "total": 360,
        "items": [
          { "id": "LAT12", "title": "12oz Hot Latte", "option": "Cafe", "quantity": 1, "price": 165 },
          { "id": "AM16", "title": "16oz Iced Americano", "option": "Grab", "quantity": 1, "price": 195 }
        ]
      }
    }
    """

    aggregation = UtakBrowserSalesProvider.aggregate_sales_from_transactions_json(payload)

    assert aggregation.total_net_sales == Decimal("1355")
    by_name = {entry.display_name: entry.sales_amount for entry in aggregation.sku_sales}
    assert by_name["Roasted Tomato & Cheese Bagel (Cafe)"] == Decimal("500")
    assert by_name["12oz Hot Latte (Cafe)"] == Decimal("660")
    assert by_name["16oz Iced Americano (Grab)"] == Decimal("195")
