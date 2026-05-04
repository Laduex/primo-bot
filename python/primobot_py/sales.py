from __future__ import annotations

import asyncio
from collections.abc import Sequence
from dataclasses import asdict, dataclass, field
from datetime import UTC, date, datetime, time, timedelta
from decimal import Decimal
from enum import Enum
import hashlib
import json
import logging
from pathlib import Path
import re
from typing import Any, Protocol
from urllib.parse import quote, urlencode
from urllib.request import Request, urlopen

import discord
from discord import app_commands

from .claims import CrossProcessClaimStore
from .utils import chunk_message, dedupe_keep_order, format_php, is_snowflake, is_valid_timezone, normalize_hhmm, resolve_zoneinfo

LOG = logging.getLogger(__name__)

DEFAULT_RECEIPTS_URL = "https://api.loyverse.com/v1.0/receipts"
DEFAULT_FIREBASE_API_KEY = "AIzaSyAIPL4akKeV62h4RRUn7jPhHy1JfXBqW-g"
DEFAULT_FIREBASE_DB_URL = "https://posfire-8d2cb.firebaseio.com"
FIREBASE_API_KEY_ENV = "UTAK_FIREBASE_API_KEY"
FIREBASE_DB_URL_ENV = "UTAK_FIREBASE_DB_URL"
DISCORD_MESSAGE_MAX_LENGTH = 2000
FORUM_POST_TITLE_MAX = 100
DISCORD_AUTOCOMPLETE_MAX_CHOICES = 25
DIRECT_RUN_NOW_RE = re.compile(r"(?i)^sales\s+run\s+now(?:\s+(.*))?$")
SNOWFLAKE_RE = re.compile(r"\d+")
SCHEDULE_CLAIM_NAMESPACE = "sales-schedule"
DIRECT_RUN_NOW_CLAIM_NAMESPACE = "sales-direct-run-now-message"
DISPATCH_DEDUPE_CLAIM_NAMESPACE = "sales-dispatch"
DISPATCH_DEDUPE_TTL = timedelta(minutes=3)


class SalesPlatform(str, Enum):
    UTAK = "UTAK"
    LOYVERSE = "LOYVERSE"

    @property
    def display_name(self) -> str:
        return "UTAK" if self is SalesPlatform.UTAK else "Loyverse"

    @property
    def metric_label(self) -> str:
        return "Total Net Sales" if self is SalesPlatform.UTAK else "Gross Sales"

    @classmethod
    def from_raw(cls, raw: str | None) -> SalesPlatform | None:
        value = (raw or "").strip().upper()
        try:
            return cls(value)
        except ValueError:
            return None


@dataclass(frozen=True, slots=True)
class SkuSalesEntry:
    sku_key: str | None
    display_name: str | None
    sales_amount: Decimal | None


@dataclass(frozen=True, slots=True)
class SalesAccountResult:
    account_id: str
    account_name: str
    platform: SalesPlatform | None
    metric_label: str
    amount: Decimal
    success: bool
    error_message: str | None
    sku_sales: list[SkuSalesEntry]

    @classmethod
    def success_result(
        cls,
        account: SalesAccountConfig | None,
        platform: SalesPlatform,
        metric_label: str,
        amount: Decimal | None,
        sku_sales: Sequence[SkuSalesEntry] | None = None,
    ) -> SalesAccountResult:
        return cls(
            account_id="" if account is None else account.id,
            account_name="Unknown" if account is None else account.name,
            platform=platform,
            metric_label=metric_label,
            amount=Decimal("0") if amount is None else amount,
            success=True,
            error_message=None,
            sku_sales=list(sku_sales or []),
        )

    @classmethod
    def failure_result(
        cls,
        account: SalesAccountConfig | None,
        platform: SalesPlatform | None,
        metric_label: str,
        error_message: str | None,
    ) -> SalesAccountResult:
        return cls(
            account_id="" if account is None else account.id,
            account_name="Unknown" if account is None else account.name,
            platform=platform,
            metric_label=metric_label,
            amount=Decimal("0"),
            success=False,
            error_message=(error_message or "Unknown error"),
            sku_sales=[],
        )


@dataclass(frozen=True, slots=True)
class SalesReportSnapshot:
    generated_at: datetime
    account_results: list[SalesAccountResult]
    grand_total: Decimal
    subtotals: dict[SalesPlatform, Decimal] = field(default_factory=dict)


@dataclass(slots=True)
class SalesAccountConfig:
    id: str = ""
    platform: str = ""
    name: str = ""
    enabled: bool = True
    username: str = ""
    password: str = ""
    token: str = ""
    baseUrl: str = ""
    salesPageUrl: str = ""

    def resolve_platform(self) -> SalesPlatform | None:
        return SalesPlatform.from_raw(self.platform)


@dataclass(slots=True)
class SalesReportConfig:
    enabled: bool = True
    timezone: str = "Asia/Manila"
    times: list[str] = field(default_factory=list)
    targetChannelId: str = ""
    overviewTime: str = ""
    overviewTargetChannelId: str = ""
    messageTone: str = "casual"
    signature: str = "Thanks, Primo"
    accounts: list[SalesAccountConfig] = field(default_factory=list)
    lastRunDateBySlot: dict[str, str] = field(default_factory=dict)


@dataclass(frozen=True, slots=True)
class SalesFetchContext:
    zone: Any
    report_date: date


class SalesProvider(Protocol):
    platform: SalesPlatform

    def fetch_today_cumulative(
        self, account: SalesAccountConfig, context: SalesFetchContext
    ) -> SalesAccountResult:
        raise NotImplementedError


class SalesReportMessageBuilder:
    DAILY_SECTION_DIVIDER = "--------------------"

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
        casual = tone.strip().lower() == "casual"
        greeting = self.resolve_greeting(snapshot.generated_at)
        content = (
            f"{greeting}, team! Here's your daily sales overview.\n\n"
            if casual and daily_overview
            else f"{greeting}, team! Here's your sales update.\n\n"
            if casual
            else f"{greeting}, team. Here is today's daily sales overview.\n\n"
            if daily_overview
            else f"{greeting}, team. Here is your sales update.\n\n"
        )

        results = sorted(snapshot.account_results or [], key=lambda item: (item.account_name or "").lower())
        if not results:
            return self._append_signature(
                content
                + "No enabled sales accounts are configured yet. Add them in the Primo dashboard to get started.",
                signature,
            )

        lines: list[str] = [content]
        has_success = False
        failures: list[SalesAccountResult] = []
        for result in results:
            if not result.success:
                failures.append(result)
                continue
            has_success = True
            if daily_overview:
                lines.append(self._append_daily_overview_account_section(result))
            else:
                lines.append(
                    f"- **{self._escape_markdown(result.account_name)}**: `{format_php(result.amount)}`\n"
                )

        if not has_success:
            lines.append("- (no successful fetches)\n")

        if daily_overview:
            lines.append(
                f"\n{self.DAILY_SECTION_DIVIDER}\nGrand Total: {format_php(snapshot.grand_total)}\n"
            )
        else:
            lines.append(f"\n**Grand Total:** `{format_php(snapshot.grand_total)}`\n")

        if failures:
            if daily_overview:
                lines.append("\nSome accounts failed during this run:\n")
            elif casual:
                lines.append(
                    "\nHeads up: I couldn't fetch some accounts this run. I'll try again on the next schedule:\n"
                )
            else:
                lines.append("\nWarning: Some accounts failed during this run:\n")
            for failure in failures:
                platform_name = "Unknown" if failure.platform is None else failure.platform.display_name
                lines.append(
                    f"- {self._escape_markdown(failure.account_name)} ({platform_name}): couldn't fetch sales right now.\n"
                )

        return self._append_signature("".join(lines), signature)

    def _append_signature(self, body: str, signature: str | None) -> str:
        trimmed = "" if signature is None else signature.strip()
        if not trimmed:
            return body
        return body + "\n\n" + trimmed

    def _append_daily_overview_account_section(self, result: SalesAccountResult) -> str:
        lines = [
            f"{self.DAILY_SECTION_DIVIDER}\n",
            f"{self._escape_markdown(result.account_name).upper()}\n",
            f"Total: {format_php(result.amount)}\n",
        ]
        lines.append(self._append_top_sku_section(result.sku_sales, True))
        lines.append("\n")
        return "".join(lines)

    def _append_top_sku_section(
        self, sku_sales: Sequence[SkuSalesEntry], plain_text_style: bool
    ) -> str:
        if not sku_sales:
            return ""

        ranked = sorted(
            sku_sales,
            key=lambda entry: (
                -self._safe_amount(entry),
                self._safe_display_name(entry).lower(),
                self._safe_sku_key(entry).lower(),
            ),
        )
        top = ranked[:10]
        if not top:
            return ""

        lines = (
            ["\nTop 10 Sold SKU (PHP)\n"]
            if plain_text_style
            else ["  Top 10 Sold SKU (PHP):\n"]
        )
        for index, entry in enumerate(top, start=1):
            if plain_text_style:
                lines.append(
                    f"{index}. {self._escape_markdown(self._safe_display_name(entry))} - "
                    f"{format_php(self._safe_amount(entry))}\n"
                )
            else:
                lines.append(
                    f"  {index}. {self._escape_markdown(self._safe_display_name(entry))}: "
                    f"`{format_php(self._safe_amount(entry))}`\n"
                )
        return "".join(lines)

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


class SalesReportConfigStore:
    def __init__(
        self,
        config_path: str,
        default_enabled: bool,
        default_timezone: str,
        default_times_raw: str,
        default_target_channel_id: str,
        default_overview_time_raw: str,
        default_overview_target_channel_id: str,
        default_tone: str,
        default_signature: str,
    ) -> None:
        self._config_path = Path(config_path)
        self._default_enabled = default_enabled
        self._default_timezone = (default_timezone or "Asia/Manila").strip()
        self._default_times_raw = (default_times_raw or "").strip()
        self._default_target_channel_id = (default_target_channel_id or "").strip()
        self._default_overview_time_raw = (default_overview_time_raw or "").strip()
        self._default_overview_target_channel_id = (
            default_overview_target_channel_id or ""
        ).strip()
        self._default_tone = (default_tone or "casual").strip()
        self._default_signature = (default_signature or "Thanks, Primo").strip()
        self._lock = asyncio.Lock()
        self._current_config: SalesReportConfig | None = None

    async def initialize(self) -> None:
        async with self._lock:
            self._config_path.parent.mkdir(parents=True, exist_ok=True)
            if self._config_path.exists():
                try:
                    payload = json.loads(self._config_path.read_text(encoding="utf-8"))
                    self._current_config = self._normalize(self._from_payload(payload))
                except Exception as ex:
                    LOG.error(
                        "Failed to initialize sales report config from %s: %s",
                        self._config_path,
                        ex,
                    )
                    self._current_config = self._build_default_config()
            else:
                self._current_config = self._build_default_config()
                self._write_config(self._current_config)

    async def get_snapshot(self) -> SalesReportConfig:
        async with self._lock:
            if self._current_config is None:
                self._current_config = self._build_default_config()
            return self._copy(self._current_config)

    async def replace_and_persist(self, updated_config: SalesReportConfig) -> SalesReportConfig:
        async with self._lock:
            normalized = self._normalize(updated_config)
            self._current_config = normalized
            self._write_config(normalized)
            return self._copy(normalized)

    def _build_default_config(self) -> SalesReportConfig:
        update_times = self._parse_times(self._default_times_raw)
        overview_time = self._normalize_single_time(self._default_overview_time_raw)
        if not overview_time and update_times:
            overview_time = update_times.pop()
        target_channel_id = (
            self._default_target_channel_id if is_snowflake(self._default_target_channel_id) else ""
        )
        overview_target_channel_id = (
            self._default_overview_target_channel_id
            if is_snowflake(self._default_overview_target_channel_id)
            else target_channel_id
        )
        return SalesReportConfig(
            enabled=self._default_enabled,
            timezone=self._default_timezone
            if is_valid_timezone(self._default_timezone)
            else "Asia/Manila",
            times=update_times,
            targetChannelId=target_channel_id,
            overviewTime=overview_time,
            overviewTargetChannelId=overview_target_channel_id,
            messageTone=self._normalize_tone(self._default_tone),
            signature=self._default_signature or "Thanks, Primo",
            accounts=[],
            lastRunDateBySlot={},
        )

    def _normalize(self, source: SalesReportConfig | None) -> SalesReportConfig:
        config = source or SalesReportConfig()
        timezone = config.timezone if is_valid_timezone(config.timezone) else "Asia/Manila"
        parsed_times = self._normalize_times(config.times)
        overview_time = self._normalize_single_time(config.overviewTime)

        if not overview_time and parsed_times:
            overview_time = parsed_times[-1]
            parsed_times = parsed_times[:-1]
        elif overview_time:
            parsed_times = [slot for slot in parsed_times if slot != overview_time]

        target_channel_id = config.targetChannelId.strip() if is_snowflake(config.targetChannelId) else ""
        overview_target_channel_id = (
            config.overviewTargetChannelId.strip()
            if is_snowflake(config.overviewTargetChannelId)
            else target_channel_id
        )

        normalized = SalesReportConfig(
            enabled=bool(config.enabled),
            timezone=timezone,
            times=parsed_times,
            targetChannelId=target_channel_id,
            overviewTime=overview_time,
            overviewTargetChannelId=overview_target_channel_id,
            messageTone=self._normalize_tone(config.messageTone),
            signature=(config.signature or "").strip() or "Thanks, Primo",
            accounts=self._normalize_accounts(config.accounts),
            lastRunDateBySlot=dict(config.lastRunDateBySlot or {}),
        )
        normalized.lastRunDateBySlot = {
            key: value
            for key, value in normalized.lastRunDateBySlot.items()
            if self._is_supported_last_run_slot_key(key, normalized.times, normalized.overviewTime)
        }
        return normalized

    def _normalize_accounts(self, source: Sequence[SalesAccountConfig] | None) -> list[SalesAccountConfig]:
        if source is None:
            return []
        normalized: list[SalesAccountConfig] = []
        seen_ids: set[str] = set()
        for account in source:
            if account is None:
                continue
            account_id = (account.id or "").strip()
            if not account_id or account_id.lower() in seen_ids:
                continue
            platform = account.resolve_platform()
            if platform is None:
                continue
            normalized.append(
                SalesAccountConfig(
                    id=account_id,
                    platform=platform.value,
                    name=(account.name or "").strip() or account_id,
                    enabled=bool(account.enabled),
                    username=(account.username or "").strip(),
                    password=(account.password or "").strip(),
                    token=(account.token or "").strip(),
                    baseUrl=(account.baseUrl or "").strip(),
                    salesPageUrl=(account.salesPageUrl or "").strip(),
                )
            )
            seen_ids.add(account_id.lower())
        normalized.sort(key=lambda account: account.id.lower())
        return normalized

    def _parse_times(self, raw_times: str) -> list[str]:
        if not raw_times:
            return []
        return self._normalize_times([part.strip() for part in raw_times.split(",")])

    def _normalize_times(self, source: Sequence[str] | None) -> list[str]:
        parsed = sorted(
            normalize_hhmm(item) for item in (source or []) if normalize_hhmm(item)
        )
        return dedupe_keep_order(parsed)

    def _normalize_single_time(self, raw_time: str | None) -> str:
        return normalize_hhmm(raw_time)

    def _normalize_tone(self, raw_tone: str | None) -> str:
        tone = (raw_tone or "").strip().lower()
        return tone if tone in {"casual", "formal"} else "casual"

    def _is_supported_last_run_slot_key(
        self, key: str, update_times: list[str], overview_time: str
    ) -> bool:
        trimmed = key.strip()
        if not trimmed:
            return False
        if trimmed.startswith("update:"):
            slot = self._normalize_single_time(trimmed[len("update:") :])
            return bool(slot and slot in update_times)
        if trimmed.startswith("overview:"):
            slot = self._normalize_single_time(trimmed[len("overview:") :])
            return bool(slot and overview_time and slot == overview_time)
        legacy_slot = self._normalize_single_time(trimmed)
        return bool(legacy_slot and (legacy_slot in update_times or legacy_slot == overview_time))

    def _write_config(self, config: SalesReportConfig) -> None:
        payload = {
            "enabled": config.enabled,
            "timezone": config.timezone,
            "times": config.times,
            "targetChannelId": config.targetChannelId,
            "overviewTime": config.overviewTime,
            "overviewTargetChannelId": config.overviewTargetChannelId,
            "messageTone": config.messageTone,
            "signature": config.signature,
            "accounts": [asdict(account) for account in config.accounts],
            "lastRunDateBySlot": config.lastRunDateBySlot,
        }
        self._config_path.parent.mkdir(parents=True, exist_ok=True)
        self._config_path.write_text(json.dumps(payload, indent=2), encoding="utf-8")

    def _copy(self, source: SalesReportConfig) -> SalesReportConfig:
        return SalesReportConfig(
            enabled=source.enabled,
            timezone=source.timezone,
            times=list(source.times),
            targetChannelId=source.targetChannelId,
            overviewTime=source.overviewTime,
            overviewTargetChannelId=source.overviewTargetChannelId,
            messageTone=source.messageTone,
            signature=source.signature,
            accounts=[SalesAccountConfig(**asdict(account)) for account in source.accounts],
            lastRunDateBySlot=dict(source.lastRunDateBySlot),
        )

    def _from_payload(self, payload: dict[str, Any]) -> SalesReportConfig:
        accounts: list[SalesAccountConfig] = []
        for account_payload in payload.get("accounts", []):
            if not isinstance(account_payload, dict):
                continue
            accounts.append(
                SalesAccountConfig(
                    id=str(account_payload.get("id", "")),
                    platform=str(account_payload.get("platform", "")),
                    name=str(account_payload.get("name", "")),
                    enabled=bool(account_payload.get("enabled", True)),
                    username=str(account_payload.get("username", "")),
                    password=str(account_payload.get("password", "")),
                    token=str(account_payload.get("token", "")),
                    baseUrl=str(account_payload.get("baseUrl", "")),
                    salesPageUrl=str(account_payload.get("salesPageUrl", "")),
                )
            )
        return SalesReportConfig(
            enabled=bool(payload.get("enabled", True)),
            timezone=str(payload.get("timezone", "Asia/Manila")),
            times=[str(item) for item in payload.get("times", [])],
            targetChannelId=str(payload.get("targetChannelId", "")),
            overviewTime=str(payload.get("overviewTime", "")),
            overviewTargetChannelId=str(payload.get("overviewTargetChannelId", "")),
            messageTone=str(payload.get("messageTone", "casual")),
            signature=str(payload.get("signature", "Thanks, Primo")),
            accounts=accounts,
            lastRunDateBySlot={
                str(key): str(value) for key, value in dict(payload.get("lastRunDateBySlot", {})).items()
            },
        )


class LoyverseApiSalesProvider:
    platform = SalesPlatform.LOYVERSE

    def fetch_today_cumulative(
        self, account: SalesAccountConfig, context: SalesFetchContext
    ) -> SalesAccountResult:
        token = account.token.strip()
        if not token:
            raise ValueError("Missing Loyverse API token")
        endpoint = account.baseUrl.strip() or DEFAULT_RECEIPTS_URL
        result = self._fetch_gross_sales_for_date(token, endpoint, context.report_date, context.zone)
        return SalesAccountResult.success_result(
            account,
            SalesPlatform.LOYVERSE,
            SalesPlatform.LOYVERSE.metric_label,
            result.amount,
            result.sku_sales,
        )

    def _fetch_gross_sales_for_date(
        self, token: str, endpoint: str, report_date: date, zone: Any
    ) -> FetchResult:
        filtered_endpoint = self._build_date_filtered_endpoint(endpoint, report_date, zone)
        next_url = filtered_endpoint
        page = 0
        total = Decimal("0")
        has_any_included = False
        sku_totals: dict[str, SkuAccumulator] = {}

        while next_url:
            page += 1
            if page > 300:
                raise RuntimeError("Loyverse pagination exceeded safety limit")
            request = Request(
                next_url,
                headers={"Authorization": f"Bearer {token}"},
                method="GET",
            )
            with urlopen(request, timeout=30) as response:
                response_body = response.read().decode("utf-8")
            if not response_body.strip():
                raise RuntimeError("Loyverse API returned empty response")

            page_result = self.parse_sales_page_for_date(response_body, report_date, zone)
            total += page_result.amount
            if page_result.included_count > 0:
                has_any_included = True
            self._merge_sku_totals(sku_totals, page_result.sku_sales)
            next_url = self._resolve_next_url(filtered_endpoint, page_result.cursor)

        if not has_any_included:
            return FetchResult(Decimal("0"), [])
        return FetchResult(total, self._to_sku_sales_entries(sku_totals))

    def _build_date_filtered_endpoint(self, endpoint: str, report_date: date, zone: Any) -> str:
        start_local = datetime.combine(report_date, time.min, tzinfo=zone)
        end_local = datetime.combine(report_date + timedelta(days=1), time.min, tzinfo=zone) - timedelta(seconds=1)
        params = {
            "created_at_min": start_local.astimezone(UTC).strftime("%Y-%m-%dT%H:%M:%SZ"),
            "created_at_max": end_local.astimezone(UTC).strftime("%Y-%m-%dT%H:%M:%SZ"),
        }
        separator = "&" if "?" in endpoint else "?"
        return endpoint + separator + urlencode(params)

    def parse_sales_page_for_date(
        self, raw_json: str, report_date: date, zone: Any
    ) -> PageResult:
        try:
            root = json.loads(raw_json)
        except json.JSONDecodeError as ex:
            raise RuntimeError(f"Failed parsing Loyverse response: {ex}") from ex

        receipts = self._resolve_receipt_nodes(root)
        if not receipts:
            return PageResult(Decimal("0"), 0, self._read_cursor(root), [])

        page_total = Decimal("0")
        included_count = 0
        sku_totals: dict[str, SkuAccumulator] = {}
        for receipt in receipts:
            if not self._is_receipt_on_report_date(receipt, report_date, zone):
                continue
            if self._is_refund_receipt(receipt):
                continue
            included_count += 1
            page_total += self._extract_gross_amount(receipt)
            self._accumulate_sku_sales(receipt, sku_totals)
        return PageResult(page_total, included_count, self._read_cursor(root), self._to_sku_sales_entries(sku_totals))

    def _merge_sku_totals(
        self, totals: dict[str, SkuAccumulator], additions: Sequence[SkuSalesEntry]
    ) -> None:
        for addition in additions:
            raw_sku_key = self._trim(addition.sku_key)
            normalized_key = self._normalize_sku_key(raw_sku_key)
            if not normalized_key:
                continue
            amount = Decimal("0") if addition.sales_amount is None else addition.sales_amount
            current = totals.get(normalized_key)
            if current is None:
                totals[normalized_key] = SkuAccumulator(
                    raw_sku_key,
                    self._choose_better_display_name("", addition.display_name, raw_sku_key),
                    amount,
                )
                continue
            sku_key = current.sku_key or raw_sku_key
            display_name = self._choose_better_display_name(
                current.display_name,
                addition.display_name,
                sku_key,
            )
            totals[normalized_key] = SkuAccumulator(
                sku_key,
                display_name,
                current.sales_amount + amount,
            )

    def _accumulate_sku_sales(
        self, receipt: dict[str, Any], sku_totals: dict[str, SkuAccumulator]
    ) -> None:
        line_items = receipt.get("line_items")
        if not isinstance(line_items, list) or not line_items:
            return
        for line_item in line_items:
            if not isinstance(line_item, dict):
                continue
            identity = self._resolve_sku_identity(line_item)
            if identity is None:
                continue
            line_amount = self._extract_line_item_amount(line_item)
            if line_amount is None:
                continue
            normalized_key = self._normalize_sku_key(identity.sku_key)
            current = sku_totals.get(normalized_key)
            if current is None:
                sku_totals[normalized_key] = SkuAccumulator(
                    identity.sku_key,
                    self._choose_better_display_name("", identity.display_name, identity.sku_key),
                    line_amount,
                )
                continue
            display_name = self._choose_better_display_name(
                current.display_name,
                identity.display_name,
                current.sku_key,
            )
            sku_totals[normalized_key] = SkuAccumulator(
                current.sku_key,
                display_name,
                current.sales_amount + line_amount,
            )

    def _resolve_sku_identity(self, line_item: dict[str, Any]) -> SkuIdentity | None:
        base_sku = self._first_non_blank(
            self._read_text(line_item, "sku"),
            self._read_text(line_item, "item_id"),
            self._read_text(line_item, "item_code"),
            self._read_text(line_item, "item_name"),
            self._read_text(line_item, "name"),
        )
        if not base_sku:
            return None
        variant_key = self._first_non_blank(
            self._read_text(line_item, "variant_id"),
            self._read_text(line_item, "variant_name"),
        )
        sku_key = base_sku if not variant_key else f"{base_sku}::{variant_key}"
        item_name = self._first_non_blank(
            self._read_text(line_item, "item_name"),
            self._read_text(line_item, "name"),
            base_sku,
        )
        variant_name = self._first_non_blank(
            self._read_text(line_item, "variant_name"),
            self._read_text(line_item, "variant_id"),
        )
        display_name = self._build_display_name(item_name, variant_name)
        return SkuIdentity(sku_key, display_name)

    def _to_sku_sales_entries(
        self, totals: dict[str, SkuAccumulator]
    ) -> list[SkuSalesEntry]:
        entries: list[SkuSalesEntry] = []
        for value in totals.values():
            if not value.sku_key:
                continue
            entries.append(
                SkuSalesEntry(
                    value.sku_key,
                    self._choose_better_display_name("", value.display_name, value.sku_key),
                    value.sales_amount,
                )
            )
        return entries

    def _resolve_receipt_nodes(self, root: Any) -> list[dict[str, Any]]:
        if isinstance(root, list):
            return [item for item in root if isinstance(item, dict)]
        if not isinstance(root, dict):
            return []
        receipts = root.get("receipts")
        if isinstance(receipts, list):
            return [item for item in receipts if isinstance(item, dict)]
        items = root.get("items")
        if isinstance(items, list):
            return [item for item in items if isinstance(item, dict)]
        return []

    def _is_receipt_on_report_date(self, receipt: dict[str, Any], report_date: date, zone: Any) -> bool:
        sale_date = self._first_parsed_date(receipt, zone, "receipt_date")
        if sale_date is not None:
            return sale_date == report_date
        created_date = self._first_parsed_date(receipt, zone, "created_at")
        if created_date is not None:
            return created_date == report_date
        payments = receipt.get("payments")
        if isinstance(payments, list):
            for payment in payments:
                if not isinstance(payment, dict):
                    continue
                paid_date = self._first_parsed_date(payment, zone, "paid_at")
                if paid_date is not None:
                    return paid_date == report_date
        fallback_date = self._first_parsed_date(
            receipt, zone, "open_time", "close_time", "created_at", "date"
        )
        return fallback_date is None or fallback_date == report_date

    def _first_parsed_date(
        self, node: dict[str, Any], zone: Any, *field_names: str
    ) -> date | None:
        for field_name in field_names:
            parsed = self._parse_local_date(str(node.get(field_name, "")), zone)
            if parsed is not None:
                return parsed
        return None

    def _parse_local_date(self, raw_value: str, zone: Any) -> date | None:
        raw = self._trim(raw_value)
        if not raw:
            return None
        try:
            return datetime.fromisoformat(raw.replace("Z", "+00:00")).astimezone(zone).date()
        except ValueError:
            if len(raw) >= 10:
                try:
                    return date.fromisoformat(raw[:10])
                except ValueError:
                    return None
            return None

    def _extract_gross_amount(self, receipt: dict[str, Any]) -> Decimal:
        from_line_items = self._sum_line_item_gross(receipt)
        if from_line_items is not None:
            return from_line_items
        for field_name in ("gross_total_money", "gross_total", "gross_sales", "total", "total_money"):
            amount = self._parse_money_node(receipt.get(field_name))
            if amount is not None:
                return amount
        for value in receipt.values():
            amount = self._parse_money_node(value)
            if amount is not None:
                return amount
        return Decimal("0")

    def _sum_line_item_gross(self, receipt: dict[str, Any]) -> Decimal | None:
        line_items = receipt.get("line_items")
        if not isinstance(line_items, list) or not line_items:
            return None
        total = Decimal("0")
        has_any = False
        for line_item in line_items:
            if not isinstance(line_item, dict):
                continue
            amount = self._extract_line_item_amount(line_item)
            if amount is not None:
                total += amount
                has_any = True
        return total if has_any else None

    def _extract_line_item_amount(self, line_item: dict[str, Any]) -> Decimal | None:
        for field_name in (
            "gross_total_money",
            "gross_total",
            "total_money",
            "total",
            "amount_money",
            "amount",
        ):
            amount = self._parse_money_node(line_item.get(field_name))
            if amount is not None:
                return amount
        quantity = self._parse_decimal_node(line_item.get("quantity"))
        unit_price = self._parse_money_node(line_item.get("price_money"))
        if unit_price is None:
            unit_price = self._parse_decimal_node(line_item.get("price"))
        if unit_price is None:
            unit_price = self._parse_money_node(line_item.get("price"))
        if quantity is not None and unit_price is not None:
            return unit_price * quantity
        return None

    def _parse_money_node(self, node: Any) -> Decimal | None:
        if node is None:
            return None
        if isinstance(node, (int, float)):
            return Decimal(str(node))
        if isinstance(node, str):
            raw = node.strip().replace(",", "")
            if not raw:
                return None
            try:
                return Decimal(raw)
            except Exception:
                return None
        if isinstance(node, dict):
            amount = node.get("amount")
            if isinstance(amount, int):
                return Decimal(amount).scaleb(-2)
            if isinstance(amount, float):
                return Decimal(str(amount))
        return None

    def _parse_decimal_node(self, node: Any) -> Decimal | None:
        if node is None:
            return None
        if isinstance(node, (int, float)):
            return Decimal(str(node))
        if isinstance(node, str):
            raw = node.strip().replace(",", "")
            if not raw:
                return None
            try:
                return Decimal(raw)
            except Exception:
                return None
        return None

    def _build_display_name(self, item_name: str, variant_name: str) -> str:
        safe_item_name = self._trim(item_name)
        safe_variant_name = self._trim(variant_name)
        if not safe_item_name:
            return safe_variant_name
        if not safe_variant_name or safe_variant_name.lower() == safe_item_name.lower():
            return safe_item_name
        return f"{safe_item_name} ({safe_variant_name})"

    def _is_refund_receipt(self, receipt: dict[str, Any]) -> bool:
        return self._trim(receipt.get("receipt_type")).lower() == "refund"

    def _read_cursor(self, root: Any) -> str:
        return self._trim(root.get("cursor") if isinstance(root, dict) else "")

    def _resolve_next_url(self, endpoint: str, cursor: str) -> str:
        if not cursor:
            return ""
        separator = "&" if "?" in endpoint else "?"
        return endpoint + separator + "cursor=" + quote(cursor, safe="")

    def _normalize_sku_key(self, sku_key: str | None) -> str:
        return self._trim(sku_key).lower()

    def _read_text(self, node: dict[str, Any], field_name: str) -> str:
        return self._trim(node.get(field_name, ""))

    def _first_non_blank(self, *values: str) -> str:
        for value in values:
            candidate = self._trim(value)
            if candidate:
                return candidate
        return ""

    def _choose_better_display_name(
        self, current: str | None, candidate: str | None, sku_key: str | None
    ) -> str:
        safe_current = self._trim(current)
        safe_candidate = self._trim(candidate)
        safe_sku_key = self._trim(sku_key)
        if not safe_current:
            return safe_candidate or safe_sku_key
        if not safe_candidate:
            return safe_current
        if safe_current.lower() == safe_sku_key.lower() and safe_candidate.lower() != safe_sku_key.lower():
            return safe_candidate
        return safe_current

    @staticmethod
    def _trim(value: Any) -> str:
        return "" if value is None else str(value).strip()


class UtakBrowserSalesProvider:
    platform = SalesPlatform.UTAK

    def fetch_today_cumulative(
        self, account: SalesAccountConfig, context: SalesFetchContext
    ) -> SalesAccountResult:
        username = account.username.strip()
        password = account.password.strip()
        if not username or not password:
            raise ValueError("UTAK account requires username and password")

        try:
            api_key = self._resolve_firebase_api_key()
            db_base_url = self._resolve_firebase_db_base_url(account)
            session = self._authenticate_with_firebase(username, password, api_key)
            transactions_json = self._fetch_transactions_json(
                db_base_url,
                session.uid,
                session.id_token,
                context.report_date,
                context.zone,
            )
            aggregation = self.aggregate_sales_from_transactions_json(transactions_json)
            return SalesAccountResult.success_result(
                account,
                SalesPlatform.UTAK,
                SalesPlatform.UTAK.metric_label,
                aggregation.total_net_sales,
                aggregation.sku_sales,
            )
        except Exception as ex:
            raise RuntimeError(f"Failed UTAK fetch for account '{account.name}': {ex}") from ex

    def _authenticate_with_firebase(self, username: str, password: str, api_key: str) -> AuthSession:
        auth_url = (
            "https://identitytoolkit.googleapis.com/v1/accounts:signInWithPassword?key="
            + quote(api_key, safe="")
        )
        payload = json.dumps(
            {"email": username, "password": password, "returnSecureToken": True}
        ).encode("utf-8")
        request = Request(
            auth_url,
            data=payload,
            headers={"Content-Type": "application/json"},
            method="POST",
        )
        with urlopen(request, timeout=30) as response:
            response_body = response.read().decode("utf-8")
        if not response_body.strip():
            raise RuntimeError("UTAK auth returned empty response")
        root = json.loads(response_body)
        uid = str(root.get("localId", "")).strip()
        id_token = str(root.get("idToken", "")).strip()
        if not uid or not id_token:
            raise RuntimeError("UTAK auth missing localId/idToken")
        return AuthSession(uid, id_token)

    def _fetch_transactions_json(
        self,
        db_base_url: str,
        uid: str,
        id_token: str,
        report_date: date,
        zone: Any,
    ) -> str:
        start_epoch = int(datetime.combine(report_date, time.min, tzinfo=zone).timestamp())
        end_epoch = int(
            (datetime.combine(report_date + timedelta(days=1), time.min, tzinfo=zone) - timedelta(seconds=1)).timestamp()
        )
        transactions_url = (
            f"{db_base_url}/{quote(uid, safe='')}/transactions.json"
            f"?orderBy=%22$key%22&startAt=%22{start_epoch}%22&endAt=%22{end_epoch}%22&auth={quote(id_token, safe='')}"
        )
        request = Request(transactions_url, method="GET")
        with urlopen(request, timeout=30) as response:
            body = response.read().decode("utf-8")
        return body or "null"

    @classmethod
    def sum_total_net_sales_from_transactions_json(cls, transactions_json: str) -> Decimal:
        return cls.aggregate_sales_from_transactions_json(transactions_json).total_net_sales

    @classmethod
    def aggregate_sales_from_transactions_json(
        cls, transactions_json: str
    ) -> TransactionAggregation:
        if not transactions_json.strip() or transactions_json.strip().lower() == "null":
            return TransactionAggregation(Decimal("0"), [])
        root = json.loads(transactions_json)
        if not isinstance(root, dict):
            return TransactionAggregation(Decimal("0"), [])
        total = Decimal("0")
        sku_totals: dict[str, ItemAccumulator] = {}
        for transaction in root.values():
            if not isinstance(transaction, dict):
                continue
            if bool(transaction.get("_deleted")):
                continue
            amount = cls._parse_amount(transaction.get("total"))
            if amount is not None:
                total += amount
            cls._accumulate_item_sales(transaction.get("items"), sku_totals)
        return TransactionAggregation(total, cls._to_utak_sku_entries(sku_totals))

    @classmethod
    def _parse_amount(cls, node: Any) -> Decimal | None:
        if node is None:
            return None
        if isinstance(node, (int, float)):
            return Decimal(str(node))
        if isinstance(node, str):
            raw = node.replace(",", "").strip()
            if not raw:
                return None
            try:
                return Decimal(raw)
            except Exception:
                return None
        return None

    @classmethod
    def _accumulate_item_sales(
        cls, items_node: Any, sku_totals: dict[str, ItemAccumulator]
    ) -> None:
        if not isinstance(items_node, list) or not items_node:
            return
        for item in items_node:
            if not isinstance(item, dict):
                continue
            base_sku = cls._first_non_blank(cls._read_text(item, "id"), cls._read_text(item, "title"))
            if not base_sku:
                continue
            option = cls._read_text(item, "option")
            display_name = cls._build_utak_display_name(
                cls._first_non_blank(cls._read_text(item, "title"), base_sku),
                option,
            )
            sku_key = base_sku if not option else f"{base_sku}::{option}"
            line_amount = cls._resolve_item_amount(item)
            if line_amount is None:
                continue
            normalized_key = sku_key.lower()
            current = sku_totals.get(normalized_key)
            if current is None:
                sku_totals[normalized_key] = ItemAccumulator(sku_key, display_name, line_amount)
                continue
            preferred_name = cls._prefer_display_name(current.display_name, display_name, current.sku_key)
            sku_totals[normalized_key] = ItemAccumulator(
                current.sku_key,
                preferred_name,
                current.amount + line_amount,
            )

    @classmethod
    def _resolve_item_amount(cls, item: dict[str, Any]) -> Decimal | None:
        for field_name in ("amount", "line_total", "gross_total", "total"):
            direct = cls._parse_amount(item.get(field_name))
            if direct is not None:
                return direct
        price = cls._parse_amount(item.get("price"))
        quantity = cls._parse_amount(item.get("quantity"))
        if price is None or quantity is None:
            return None
        return price * quantity

    @classmethod
    def _to_utak_sku_entries(
        cls, sku_totals: dict[str, ItemAccumulator]
    ) -> list[SkuSalesEntry]:
        entries: list[SkuSalesEntry] = []
        for value in sku_totals.values():
            if not value.sku_key:
                continue
            entries.append(
                SkuSalesEntry(
                    value.sku_key,
                    cls._prefer_display_name("", value.display_name, value.sku_key),
                    value.amount,
                )
            )
        return entries

    @staticmethod
    def _read_text(node: dict[str, Any], field_name: str) -> str:
        return str(node.get(field_name, "")).strip()

    @staticmethod
    def _first_non_blank(*values: str) -> str:
        for value in values:
            if value and value.strip():
                return value.strip()
        return ""

    @staticmethod
    def _build_utak_display_name(title: str, option: str) -> str:
        safe_title = title.strip()
        safe_option = option.strip()
        return safe_title if not safe_option else f"{safe_title} ({safe_option})"

    @staticmethod
    def _prefer_display_name(current: str, candidate: str, sku_key_fallback: str) -> str:
        safe_current = current.strip()
        safe_candidate = candidate.strip()
        safe_sku_key = sku_key_fallback.strip()
        if not safe_current:
            return safe_candidate or safe_sku_key
        if not safe_candidate:
            return safe_current
        if safe_current.lower() == safe_sku_key.lower() and safe_candidate.lower() != safe_sku_key.lower():
            return safe_candidate
        return safe_current

    def _resolve_firebase_api_key(self) -> str:
        import os

        return os.getenv(FIREBASE_API_KEY_ENV, "").strip() or DEFAULT_FIREBASE_API_KEY

    def _resolve_firebase_db_base_url(self, account: SalesAccountConfig) -> str:
        import os

        from_env = os.getenv(FIREBASE_DB_URL_ENV, "").strip()
        if from_env:
            return from_env.rstrip("/")
        configured = account.baseUrl.strip()
        if "firebaseio.com" in configured:
            return configured.rstrip("/")
        return DEFAULT_FIREBASE_DB_URL


class SalesAggregatorService:
    def __init__(self, providers: Sequence[SalesProvider]) -> None:
        self.providers = {provider.platform: provider for provider in providers}

    def aggregate(self, config: SalesReportConfig, zone: Any) -> SalesReportSnapshot:
        now = datetime.now(zone)
        report_date = now.date()
        context = SalesFetchContext(zone, report_date)
        account_results: list[SalesAccountResult] = []
        subtotals = {platform: Decimal("0") for platform in SalesPlatform}

        for account in config.accounts:
            if not account.enabled:
                continue
            platform = account.resolve_platform()
            if platform is None:
                account_results.append(
                    SalesAccountResult.failure_result(account, None, "Unknown Metric", "Invalid platform")
                )
                continue
            provider = self.providers.get(platform)
            if provider is None:
                account_results.append(
                    SalesAccountResult.failure_result(
                        account,
                        platform,
                        platform.metric_label,
                        "No provider configured",
                    )
                )
                continue
            try:
                result = provider.fetch_today_cumulative(account, context)
            except Exception as ex:
                LOG.warning(
                    "Sales fetch failed for account %r (%s): %s",
                    account.name,
                    platform,
                    ex,
                )
                account_results.append(
                    SalesAccountResult.failure_result(
                        account,
                        platform,
                        platform.metric_label,
                        self._clean_error(str(ex)),
                    )
                )
                continue
            account_results.append(result)
            if result.success:
                subtotals[platform] += result.amount

        grand_total = sum(subtotals.values(), start=Decimal("0"))
        return SalesReportSnapshot(now, account_results, grand_total, subtotals)

    @staticmethod
    def _clean_error(message: str) -> str:
        normalized = (message or "").strip().replace("\n", " ")
        if not normalized:
            return "Unable to fetch sales"
        return normalized[:160] + ("..." if len(normalized) > 160 else "")


class SalesReportExecutorService:
    def __init__(
        self,
        aggregator_service: SalesAggregatorService,
        message_builder: SalesReportMessageBuilder,
        claim_store: CrossProcessClaimStore,
    ) -> None:
        self.aggregator_service = aggregator_service
        self.message_builder = message_builder
        self.claim_store = claim_store

    async def execute(
        self,
        guild: discord.Guild | None,
        config: SalesReportConfig,
        override_target_channel_id: str,
        selected_account_id: str,
        daily_overview: bool,
        suppress_recent_duplicates: bool = False,
    ) -> DispatchResult:
        if guild is None:
            return DispatchResult("GUILD_NOT_FOUND", "", selected_account_id, "No guild available", 0, 0)

        target_id = self._resolve_target_channel_id(config, override_target_channel_id, daily_overview)
        if not target_id:
            return DispatchResult("TARGET_NOT_CONFIGURED", "", selected_account_id, "No target channel configured", 0, 0)

        prepared = await self._prepare_dispatch(config, selected_account_id, daily_overview)
        if prepared.result is not None:
            return prepared.result

        try:
            chunks = chunk_message(prepared.content, DISCORD_MESSAGE_MAX_LENGTH)
            dedupe_key = ""
            if suppress_recent_duplicates:
                dedupe_key = self._build_dispatch_dedupe_key(
                    target_id,
                    selected_account_id,
                    daily_overview,
                    chunks,
                )
                if not self.claim_store.try_claim(
                    DISPATCH_DEDUPE_CLAIM_NAMESPACE,
                    dedupe_key,
                    DISPATCH_DEDUPE_TTL,
                ):
                    return DispatchResult(
                        "DUPLICATE_SUPPRESSED",
                        target_id,
                        selected_account_id,
                        "Duplicate scheduled dispatch suppressed",
                        prepared.success_count,
                        prepared.failure_count,
                    )
            channel = guild.get_channel(int(target_id))
            if isinstance(channel, discord.TextChannel):
                if suppress_recent_duplicates and await self._matches_recent_bot_dispatch(channel, chunks):
                    return DispatchResult(
                        "DUPLICATE_SUPPRESSED",
                        target_id,
                        selected_account_id,
                        "Duplicate scheduled dispatch suppressed",
                        prepared.success_count,
                        prepared.failure_count,
                    )
                for chunk in chunks:
                    await channel.send(chunk)
            elif isinstance(channel, discord.ForumChannel):
                created = await channel.create_thread(
                    name=self._build_forum_post_title(prepared.zone, selected_account_id, daily_overview),
                    content=chunks[0],
                )
                for chunk in chunks[1:]:
                    await created.thread.send(chunk)
            else:
                return DispatchResult("TARGET_NOT_FOUND", target_id, selected_account_id, "Target channel not found", 0, 0)
            return DispatchResult(
                "SENT",
                target_id,
                selected_account_id,
                "Sent",
                prepared.success_count,
                prepared.failure_count,
            )
        except discord.HTTPException as ex:
            if dedupe_key:
                self.claim_store.release(DISPATCH_DEDUPE_CLAIM_NAMESPACE, dedupe_key)
            return DispatchResult("SEND_FAILED", target_id, selected_account_id, str(ex), 0, 0)

    async def execute_direct(
        self,
        config: SalesReportConfig,
        selected_account_id: str,
        destination: discord.abc.Messageable | None,
        daily_overview: bool,
    ) -> DispatchResult:
        if destination is None:
            return DispatchResult("SEND_FAILED", "", selected_account_id, "No destination channel", 0, 0)
        prepared = await self._prepare_dispatch(config, selected_account_id, daily_overview)
        if prepared.result is not None:
            return prepared.result
        try:
            for chunk in chunk_message(prepared.content, DISCORD_MESSAGE_MAX_LENGTH):
                await destination.send(chunk)
            destination_id = str(getattr(destination, "id", ""))
            return DispatchResult(
                "SENT",
                destination_id,
                selected_account_id,
                "Sent",
                prepared.success_count,
                prepared.failure_count,
            )
        except discord.HTTPException as ex:
            destination_id = str(getattr(destination, "id", ""))
            return DispatchResult("SEND_FAILED", destination_id, selected_account_id, str(ex), 0, 0)

    async def _prepare_dispatch(
        self, config: SalesReportConfig, selected_account_id: str, daily_overview: bool
    ) -> PreparedDispatch:
        effective_config = self._filter_config_by_account(config, selected_account_id)
        if effective_config is None:
            return PreparedDispatch(
                "",
                0,
                0,
                resolve_zoneinfo("Asia/Manila"),
                DispatchResult("ACCOUNT_NOT_FOUND", "", selected_account_id, "Account not found", 0, 0),
            )
        zone = resolve_zoneinfo(effective_config.timezone)
        snapshot = await asyncio.to_thread(self.aggregator_service.aggregate, effective_config, zone)
        content = self.message_builder.build_message(
            snapshot,
            effective_config.messageTone,
            effective_config.signature,
            daily_overview,
        )
        success_count = sum(1 for result in snapshot.account_results if result.success)
        failure_count = sum(1 for result in snapshot.account_results if not result.success)
        return PreparedDispatch(content, success_count, failure_count, zone, None)

    def _resolve_target_channel_id(
        self, config: SalesReportConfig, override_target_channel_id: str, daily_overview: bool
    ) -> str:
        override = (override_target_channel_id or "").strip()
        if override:
            return override
        configured = (
            config.overviewTargetChannelId.strip()
            if daily_overview
            else config.targetChannelId.strip()
        )
        if not configured and daily_overview:
            configured = config.targetChannelId.strip()
        return configured

    def _filter_config_by_account(
        self, config: SalesReportConfig, selected_account_id: str
    ) -> SalesReportConfig | None:
        requested = (selected_account_id or "").strip()
        if not requested:
            return config
        selected_accounts = [
            account for account in config.accounts if requested.lower() == account.id.lower()
        ]
        if not selected_accounts:
            return None
        return SalesReportConfig(
            enabled=config.enabled,
            timezone=config.timezone,
            times=list(config.times),
            targetChannelId=config.targetChannelId,
            overviewTime=config.overviewTime,
            overviewTargetChannelId=config.overviewTargetChannelId,
            messageTone=config.messageTone,
            signature=config.signature,
            accounts=selected_accounts,
            lastRunDateBySlot=dict(config.lastRunDateBySlot),
        )

    def _build_forum_post_title(self, zone: Any, selected_account_id: str, daily_overview: bool) -> str:
        timestamp = datetime.now(zone).strftime("%Y-%m-%d %H:%M")
        scope = selected_account_id.strip() if selected_account_id.strip() else "All Accounts"
        report_type = "Daily Overview" if daily_overview else "Sales Update"
        title = f"{report_type} | {scope} | {timestamp}"
        return title[:FORUM_POST_TITLE_MAX]

    @staticmethod
    def _build_dispatch_dedupe_key(
        target_id: str,
        selected_account_id: str,
        daily_overview: bool,
        chunks: Sequence[str],
    ) -> str:
        payload = "\n<chunk>\n".join(chunks)
        digest = hashlib.sha256(payload.encode("utf-8")).hexdigest()
        report_type = "overview" if daily_overview else "update"
        account_scope = selected_account_id.strip() if selected_account_id.strip() else "all"
        return f"{target_id}|{report_type}|{account_scope}|{digest}"

    async def _matches_recent_bot_dispatch(
        self,
        channel: discord.TextChannel,
        chunks: Sequence[str],
    ) -> bool:
        bot_member = channel.guild.me
        if bot_member is None:
            return False

        recent_messages = [
            message
            async for message in channel.history(limit=max(len(chunks), 1))
        ]
        if len(recent_messages) < len(chunks):
            return False

        now = datetime.now(UTC)
        expected_contents = list(reversed(list(chunks)))
        for index, expected in enumerate(expected_contents):
            message = recent_messages[index]
            if message.author.id != bot_member.id:
                return False
            if (now - message.created_at.astimezone(UTC)) > DISPATCH_DEDUPE_TTL:
                return False
            if message.content != expected:
                return False
        return True


class SalesReportSchedulerService:
    def __init__(
        self,
        bot: discord.Client,
        config_store: SalesReportConfigStore,
        executor_service: SalesReportExecutorService,
        default_guild_id: str,
        claim_store: CrossProcessClaimStore,
    ) -> None:
        self.bot = bot
        self.config_store = config_store
        self.executor_service = executor_service
        self.default_guild_id = default_guild_id.strip()
        self.claim_store = claim_store

    async def run_sales_tick(self) -> None:
        config = await self.config_store.get_snapshot()
        has_update_schedule = bool(config.times)
        has_overview_schedule = bool(config.overviewTime.strip())
        if not config.enabled or (not has_update_schedule and not has_overview_schedule):
            return

        zone = resolve_zoneinfo(config.timezone)
        now = datetime.now(zone)
        slot = now.strftime("%H:%M")
        today_text = now.date().isoformat()

        guild = self.resolve_guild()
        if guild is None:
            LOG.warning("Sales report skipped: no guild available.")
            return

        overview_slot = normalize_hhmm(config.overviewTime)
        if (
            overview_slot
            and overview_slot == slot
            and not self._already_ran_today(config, self._slot_key(True, slot), slot, today_text)
        ):
            await self._dispatch_scheduled(guild, config, slot, today_text, True)
            return

        if (
            slot in config.times
            and slot != overview_slot
            and not self._already_ran_today(config, self._slot_key(False, slot), slot, today_text)
        ):
            await self._dispatch_scheduled(guild, config, slot, today_text, False)

    async def run_now(
        self, guild: discord.Guild | None, override_target_channel_id: str, selected_account_id: str
    ) -> DispatchResult:
        config = await self.config_store.get_snapshot()
        return await self.executor_service.execute(
            guild,
            config,
            override_target_channel_id,
            selected_account_id,
            False,
        )

    async def _dispatch_scheduled(
        self,
        guild: discord.Guild,
        config: SalesReportConfig,
        slot: str,
        today_text: str,
        daily_overview: bool,
    ) -> None:
        slot_key = self._slot_key(daily_overview, slot)
        claim_key = f"{today_text}|{slot_key}"
        if not self.claim_store.try_claim(
            SCHEDULE_CLAIM_NAMESPACE,
            claim_key,
            timedelta(days=2),
        ):
            return
        config.lastRunDateBySlot[slot_key] = today_text
        await self.config_store.replace_and_persist(config)

        result = await self.executor_service.execute(
            guild,
            config,
            "",
            "",
            daily_overview,
            suppress_recent_duplicates=True,
        )
        if result.status in {"SENT", "DUPLICATE_SUPPRESSED"}:
            if daily_overview:
                LOG.info(
                    "%s scheduled daily sales overview for slot %s to channel %s (%s success, %s failed).",
                    "Suppressed duplicate" if result.status == "DUPLICATE_SUPPRESSED" else "Posted",
                    slot,
                    result.target_channel_id,
                    result.success_count,
                    result.failure_count,
                )
            else:
                LOG.info(
                    "%s scheduled sales update for slot %s to channel %s (%s success, %s failed).",
                    "Suppressed duplicate" if result.status == "DUPLICATE_SUPPRESSED" else "Posted",
                    slot,
                    result.target_channel_id,
                    result.success_count,
                    result.failure_count,
                )
            return

        config.lastRunDateBySlot.pop(slot_key, None)
        await self.config_store.replace_and_persist(config)
        self.claim_store.release(SCHEDULE_CLAIM_NAMESPACE, claim_key)
        if daily_overview:
            LOG.warning("Scheduled daily sales overview failed for slot %s: %s", slot, result.message)
        else:
            LOG.warning("Scheduled sales update failed for slot %s: %s", slot, result.message)

    def resolve_guild(self) -> discord.Guild | None:
        if self.default_guild_id:
            guild = self.bot.get_guild(int(self.default_guild_id))
            if guild is not None:
                return guild
        return self.bot.guilds[0] if self.bot.guilds else None

    @staticmethod
    def _slot_key(daily_overview: bool, slot: str) -> str:
        return ("overview:" if daily_overview else "update:") + slot

    @staticmethod
    def _already_ran_today(
        config: SalesReportConfig, key: str, legacy_slot: str, today_text: str
    ) -> bool:
        return today_text == config.lastRunDateBySlot.get(key) or today_text == config.lastRunDateBySlot.get(legacy_slot)


class SalesCommandService:
    def __init__(
        self,
        bot: discord.Client,
        config_store: SalesReportConfigStore,
        executor_service: SalesReportExecutorService,
        scheduler_service: SalesReportSchedulerService,
        default_guild_id: str,
        claim_store: CrossProcessClaimStore,
    ) -> None:
        self.bot = bot
        self.config_store = config_store
        self.executor_service = executor_service
        self.scheduler_service = scheduler_service
        self.default_guild_id = default_guild_id.strip()
        self.claim_store = claim_store

    async def status_text(self) -> str:
        config = await self.config_store.get_snapshot()
        update_times_text = ", ".join(config.times) if config.times else "(none)"
        overview_time_text = config.overviewTime or "(not set)"
        accounts = "(none)"
        if config.accounts:
            accounts = "\n".join(
                f"- `{account.id}` | {account.resolve_platform().display_name if account.resolve_platform() else 'Unknown'} | "
                f"{account.name} | enabled: `{account.enabled}`"
                for account in sorted(config.accounts, key=lambda item: item.id.lower())
            )
        return (
            "**Sales Report Settings**\n"
            f"Enabled: `{config.enabled}`\n"
            f"Timezone: `{config.timezone}`\n"
            f"Sales Update Times: `{update_times_text}`\n"
            f"Daily Overview Time: `{overview_time_text}`\n"
            f"Sales Update Channel: {self._format_channel_mention(config.targetChannelId)}\n"
            f"Daily Overview Channel: {self._format_channel_mention(config.overviewTargetChannelId)}\n"
            f"Tone: `{config.messageTone}`\n"
            f"Signature: `{config.signature}`\n"
            f"Next Sales Update: `{self._describe_next_scheduled_run(config.times, config.timezone)}`\n"
            f"Next Daily Overview: `{self._describe_next_overview(config)}`\n\n"
            f"**Accounts**\n{accounts}"
        )

    async def set_enabled(self, enabled: bool) -> SalesReportConfig:
        config = await self.config_store.get_snapshot()
        config.enabled = enabled
        return await self.config_store.replace_and_persist(config)

    async def set_timezone(self, timezone: str) -> SalesReportConfig:
        config = await self.config_store.get_snapshot()
        config.timezone = timezone.strip()
        return await self.config_store.replace_and_persist(config)

    async def add_time(self, slot: str) -> tuple[bool, SalesReportConfig]:
        config = await self.config_store.get_snapshot()
        if slot in config.times:
            return False, config
        config.times = sorted(config.times + [slot])
        return True, await self.config_store.replace_and_persist(config)

    async def remove_time(self, slot: str) -> tuple[bool, SalesReportConfig]:
        config = await self.config_store.get_snapshot()
        if slot not in config.times:
            return False, config
        config.times = [existing for existing in config.times if existing != slot]
        config.lastRunDateBySlot.pop(f"update:{slot}", None)
        config.lastRunDateBySlot.pop(slot, None)
        return True, await self.config_store.replace_and_persist(config)

    async def clear_times(self) -> SalesReportConfig:
        config = await self.config_store.get_snapshot()
        previous_update_times = list(config.times)
        config.times = []
        for slot in previous_update_times:
            config.lastRunDateBySlot.pop(f"update:{slot}", None)
            config.lastRunDateBySlot.pop(slot, None)
        return await self.config_store.replace_and_persist(config)

    async def set_channel(self, channel_id: int) -> SalesReportConfig:
        config = await self.config_store.get_snapshot()
        config.targetChannelId = str(channel_id)
        return await self.config_store.replace_and_persist(config)

    async def set_summary(self, channel_id: int, slot: str, timezone: str | None) -> SalesReportConfig:
        config = await self.config_store.get_snapshot()
        previous_overview_time = config.overviewTime
        config.overviewTargetChannelId = str(channel_id)
        config.overviewTime = slot
        config.lastRunDateBySlot.pop(f"overview:{slot}", None)
        config.lastRunDateBySlot.pop(slot, None)
        if previous_overview_time:
            config.lastRunDateBySlot.pop(f"overview:{previous_overview_time}", None)
            config.lastRunDateBySlot.pop(previous_overview_time, None)
        if timezone and timezone.strip():
            config.timezone = timezone.strip()
        return await self.config_store.replace_and_persist(config)

    async def list_accounts_text(self) -> str:
        config = await self.config_store.get_snapshot()
        if not config.accounts:
            return "No sales accounts configured yet."
        body = "\n".join(
            f"- `{account.id}` | {account.resolve_platform().display_name if account.resolve_platform() else 'Unknown'} | "
            f"`{account.name}` | enabled: `{account.enabled}` | credentials: `{self._credentials_summary(account)}`"
            for account in sorted(config.accounts, key=lambda item: item.id.lower())
        )
        return "**Sales Accounts**\n" + body

    async def add_account(self, account: SalesAccountConfig) -> tuple[bool, str]:
        config = await self.config_store.get_snapshot()
        exists = any(account.id.lower() == existing.id.lower() for existing in config.accounts)
        if exists:
            return False, f"Account id `{account.id}` already exists. Choose another id."
        validation_error = self.validate_account_by_platform(account)
        if validation_error:
            return False, validation_error
        config.accounts.append(account)
        await self.config_store.replace_and_persist(config)
        platform = account.resolve_platform()
        return True, f"Added `{account.name}` ({platform.display_name if platform else 'Unknown'}) as account id `{account.id}`."

    async def update_account(self, account_id: str, **updates: str | bool | None) -> tuple[bool, str]:
        config = await self.config_store.get_snapshot()
        account = next((item for item in config.accounts if item.id.lower() == account_id.lower()), None)
        if account is None:
            return False, f"No account found for id `{account_id}`."
        for key, value in updates.items():
            if value is None:
                continue
            if key == "enabled":
                account.enabled = bool(value)
            elif key == "name" and isinstance(value, str) and value.strip():
                account.name = value.strip()
            elif key == "username" and isinstance(value, str):
                account.username = value.strip()
            elif key == "password" and isinstance(value, str):
                account.password = value.strip()
            elif key == "token" and isinstance(value, str):
                account.token = value.strip()
            elif key == "baseUrl" and isinstance(value, str):
                account.baseUrl = value.strip()
            elif key == "salesPageUrl" and isinstance(value, str):
                account.salesPageUrl = value.strip()
        validation_error = self.validate_account_by_platform(account)
        if validation_error:
            return False, validation_error
        await self.config_store.replace_and_persist(config)
        return True, f"Updated sales account `{account.id}` ({account.name})."

    async def remove_account(self, account_id: str) -> tuple[bool, str]:
        config = await self.config_store.get_snapshot()
        before = len(config.accounts)
        config.accounts = [item for item in config.accounts if item.id.lower() != account_id.lower()]
        if before == len(config.accounts):
            return False, f"No account found for id `{account_id}`."
        await self.config_store.replace_and_persist(config)
        return True, f"Removed sales account `{account_id}`."

    async def set_account_enabled(self, account_id: str, enabled: bool) -> tuple[bool, str]:
        config = await self.config_store.get_snapshot()
        account = next((item for item in config.accounts if item.id.lower() == account_id.lower()), None)
        if account is None:
            return False, f"No account found for id `{account_id}`."
        account.enabled = enabled
        await self.config_store.replace_and_persist(config)
        return True, f"Account `{account.id}` is now `{'enabled' if enabled else 'disabled'}`."

    async def set_copy(self, tone: str | None, signature: str | None) -> SalesReportConfig:
        config = await self.config_store.get_snapshot()
        if tone and tone.strip():
            config.messageTone = tone.strip().lower()
        if signature and signature.strip():
            config.signature = signature.strip()
        return await self.config_store.replace_and_persist(config)

    async def run_now(
        self,
        guild: discord.Guild | None,
        direct_channel: discord.abc.Messageable | None,
        override_target_id: str,
        selected_account_id: str,
        daily_overview: bool = False,
    ) -> DispatchResult:
        if guild is None:
            config = await self.config_store.get_snapshot()
            return await self.executor_service.execute_direct(
                config,
                selected_account_id,
                direct_channel,
                daily_overview,
            )
        return await self.scheduler_service.run_now(guild, override_target_id, selected_account_id)

    async def handle_direct_run_now_message(self, message: discord.Message) -> bool:
        if message.author.bot:
            return False
        request = self._parse_direct_run_now_request(message.content)
        if request is None:
            return False
        if request.malformed:
            await message.reply("Usage: `sales run now` or `sales run now account <account-id-or-name>`.")
            return True
        if not await self._has_manage_server_for_direct_run_now_message(message):
            await message.reply("You need Manage Server permission to run `sales run now`.")
            return True

        config = await self.config_store.get_snapshot()
        selected_account_id = self.resolve_requested_account_id(config, request.account_query)
        if request.account_query and not selected_account_id:
            await message.reply(
                f"No account found for `{request.account_query}`. Check the Primo dashboard account list."
            )
            return True

        scope_label = (
            "all enabled accounts"
            if not selected_account_id
            else f"account `{selected_account_id}`"
        )
        if not self.claim_store.try_claim(
            DIRECT_RUN_NOW_CLAIM_NAMESPACE,
            str(message.id),
            timedelta(hours=1),
        ):
            return True
        await message.channel.send(f"Running sales report now for {scope_label}...")
        result = await self.executor_service.execute_direct(
            await self.config_store.get_snapshot(),
            selected_account_id,
            message.channel,
            False,
        )
        await self.reply_direct_run_now_result(message.channel, result)
        return True

    async def build_run_now_account_choices(
        self, interaction: discord.Interaction[discord.Client], current: str
    ) -> list[app_commands.Choice[str]]:
        if interaction.guild is not None and isinstance(interaction.user, discord.Member):
            if not interaction.user.guild_permissions.manage_guild:
                return []
        elif not await self._has_manage_server_for_direct_run_now_user(interaction.user):
            return []

        scope = getattr(interaction.namespace, "scope", "")
        if scope and str(scope).strip().lower() != "single":
            return []

        config = await self.config_store.get_snapshot()
        query = current.strip().lower()
        choices: list[app_commands.Choice[str]] = []
        for account in sorted(
            [item for item in config.accounts if item.id and item.enabled],
            key=lambda item: item.name.lower(),
        ):
            if len(choices) >= DISCORD_AUTOCOMPLETE_MAX_CHOICES:
                break
            platform = account.resolve_platform()
            platform_name = "Unknown" if platform is None else platform.display_name
            account_name = account.name.strip() or account.id
            searchable = f"{account_name} {account.id} {platform_name}".lower()
            if query and query not in searchable:
                continue
            choices.append(
                app_commands.Choice(
                    name=f"{account_name} ({platform_name})",
                    value=account.id,
                )
            )
        return choices

    def resolve_requested_account_id(self, config: SalesReportConfig, raw_query: str) -> str:
        query = raw_query.strip()
        if not query or query.lower() == "all":
            return ""
        for account in config.accounts:
            if query.lower() == account.id.lower():
                return account.id
        for account in config.accounts:
            if query.lower() == account.name.lower():
                return account.id
        return ""

    def validate_account_by_platform(self, account: SalesAccountConfig) -> str | None:
        platform = account.resolve_platform()
        if platform is None:
            return "Invalid account platform."
        if not account.id.strip():
            return "Account id is required."
        if not account.name.strip():
            return "Account name is required."
        if platform is SalesPlatform.UTAK:
            if not account.username.strip() or not account.password.strip():
                return "UTAK accounts require `username` and `password`."
            if not account.salesPageUrl.strip() and not account.baseUrl.strip():
                return "UTAK accounts require `sales-url` or `base-url`."
        if platform is SalesPlatform.LOYVERSE and not account.token.strip():
            return "Loyverse accounts require `token`."
        return None

    async def reply_direct_run_now_result(
        self, channel: discord.abc.Messageable, result: DispatchResult
    ) -> None:
        if result.status == "SENT":
            scope_label = (
                "all enabled accounts"
                if not result.account_id
                else f"account `{result.account_id}`"
            )
            await channel.send(
                f"Sales report sent here for {scope_label}. Success: `{result.success_count}`, Failed: `{result.failure_count}`."
            )
            return
        if result.status == "ACCOUNT_NOT_FOUND":
            if not result.account_id:
                await channel.send("No matching account was found. Check the Primo dashboard account list.")
            else:
                await channel.send(
                    f"No account found for id `{result.account_id}`. Check the Primo dashboard account list."
                )
            return
        await channel.send("Failed to send sales report: " + (result.message or "Unknown error"))

    async def _has_manage_server_for_direct_run_now_message(self, message: discord.Message) -> bool:
        if message.guild is not None and isinstance(message.author, discord.Member):
            return message.author.guild_permissions.manage_guild
        return await self._has_manage_server_for_direct_run_now_user(message.author)

    async def _has_manage_server_for_direct_run_now_user(self, user: discord.abc.User) -> bool:
        if self.default_guild_id:
            guild = self.bot.get_guild(int(self.default_guild_id))
            if guild is not None:
                member = guild.get_member(user.id)
                if member is None:
                    try:
                        member = await guild.fetch_member(user.id)
                    except discord.HTTPException:
                        member = None
                if member is not None and member.guild_permissions.manage_guild:
                    return True
        for guild in self.bot.guilds:
            member = guild.get_member(user.id)
            if member is None:
                try:
                    member = await guild.fetch_member(user.id)
                except discord.HTTPException:
                    continue
            if member.guild_permissions.manage_guild:
                return True
        return False

    def _parse_direct_run_now_request(self, raw_message: str) -> DirectRunNowRequest | None:
        normalized = (raw_message or "").strip()
        if not normalized:
            return None
        normalized = re.sub(r"\s+", " ", normalized)
        match = DIRECT_RUN_NOW_RE.match(normalized)
        if not match:
            return None
        suffix = "" if match.group(1) is None else match.group(1).strip()
        if not suffix or suffix.lower() == "all":
            return DirectRunNowRequest("", False)
        explicit_single_account_syntax = False
        lowered = suffix.lower()
        if lowered.startswith("account"):
            explicit_single_account_syntax = True
            suffix = suffix[len("account") :].strip()
            if suffix.startswith(":"):
                suffix = suffix[1:].strip()
        elif lowered.startswith("single"):
            explicit_single_account_syntax = True
            suffix = suffix[len("single") :].strip()
            if suffix.lower().startswith("account"):
                suffix = suffix[len("account") :].strip()
                if suffix.startswith(":"):
                    suffix = suffix[1:].strip()
        if not suffix and explicit_single_account_syntax:
            return DirectRunNowRequest("", True)
        return DirectRunNowRequest(suffix, False)

    def _describe_next_overview(self, config: SalesReportConfig) -> str:
        if not config.overviewTime.strip():
            return "No daily overview time configured"
        return self._describe_next_scheduled_run([config.overviewTime], config.timezone)

    def _describe_next_scheduled_run(self, slots: Sequence[str], timezone: str) -> str:
        if not slots:
            return "No scheduled times configured"
        zone = resolve_zoneinfo(timezone)
        now = datetime.now(zone)
        next_run: datetime | None = None
        for slot in slots:
            normalized = normalize_hhmm(slot)
            if not normalized:
                continue
            slot_hour, slot_minute = normalized.split(":", 1)
            candidate = now.replace(hour=int(slot_hour), minute=int(slot_minute), second=0, microsecond=0)
            if candidate <= now:
                candidate += timedelta(days=1)
            if next_run is None or candidate < next_run:
                next_run = candidate
        return "Invalid timezone or schedule" if next_run is None else next_run.isoformat()

    def _format_channel_mention(self, channel_id: str) -> str:
        trimmed = channel_id.strip()
        return "(not set)" if not trimmed else f"<#{trimmed}>"

    def _credentials_summary(self, account: SalesAccountConfig) -> str:
        platform = account.resolve_platform()
        if platform is SalesPlatform.UTAK:
            return (
                f"username:{self._masked_flag(account.username)} "
                f"password:{self._masked_flag(account.password)} "
                f"url:{self._present_flag(account.salesPageUrl)}"
            )
        if platform is SalesPlatform.LOYVERSE:
            return (
                f"token:{self._masked_flag(account.token)} "
                f"endpoint:{self._present_flag(account.baseUrl)}"
            )
        return "unknown"

    @staticmethod
    def _masked_flag(value: str) -> str:
        return "missing" if not value.strip() else "set"

    @staticmethod
    def _present_flag(value: str) -> str:
        return "default" if not value.strip() else "custom"


@dataclass(frozen=True, slots=True)
class DispatchResult:
    status: str
    target_channel_id: str
    account_id: str
    message: str
    success_count: int
    failure_count: int


@dataclass(frozen=True, slots=True)
class PreparedDispatch:
    content: str
    success_count: int
    failure_count: int
    zone: Any
    result: DispatchResult | None


@dataclass(frozen=True, slots=True)
class DirectRunNowRequest:
    account_query: str
    malformed: bool


@dataclass(frozen=True, slots=True)
class AuthSession:
    uid: str
    id_token: str


@dataclass(frozen=True, slots=True)
class FetchResult:
    amount: Decimal
    sku_sales: list[SkuSalesEntry]


@dataclass(frozen=True, slots=True)
class SkuIdentity:
    sku_key: str
    display_name: str


@dataclass(frozen=True, slots=True)
class SkuAccumulator:
    sku_key: str
    display_name: str
    sales_amount: Decimal


@dataclass(frozen=True, slots=True)
class PageResult:
    amount: Decimal
    included_count: int
    cursor: str
    sku_sales: list[SkuSalesEntry]


@dataclass(frozen=True, slots=True)
class TransactionAggregation:
    total_net_sales: Decimal
    sku_sales: list[SkuSalesEntry]


@dataclass(frozen=True, slots=True)
class ItemAccumulator:
    sku_key: str
    display_name: str
    amount: Decimal
