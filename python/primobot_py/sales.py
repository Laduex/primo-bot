from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime
from decimal import Decimal
from enum import Enum


class SalesPlatform(str, Enum):
    UTAK = "UTAK"
    LOYVERSE = "Loyverse"


@dataclass(frozen=True)
class SkuSalesEntry:
    sku_key: str | None
    display_name: str | None
    sales_amount: Decimal | None


@dataclass(frozen=True)
class SalesAccountResult:
    account_id: str
    account_name: str
    platform: SalesPlatform | None
    metric_label: str
    amount: Decimal
    success: bool
    error_message: str | None
    sku_sales: list[SkuSalesEntry]


@dataclass(frozen=True)
class SalesReportSnapshot:
    generated_at: datetime
    account_results: list[SalesAccountResult]
    grand_total: Decimal


class SalesReportMessageBuilder:
    def resolve_greeting(self, local_time: datetime) -> str:
        hour = local_time.hour
        if 5 <= hour <= 11:
            return "Good Morning"
        if 12 <= hour <= 17:
            return "Good Afternoon"
        return "Good Evening"

    def build_message(
        self,
        snapshot: SalesReportSnapshot,
        tone: str,
        signature: str | None,
        daily_overview: bool,
    ) -> str:
        casual = tone.lower() == "casual"
        greeting = self.resolve_greeting(snapshot.generated_at)

        if casual:
            header = (
                f"{greeting}, team! Here's your daily sales overview.\n\n"
                if daily_overview
                else f"{greeting}, team! Here's your sales update.\n\n"
            )
        else:
            header = (
                f"{greeting}, team. Here is today's daily sales overview.\n\n"
                if daily_overview
                else f"{greeting}, team. Here is your sales update.\n\n"
            )

        results = list(snapshot.account_results or [])
        if not results:
            content = (
                header
                + "No enabled sales accounts are configured yet. "
                + "Use `/sales-report add-account` to get started."
            )
            return self._append_signature(content, signature)

        results.sort(key=lambda result: (result.account_name or "").lower())

        content = header
        has_success = False
        for result in results:
            if not result.success:
                continue

            has_success = True
            content += (
                f"- **{self._escape_markdown(result.account_name)}**: `"
                f"{self._format_php(result.amount)}`\n"
            )

            if daily_overview:
                content += self._append_top_sku_section(result.sku_sales)

        if not has_success:
            content += "- (no successful fetches)\n"

        content += f"\n**Grand Total:** `{self._format_php(snapshot.grand_total)}`\n"

        failures = [result for result in results if not result.success]
        if failures:
            if casual:
                content += (
                    "\nHeads up: I couldn't fetch some accounts this run. "
                    "I'll try again on the next schedule:\n"
                )
            else:
                content += "\nWarning: Some accounts failed during this run:\n"

            for failure in failures:
                platform_name = "Unknown" if failure.platform is None else failure.platform.value
                content += (
                    f"- {self._escape_markdown(failure.account_name)} ({platform_name}): "
                    "couldn't fetch sales right now.\n"
                )

        return self._append_signature(content, signature)

    def _append_signature(self, content: str, signature: str | None) -> str:
        trimmed = "" if signature is None else signature.strip()
        if trimmed:
            return f"{content}\n\n{trimmed}"
        return content

    def _append_top_sku_section(self, sku_sales: list[SkuSalesEntry] | None) -> str:
        if not sku_sales:
            return ""

        ranked = list(sku_sales)
        ranked.sort(
            key=lambda entry: (
                -self._safe_amount(entry),
                self._safe_display_name(entry).lower(),
                self._safe_sku_key(entry).lower(),
            )
        )

        top = ranked[:10]
        if not top:
            return ""

        lines = ["  Top 10 Sold SKU (PHP):\n"]
        rank = 1
        for entry in top:
            lines.append(
                f"  {rank}. {self._escape_markdown(self._safe_display_name(entry))}: "
                f"`{self._format_php(self._safe_amount(entry))}`\n"
            )
            rank += 1
        return "".join(lines)

    @staticmethod
    def _format_php(value: Decimal | None) -> str:
        safe = Decimal("0") if value is None else value
        return f"PHP {safe:,.2f}"

    @staticmethod
    def _escape_markdown(value: str | None) -> str:
        if value is None or not value.strip():
            return "Unnamed Account"
        return value.replace("`", "'").replace("*", "")

    @staticmethod
    def _safe_amount(entry: SkuSalesEntry | None) -> Decimal:
        if entry is None or entry.sales_amount is None:
            return Decimal("0")
        return entry.sales_amount

    def _safe_display_name(self, entry: SkuSalesEntry | None) -> str:
        if entry is None:
            return "Unknown SKU"

        display = "" if entry.display_name is None else entry.display_name.strip()
        if display:
            return display

        sku_key = self._safe_sku_key(entry)
        return "Unknown SKU" if not sku_key else sku_key

    @staticmethod
    def _safe_sku_key(entry: SkuSalesEntry | None) -> str:
        if entry is None or entry.sku_key is None:
            return ""
        return entry.sku_key.strip()
