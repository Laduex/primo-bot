from __future__ import annotations

import asyncio
from dataclasses import asdict, dataclass
from datetime import UTC, datetime, timedelta
import hashlib
import hmac
import json
import logging
from pathlib import Path
from typing import Any, Protocol
from urllib.error import HTTPError
from urllib.parse import urlencode
from urllib.request import Request, urlopen

import discord

from .claims import CrossProcessClaimStore
from .utils import chunk_message, is_snowflake

LOG = logging.getLogger(__name__)
SCHEDULE_CLAIM_NAMESPACE = "meta-unread-schedule"

DISCORD_MESSAGE_MAX_LENGTH = 2000
FORUM_POST_TITLE_MAX_LENGTH = 100
MIN_INTERVAL_MINUTES = 5
MAX_INTERVAL_MINUTES = 60
PAGES_FIELDS = "id,name,access_token,instagram_business_account"
CONVERSATION_FIELDS = "id,updated_time,snippet,unread_count,senders"
CONVERSATION_PAGE_LIMIT = 5
MAX_UNREAD_CONVERSATIONS_PER_PAGE_PLATFORM = 5
MAX_UNREAD_CONVERSATIONS_PER_PLATFORM = 5
RUN_AT_FORMAT = "%Y-%m-%d %H:%M:%S %Z"
AUTO_REPLY_MARKER = "we've received your message and will get back to you shortly"


@dataclass(slots=True)
class MetaUnreadConfig:
    enabled: bool = False
    targetChannelId: str = ""
    intervalMinutes: int = 15
    lastRunAtEpochMs: int = 0


@dataclass(frozen=True, slots=True)
class MetaPageAccess:
    page_id: str
    page_name: str
    page_access_token: str
    instagram_account_id: str
    instagram_username: str


@dataclass(frozen=True, slots=True)
class MetaUnreadConversation:
    page_id: str
    page_name: str
    platform: str
    conversation_id: str
    sender_name: str
    snippet: str
    unread_count: int
    updated_time: str


@dataclass(frozen=True, slots=True)
class MetaUnreadSnapshot:
    pages_scanned: int
    unread_thread_count: int
    unread_message_count: int
    conversations: list[MetaUnreadConversation]
    warnings: list[str]


class MetaGraphApiException(RuntimeError):
    def __init__(
        self,
        status: int,
        code: int | None,
        subcode: int | None,
        type_name: str,
        fb_trace_id: str,
        error_message: str,
    ) -> None:
        self.status = status
        self.code = code
        self.subcode = subcode
        self.type_name = type_name or ""
        self.fb_trace_id = fb_trace_id or ""
        self.error_message = error_message or "Unknown Meta API error"
        super().__init__(
            "Meta API error status=%s code=%s subcode=%s type=%s message=%s fbtrace_id=%s"
            % (
                status,
                "?" if code is None else code,
                "?" if subcode is None else subcode,
                self.type_name,
                self.error_message,
                self.fb_trace_id,
            )
        )

    def brief_message(self) -> str:
        return "status=%d code=%s message=%s" % (
            self.status,
            "?" if self.code is None else self.code,
            self.error_message,
        )


class MetaUnreadConfigStore:
    def __init__(
        self,
        config_path: str,
        default_enabled: bool,
        default_interval_minutes: int,
        default_target_channel_id: str,
    ) -> None:
        self._config_path = Path(config_path)
        self._default_enabled = default_enabled
        self._default_interval_minutes = self._clamp_interval(default_interval_minutes)
        self._default_target_channel_id = (default_target_channel_id or "").strip()
        self._lock = asyncio.Lock()
        self._current_config: MetaUnreadConfig | None = None

    async def initialize(self) -> None:
        async with self._lock:
            self._config_path.parent.mkdir(parents=True, exist_ok=True)
            if self._config_path.exists():
                try:
                    payload = json.loads(self._config_path.read_text(encoding="utf-8"))
                    self._current_config = self._normalize(self._from_payload(payload))
                except Exception as ex:
                    LOG.error(
                        "Failed to initialize meta unread config from %s: %s",
                        self._config_path,
                        ex,
                    )
                    self._current_config = self._build_default_config()
            else:
                self._current_config = self._build_default_config()
                self._write_config(self._current_config)

    async def get_snapshot(self) -> MetaUnreadConfig:
        async with self._lock:
            if self._current_config is None:
                self._current_config = self._build_default_config()
            return MetaUnreadConfig(**asdict(self._current_config))

    async def replace_and_persist(self, updated_config: MetaUnreadConfig) -> MetaUnreadConfig:
        async with self._lock:
            normalized = self._normalize(updated_config)
            self._current_config = normalized
            self._write_config(normalized)
            return MetaUnreadConfig(**asdict(normalized))

    def _build_default_config(self) -> MetaUnreadConfig:
        return MetaUnreadConfig(
            enabled=self._default_enabled,
            targetChannelId=self._default_target_channel_id
            if is_snowflake(self._default_target_channel_id)
            else "",
            intervalMinutes=self._default_interval_minutes,
            lastRunAtEpochMs=0,
        )

    def _normalize(self, source: MetaUnreadConfig | None) -> MetaUnreadConfig:
        config = source or MetaUnreadConfig()
        return MetaUnreadConfig(
            enabled=bool(config.enabled),
            targetChannelId=config.targetChannelId.strip()
            if is_snowflake(config.targetChannelId)
            else "",
            intervalMinutes=self._clamp_interval(config.intervalMinutes),
            lastRunAtEpochMs=max(0, int(config.lastRunAtEpochMs)),
        )

    def _clamp_interval(self, minutes: int) -> int:
        return max(MIN_INTERVAL_MINUTES, min(MAX_INTERVAL_MINUTES, int(minutes)))

    def _write_config(self, config: MetaUnreadConfig) -> None:
        self._config_path.parent.mkdir(parents=True, exist_ok=True)
        self._config_path.write_text(json.dumps(asdict(config), indent=2), encoding="utf-8")

    def _from_payload(self, payload: dict[str, Any]) -> MetaUnreadConfig:
        return MetaUnreadConfig(
            enabled=bool(payload.get("enabled", False)),
            targetChannelId=str(payload.get("targetChannelId", "")),
            intervalMinutes=int(payload.get("intervalMinutes", 15)),
            lastRunAtEpochMs=int(payload.get("lastRunAtEpochMs", 0)),
        )


class MetaUnreadApiClient(Protocol):
    def list_pages(self) -> list[MetaPageAccess]:
        raise NotImplementedError

    def list_unread_conversations(
        self, page: MetaPageAccess, platform: str
    ) -> list[MetaUnreadConversation]:
        raise NotImplementedError


class MetaGraphApiClient:
    def __init__(
        self,
        api_base_url: str,
        graph_version: str,
        user_access_token: str,
        app_secret: str,
    ) -> None:
        self.api_base_url = self._normalize_base_url(api_base_url)
        self.graph_version = self._normalize_graph_version(graph_version)
        self.user_access_token = (user_access_token or "").strip()
        self.app_secret = (app_secret or "").strip()

    def list_pages(self) -> list[MetaPageAccess]:
        if not self.user_access_token:
            raise RuntimeError("META_ACCESS_TOKEN is missing.")
        pages: list[MetaPageAccess] = []
        after = ""
        while True:
            params = {"fields": PAGES_FIELDS, "limit": "100"}
            if after:
                params["after"] = after
            root = self._call_graph("/me/accounts", self.user_access_token, params)
            for node in self._data_nodes(root):
                page_id = self._text(node, "id")
                page_name = self._text(node, "name")
                page_token = self._text(node, "access_token")
                ig = node.get("instagram_business_account", {})
                ig_id = self._text(ig, "id") if isinstance(ig, dict) else ""
                ig_username = self._text(ig, "username") if isinstance(ig, dict) else ""
                if not page_id or not page_token:
                    continue
                pages.append(
                    MetaPageAccess(
                        page_id,
                        page_name or page_id,
                        page_token,
                        ig_id,
                        ig_username,
                    )
                )
            after = self._next_after_cursor(root)
            if not after:
                break
        pages.sort(key=lambda item: item.page_name.lower())
        return pages

    def list_unread_conversations(
        self, page: MetaPageAccess, platform: str
    ) -> list[MetaUnreadConversation]:
        if not page.page_id or not page.page_access_token:
            return []
        normalized_platform = self._normalize_platform(platform)
        unread: list[MetaUnreadConversation] = []
        after = ""
        while True:
            params = {"fields": CONVERSATION_FIELDS, "limit": str(CONVERSATION_PAGE_LIMIT)}
            if normalized_platform == "instagram":
                params["platform"] = "instagram"
            if after:
                params["after"] = after
            root = self._call_graph(f"/{page.page_id}/conversations", page.page_access_token, params)
            for node in self._data_nodes(root):
                unread_count = self._int_value(node.get("unread_count"))
                if unread_count <= 0:
                    continue
                unread.append(
                    MetaUnreadConversation(
                        page.page_id,
                        page.page_name,
                        "instagram" if normalized_platform == "instagram" else "facebook",
                        self._text(node, "id"),
                        self._resolve_sender_name(node.get("senders"), page.page_name),
                        self._text(node, "snippet"),
                        unread_count,
                        self._text(node, "updated_time"),
                    )
                )
                if len(unread) >= MAX_UNREAD_CONVERSATIONS_PER_PAGE_PLATFORM:
                    break
            after = self._next_after_cursor(root)
            if not after or len(unread) >= MAX_UNREAD_CONVERSATIONS_PER_PAGE_PLATFORM:
                break
        unread.sort(key=lambda item: item.updated_time, reverse=True)
        return unread

    def _call_graph(self, path: str, access_token: str, params: dict[str, str]) -> dict[str, Any]:
        url = self._build_url(path, access_token, params)
        request = Request(url, method="GET")
        try:
            with urlopen(request, timeout=30) as response:
                body = response.read().decode("utf-8")
                status = response.status
        except HTTPError as ex:
            body = ex.read().decode("utf-8", errors="replace")
            raise self._parse_api_error(ex.code, body) from ex
        except Exception as ex:
            raise RuntimeError(f"Meta API request failed: {ex}") from ex

        if status >= 400:
            raise self._parse_api_error(status, body)
        try:
            return json.loads(body or "{}")
        except json.JSONDecodeError as ex:
            raise RuntimeError("Meta API returned invalid JSON") from ex

    def _build_url(self, path: str, access_token: str, params: dict[str, str]) -> str:
        normalized_path = path if path.startswith("/") else "/" + path
        query = dict(params)
        query["access_token"] = access_token or ""
        proof = self._app_secret_proof(access_token)
        if proof:
            query["appsecret_proof"] = proof
        return (
            f"{self.api_base_url}/{self.graph_version}{normalized_path}?"
            + urlencode(query)
        )

    def _app_secret_proof(self, token: str) -> str:
        if not token or not self.app_secret:
            return ""
        digest = hmac.new(
            self.app_secret.encode("utf-8"),
            token.encode("utf-8"),
            hashlib.sha256,
        ).hexdigest()
        return digest

    def _parse_api_error(self, status: int, body: str) -> MetaGraphApiException:
        try:
            root = json.loads(body or "{}")
        except json.JSONDecodeError:
            return MetaGraphApiException(status, None, None, "", "", "Unknown Meta API error")
        error = root.get("error", {}) if isinstance(root, dict) else {}
        return MetaGraphApiException(
            status,
            self._int_or_none(error.get("code")),
            self._int_or_none(error.get("error_subcode")),
            str(error.get("type", "")),
            str(error.get("fbtrace_id", "")),
            str(error.get("message", "Unknown Meta API error")),
        )

    def _next_after_cursor(self, root: dict[str, Any]) -> str:
        paging = root.get("paging", {})
        cursors = paging.get("cursors", {}) if isinstance(paging, dict) else {}
        after = cursors.get("after", "") if isinstance(cursors, dict) else ""
        return str(after).strip()

    def _resolve_sender_name(self, senders: Any, fallback_page_name: str) -> str:
        if not isinstance(senders, dict):
            return fallback_page_name or "Unknown sender"
        data = senders.get("data")
        if not isinstance(data, list):
            return fallback_page_name or "Unknown sender"
        for sender in data:
            if not isinstance(sender, dict):
                continue
            name = self._text(sender, "name")
            if name:
                return name
        return fallback_page_name or "Unknown sender"

    def _data_nodes(self, root: dict[str, Any]) -> list[dict[str, Any]]:
        data = root.get("data")
        if not isinstance(data, list):
            return []
        return [item for item in data if isinstance(item, dict)]

    def _text(self, node: Any, field: str) -> str:
        if not isinstance(node, dict):
            return ""
        value = node.get(field, "")
        return "" if value is None else str(value).strip()

    def _int_value(self, value: Any) -> int:
        try:
            return max(0, int(value))
        except (TypeError, ValueError):
            return 0

    def _int_or_none(self, value: Any) -> int | None:
        try:
            return int(value)
        except (TypeError, ValueError):
            return None

    def _normalize_platform(self, platform: str) -> str:
        value = (platform or "").strip().lower()
        if value in {"facebook", "messenger"}:
            return "facebook"
        if value == "instagram":
            return "instagram"
        return value

    def _normalize_base_url(self, raw: str) -> str:
        value = (raw or "https://graph.facebook.com").strip()
        while value.endswith("/"):
            value = value[:-1]
        return value

    def _normalize_graph_version(self, raw: str) -> str:
        value = (raw or "v24.0").strip()
        if value.startswith("/"):
            value = value[1:]
        return value


class MetaUnreadCollectorService:
    def __init__(self, api_client: MetaUnreadApiClient) -> None:
        self.api_client = api_client

    def collect_unread(self) -> MetaUnreadSnapshot:
        pages = self.api_client.list_pages()
        all_conversations: list[MetaUnreadConversation] = []
        warnings: list[str] = []
        facebook_count = 0
        instagram_count = 0

        for page in pages:
            facebook_unread = self._collect_platform_unread(
                page,
                "facebook",
                MAX_UNREAD_CONVERSATIONS_PER_PLATFORM - facebook_count,
                warnings,
            )
            facebook_count += len(facebook_unread)
            all_conversations.extend(facebook_unread)

            instagram_unread = self._collect_platform_unread(
                page,
                "instagram",
                MAX_UNREAD_CONVERSATIONS_PER_PLATFORM - instagram_count,
                warnings,
            )
            instagram_count += len(instagram_unread)
            all_conversations.extend(instagram_unread)

        all_conversations.sort(
            key=lambda item: (
                item.updated_time or "",
                item.page_name.lower(),
                item.conversation_id.lower(),
            ),
            reverse=True,
        )
        unread_messages = sum(max(0, item.unread_count) for item in all_conversations)
        return MetaUnreadSnapshot(
            len(pages),
            len(all_conversations),
            unread_messages,
            list(all_conversations),
            list(warnings),
        )

    def _collect_platform_unread(
        self,
        page: MetaPageAccess,
        platform: str,
        remaining_limit: int,
        warnings: list[str],
    ) -> list[MetaUnreadConversation]:
        if remaining_limit <= 0:
            return []
        try:
            conversations = self.api_client.list_unread_conversations(page, platform)
            return conversations[:remaining_limit]
        except MetaGraphApiException as ex:
            warnings.append(
                f"{self._display_platform(platform)} unread check failed for `{page.page_name}`: {ex.brief_message()}"
            )
        except RuntimeError as ex:
            warnings.append(
                f"{self._display_platform(platform)} unread check failed for `{page.page_name}`: {self._brief_runtime_message(str(ex))}"
            )
        return []

    def _display_platform(self, platform: str) -> str:
        value = (platform or "").strip().lower()
        if value in {"facebook", "messenger"}:
            return "Facebook"
        if value == "instagram":
            return "Instagram"
        return platform or "Meta"

    def _brief_runtime_message(self, message: str) -> str:
        normalized = (message or "").strip()
        if not normalized:
            return "Unknown error"
        return normalized if len(normalized) <= 220 else normalized[:217] + "..."


class MetaUnreadMessageBuilder:
    def build_digest(self, snapshot: MetaUnreadSnapshot, run_at: datetime | None = None) -> str:
        safe_snapshot = snapshot
        run_at = datetime.now().astimezone() if run_at is None else run_at
        conversations = [item for item in safe_snapshot.conversations if item is not None]

        lines = [
            "**Meta Unread Digest**",
            f"Run At: `{run_at.strftime(RUN_AT_FORMAT)}`",
            f"Pages Scanned: `{safe_snapshot.pages_scanned}`",
            f"Unread Conversations: `{safe_snapshot.unread_thread_count}`",
            f"Unread Messages: `{safe_snapshot.unread_message_count}`",
            "",
        ]
        self._append_platform_summary(lines, conversations)
        self._append_page_summary(lines, conversations)
        lines.extend(["", "**Recent Unread Conversations**"])
        self._append_conversation_sections(lines, conversations, run_at)
        if safe_snapshot.warnings:
            lines.extend(["", "**Instagram Warnings**"])
            for warning in safe_snapshot.warnings:
                lines.append("- " + self._sanitize_line(warning, 300))
        return "\n".join(lines).strip()

    def _append_platform_summary(
        self, lines: list[str], conversations: list[MetaUnreadConversation]
    ) -> None:
        messenger_threads = messenger_messages = instagram_threads = instagram_messages = 0
        for conversation in conversations:
            unread = max(0, conversation.unread_count)
            platform = self._normalize_platform(conversation.platform)
            if platform == "instagram":
                instagram_threads += 1
                instagram_messages += unread
            else:
                messenger_threads += 1
                messenger_messages += unread
        lines.extend(
            [
                "**By Platform**",
                f"- Messenger: threads `{messenger_threads}`, messages `{messenger_messages}`",
                f"- Instagram: threads `{instagram_threads}`, messages `{instagram_messages}`",
            ]
        )

    def _append_page_summary(
        self, lines: list[str], conversations: list[MetaUnreadConversation]
    ) -> None:
        lines.extend(["", "**By Page**"])
        if not conversations:
            lines.append("- (none)")
            return
        grouped: dict[str, PageSummary] = {}
        for conversation in conversations:
            key = self._group_key(conversation)
            summary = grouped.setdefault(
                key,
                PageSummary(
                    self._sanitize_line(conversation.page_name, 80),
                    self._normalize_platform(conversation.platform),
                    0,
                    0,
                ),
            )
            summary.thread_count += 1
            summary.message_count += max(0, conversation.unread_count)
        ordered = sorted(
            grouped.values(),
            key=lambda item: (-item.message_count, item.page_name.lower(), item.platform.lower()),
        )
        for summary in ordered:
            lines.append(
                f"- {summary.page_name} ({summary.platform}): threads `{summary.thread_count}`, messages `{summary.message_count}`"
            )

    def _append_conversation_sections(
        self,
        lines: list[str],
        conversations: list[MetaUnreadConversation],
        run_at: datetime,
    ) -> None:
        if not conversations:
            lines.append("- (none)")
            return
        display_count = min(15, len(conversations))
        for index, conversation in enumerate(conversations[:display_count], start=1):
            lines.extend(
                [
                    f"{index}.",
                    f"   Name: {self._sanitize_line(conversation.sender_name, 80)}",
                    f"   Platform: {self._display_platform(self._normalize_platform(conversation.platform))}",
                    f"   Unread Messages: `{max(0, conversation.unread_count)}`",
                    f"   Snippet: {self._normalize_snippet(conversation.snippet)}",
                    f"   Time Received: `{self._sanitize_line(conversation.updated_time, 40)}`",
                    f"   Days Ago: `{self._days_ago_label(conversation.updated_time, run_at)}`",
                ]
            )
        hidden_count = len(conversations) - display_count
        if hidden_count > 0:
            lines.append(f"- ...and `{hidden_count}` more unread conversation(s).")

    def _group_key(self, conversation: MetaUnreadConversation) -> str:
        return f"**{self._sanitize_line(conversation.page_name, 80)}** ({self._normalize_platform(conversation.platform)})"

    def _normalize_platform(self, platform: str) -> str:
        normalized = self._sanitize_line(platform, 20).lower()
        if normalized in {"facebook", "messenger"}:
            return "messenger"
        if normalized == "instagram":
            return "instagram"
        return normalized

    def _normalize_snippet(self, snippet: str) -> str:
        normalized = self._sanitize_line(snippet, 120)
        if normalized == "(no text)":
            return normalized
        if AUTO_REPLY_MARKER in normalized.lower():
            return "(auto-reply acknowledgement)"
        return normalized

    def _days_ago_label(self, updated_time: str, run_at: datetime) -> str:
        normalized = self._sanitize_line(updated_time, 40)
        if normalized == "(no text)":
            return normalized
        try:
            updated = datetime.fromisoformat(normalized.replace("Z", "+00:00"))
            age = run_at.astimezone(UTC) - updated.astimezone(UTC)
            if age.total_seconds() < 0:
                return "0m"
            return self._compact_age(age)
        except Exception:
            return "(unknown)"

    def _display_platform(self, platform: str) -> str:
        if platform == "messenger":
            return "Messenger (account)"
        if platform == "instagram":
            return "Instagram"
        return self._sanitize_line(platform, 20)

    def _compact_age(self, duration: timedelta) -> str:
        days = duration.days
        if days > 0:
            return f"{days}d"
        hours = int(duration.total_seconds() // 3600)
        if hours > 0:
            return f"{hours}h"
        minutes = max(1, int(duration.total_seconds() // 60))
        return f"{minutes}m"

    def _sanitize_line(self, value: str, max_length: int) -> str:
        normalized = " ".join((value or "").strip().split())
        if not normalized:
            return "(no text)"
        if len(normalized) <= max_length:
            return normalized
        return normalized[: max(0, max_length - 3)].rstrip() + "..."


class MetaUnreadExecutorService:
    def __init__(
        self,
        collector_service: MetaUnreadCollectorService,
        message_builder: MetaUnreadMessageBuilder,
    ) -> None:
        self.collector_service = collector_service
        self.message_builder = message_builder

    async def execute(
        self, guild: discord.Guild | None, config: MetaUnreadConfig
    ) -> MetaUnreadDispatchResult:
        if guild is None:
            return MetaUnreadDispatchResult("GUILD_NOT_FOUND", "", "No guild available", 0, 0, 0, 0)
        target_id = (config.targetChannelId if config else "").strip()
        if not target_id:
            return MetaUnreadDispatchResult("TARGET_NOT_CONFIGURED", "", "No target channel configured", 0, 0, 0, 0)
        try:
            snapshot = await asyncio.to_thread(self.collector_service.collect_unread)
        except Exception as ex:
            return MetaUnreadDispatchResult("FETCH_FAILED", target_id, str(ex) or "Unread check failed", 0, 0, 0, 0)
        if snapshot.unread_thread_count <= 0:
            return MetaUnreadDispatchResult(
                "NO_UNREAD",
                target_id,
                "No unread conversations",
                snapshot.pages_scanned,
                snapshot.unread_thread_count,
                snapshot.unread_message_count,
                len(snapshot.warnings),
            )
        content = self.message_builder.build_digest(snapshot)
        chunks = chunk_message(content, DISCORD_MESSAGE_MAX_LENGTH)
        try:
            direct_target = guild.get_channel(int(target_id))
            if isinstance(direct_target, (discord.TextChannel, discord.Thread)):
                for chunk in chunks:
                    await direct_target.send(chunk)
                return MetaUnreadDispatchResult("SENT", target_id, "Sent", snapshot.pages_scanned, snapshot.unread_thread_count, snapshot.unread_message_count, len(snapshot.warnings))
            if isinstance(direct_target, discord.ForumChannel):
                created = await direct_target.create_thread(
                    name=self._build_forum_post_title(),
                    content=chunks[0],
                )
                for chunk in chunks[1:]:
                    await created.thread.send(chunk)
                return MetaUnreadDispatchResult("SENT", target_id, "Sent", snapshot.pages_scanned, snapshot.unread_thread_count, snapshot.unread_message_count, len(snapshot.warnings))
            existing = guild.get_channel(int(target_id))
            if existing is not None:
                return MetaUnreadDispatchResult("TARGET_UNSUPPORTED", target_id, "Configured target channel type is not supported for digest delivery", snapshot.pages_scanned, snapshot.unread_thread_count, snapshot.unread_message_count, len(snapshot.warnings))
            return MetaUnreadDispatchResult("TARGET_NOT_FOUND", target_id, "Target channel was not found", snapshot.pages_scanned, snapshot.unread_thread_count, snapshot.unread_message_count, len(snapshot.warnings))
        except Exception as ex:
            return MetaUnreadDispatchResult("SEND_FAILED", target_id, str(ex) or "Failed sending digest", snapshot.pages_scanned, snapshot.unread_thread_count, snapshot.unread_message_count, len(snapshot.warnings))

    def _build_forum_post_title(self) -> str:
        title = "Meta Unread Digest | " + datetime.now().strftime("%Y-%m-%d %H:%M")
        return title[:FORUM_POST_TITLE_MAX_LENGTH]


class MetaUnreadSchedulerService:
    def __init__(
        self,
        bot: discord.Client,
        config_store: MetaUnreadConfigStore,
        executor_service: MetaUnreadExecutorService,
        default_guild_id: str,
        claim_store: CrossProcessClaimStore,
    ) -> None:
        self.bot = bot
        self.config_store = config_store
        self.executor_service = executor_service
        self.default_guild_id = default_guild_id.strip()
        self.claim_store = claim_store

    async def run_meta_unread_tick(self) -> None:
        config = await self.config_store.get_snapshot()
        if not config.enabled:
            return
        now = int(datetime.now().timestamp() * 1000)
        if not self.is_due(config, now):
            return
        interval_minutes = max(5, config.intervalMinutes)
        window_bucket = now // (interval_minutes * 60_000)
        claim_key = f"{config.targetChannelId or 'default'}|{window_bucket}"
        if not self.claim_store.try_claim(
            SCHEDULE_CLAIM_NAMESPACE,
            claim_key,
            timedelta(minutes=interval_minutes * 2),
        ):
            return
        guild = self.resolve_guild()
        result = await self.executor_service.execute(guild, config)
        config.lastRunAtEpochMs = now
        await self.config_store.replace_and_persist(config)
        self._log_result("scheduled", result)

    async def run_now(self, guild: discord.Guild | None) -> MetaUnreadDispatchResult:
        config = await self.config_store.get_snapshot()
        result = await self.executor_service.execute(guild, config)
        config.lastRunAtEpochMs = int(datetime.now().timestamp() * 1000)
        await self.config_store.replace_and_persist(config)
        return result

    def is_due(self, config: MetaUnreadConfig, now_epoch_ms: int) -> bool:
        last_run = max(0, config.lastRunAtEpochMs)
        interval_minutes = max(5, config.intervalMinutes)
        interval_ms = interval_minutes * 60_000
        if last_run <= 0:
            return True
        return now_epoch_ms - last_run >= interval_ms

    def resolve_guild(self) -> discord.Guild | None:
        if self.default_guild_id:
            guild = self.bot.get_guild(int(self.default_guild_id))
            if guild is not None:
                return guild
        return self.bot.guilds[0] if self.bot.guilds else None

    def _log_result(self, source: str, result: MetaUnreadDispatchResult) -> None:
        if result.status == "SENT":
            LOG.info(
                "Meta unread %s digest sent to channel %s (pages=%s, unreadThreads=%s, unreadMessages=%s, warnings=%s).",
                source,
                result.target_channel_id,
                result.pages_scanned,
                result.unread_threads,
                result.unread_messages,
                result.warning_count,
            )
        elif result.status == "NO_UNREAD":
            LOG.info(
                "Meta unread %s check found no unread conversations (pages=%s, warnings=%s).",
                source,
                result.pages_scanned,
                result.warning_count,
            )
        else:
            LOG.warning("Meta unread %s check did not send digest: %s", source, result.message)


@dataclass(slots=True)
class PageSummary:
    page_name: str
    platform: str
    thread_count: int
    message_count: int


@dataclass(frozen=True, slots=True)
class MetaUnreadDispatchResult:
    status: str
    target_channel_id: str
    message: str
    pages_scanned: int
    unread_threads: int
    unread_messages: int
    warning_count: int
