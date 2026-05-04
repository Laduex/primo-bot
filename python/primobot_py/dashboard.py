from __future__ import annotations

import asyncio
from base64 import urlsafe_b64decode, urlsafe_b64encode
from dataclasses import asdict, dataclass, field
from datetime import UTC, datetime, timedelta
import hashlib
import hmac
import html
import json
import secrets
import time
from pathlib import Path
from typing import Any
from urllib.error import HTTPError
from urllib.parse import urlencode
from urllib.request import Request, urlopen

import discord

from .meta_unread import MAX_INTERVAL_MINUTES, MIN_INTERVAL_MINUTES
from .orders import OrderHandler
from .reminders import OrdersReminderRoute
from .sales import SalesAccountConfig
from .utils import is_snowflake, is_valid_timezone, normalize_hhmm

DISCORD_API_BASE_URL = "https://discord.com/api/v10"
DISCORD_OAUTH_AUTHORIZE_URL = "https://discord.com/oauth2/authorize"
MANAGE_GUILD_PERMISSION = 1 << 5
DISCORD_API_HEADERS = {
    "Accept": "application/json",
    "User-Agent": "PrimoBotDashboard/0.1 (+https://discord.com/developers/applications)",
}
DISCORD_RATE_LIMIT_MAX_RETRIES = 3
DISCORD_RATE_LIMIT_FALLBACK_DELAY_SECONDS = 1.0


@dataclass(slots=True)
class DashboardConfig:
    forumAutoMentionTargets: dict[str, list[str]] = field(default_factory=dict)
    metaWebhookTargetChannelId: str = ""


@dataclass(frozen=True, slots=True)
class DashboardSession:
    state: str
    user_id: str
    username: str
    global_name: str
    access_token: str
    refresh_token: str
    expires_at_epoch_ms: int

    @property
    def display_name(self) -> str:
        return self.global_name or self.username or self.user_id

    def is_expired(self) -> bool:
        return self.expires_at_epoch_ms <= int(datetime.now(UTC).timestamp() * 1000)


@dataclass(frozen=True, slots=True)
class DiscordGuildAccess:
    id: str
    name: str
    icon: str
    permissions: int
    owner: bool

    @property
    def can_manage(self) -> bool:
        return self.owner or bool(self.permissions & MANAGE_GUILD_PERMISSION)


class DiscordGuildAccessCache:
    def __init__(self, ttl_seconds: int = 60) -> None:
        self._ttl_ms = max(1, ttl_seconds) * 1000
        self._entries: dict[str, tuple[int, list[DiscordGuildAccess]]] = {}

    def get(self, key: str, now_epoch_ms: int) -> list[DiscordGuildAccess] | None:
        entry = self._entries.get(key)
        if entry is None:
            return None
        expires_at_epoch_ms, guilds = entry
        if expires_at_epoch_ms <= now_epoch_ms:
            self._entries.pop(key, None)
            return None
        return list(guilds)

    def put(
        self,
        key: str,
        guilds: list[DiscordGuildAccess],
        now_epoch_ms: int,
        session_expires_at_epoch_ms: int,
    ) -> None:
        expires_at_epoch_ms = min(
            session_expires_at_epoch_ms,
            now_epoch_ms + self._ttl_ms,
        )
        self._entries[key] = (expires_at_epoch_ms, list(guilds))


class DashboardConfigStore:
    def __init__(
        self,
        config_path: str,
        default_forum_auto_mention_targets_raw: str,
        default_meta_webhook_target_channel_id: str,
    ) -> None:
        self._config_path = Path(config_path)
        self._default_targets_raw = default_forum_auto_mention_targets_raw or ""
        self._default_meta_webhook_target_channel_id = (
            default_meta_webhook_target_channel_id or ""
        ).strip()
        self._lock = asyncio.Lock()
        self._current_config: DashboardConfig | None = None

    async def initialize(self) -> None:
        async with self._lock:
            self._config_path.parent.mkdir(parents=True, exist_ok=True)
            if self._config_path.exists():
                try:
                    payload = json.loads(self._config_path.read_text(encoding="utf-8"))
                    self._current_config = self._normalize(self._from_payload(payload))
                except Exception:
                    self._current_config = self._build_default_config()
            else:
                self._current_config = self._build_default_config()
                self._write_config(self._current_config)

    async def get_snapshot(self) -> DashboardConfig:
        async with self._lock:
            if self._current_config is None:
                self._current_config = self._build_default_config()
            return DashboardConfig(
                forumAutoMentionTargets={
                    forum_id: list(role_ids)
                    for forum_id, role_ids in self._current_config.forumAutoMentionTargets.items()
                },
                metaWebhookTargetChannelId=self._current_config.metaWebhookTargetChannelId,
            )

    async def replace_and_persist(self, updated_config: DashboardConfig) -> DashboardConfig:
        async with self._lock:
            normalized = self._normalize(updated_config)
            self._current_config = normalized
            self._write_config(normalized)
            return DashboardConfig(
                forumAutoMentionTargets={
                    forum_id: list(role_ids)
                    for forum_id, role_ids in normalized.forumAutoMentionTargets.items()
                },
                metaWebhookTargetChannelId=normalized.metaWebhookTargetChannelId,
            )

    def _build_default_config(self) -> DashboardConfig:
        parsed = {
            str(forum_id): [str(role_id) for role_id in role_ids]
            for forum_id, role_ids in OrderHandler.parse_forum_auto_mention_targets(
                self._default_targets_raw
            ).items()
        }
        return DashboardConfig(
            forumAutoMentionTargets=parsed,
            metaWebhookTargetChannelId=self._default_meta_webhook_target_channel_id
            if is_snowflake(self._default_meta_webhook_target_channel_id)
            else "",
        )

    def _normalize(self, source: DashboardConfig | None) -> DashboardConfig:
        config = source or DashboardConfig()
        normalized_targets: dict[str, list[str]] = {}
        for forum_id, role_ids in (config.forumAutoMentionTargets or {}).items():
            forum_text = str(forum_id).strip()
            if not is_snowflake(forum_text):
                continue
            deduped_role_ids: list[str] = []
            for role_id in role_ids or []:
                role_text = str(role_id).strip()
                if not is_snowflake(role_text):
                    continue
                if role_text not in deduped_role_ids:
                    deduped_role_ids.append(role_text)
            if deduped_role_ids:
                normalized_targets[forum_text] = deduped_role_ids
        return DashboardConfig(
            forumAutoMentionTargets=normalized_targets,
            metaWebhookTargetChannelId=str(config.metaWebhookTargetChannelId).strip()
            if is_snowflake(str(config.metaWebhookTargetChannelId))
            else "",
        )

    def _write_config(self, config: DashboardConfig) -> None:
        payload = asdict(config)
        self._config_path.parent.mkdir(parents=True, exist_ok=True)
        self._config_path.write_text(json.dumps(payload, indent=2), encoding="utf-8")

    def _from_payload(self, payload: dict[str, Any]) -> DashboardConfig:
        raw_targets = payload.get("forumAutoMentionTargets", {})
        forum_targets: dict[str, list[str]] = {}
        if isinstance(raw_targets, dict):
            for forum_id, role_ids in raw_targets.items():
                if isinstance(role_ids, list):
                    forum_targets[str(forum_id)] = [str(role_id) for role_id in role_ids]
        return DashboardConfig(
            forumAutoMentionTargets=forum_targets,
            metaWebhookTargetChannelId=str(payload.get("metaWebhookTargetChannelId", "")),
        )


class DashboardSessionSigner:
    def __init__(self, secret: str) -> None:
        self._secret = secret.encode("utf-8")

    def dumps(self, session: DashboardSession) -> str:
        payload = json.dumps(asdict(session), separators=(",", ":"), sort_keys=True).encode("utf-8")
        encoded_payload = urlsafe_b64encode(payload).decode("ascii")
        signature = hmac.new(self._secret, encoded_payload.encode("ascii"), hashlib.sha256).hexdigest()
        return encoded_payload + "." + signature

    def loads(self, value: str) -> DashboardSession | None:
        if not value or "." not in value:
            return None
        payload_part, signature = value.rsplit(".", 1)
        expected = hmac.new(self._secret, payload_part.encode("ascii"), hashlib.sha256).hexdigest()
        if not hmac.compare_digest(expected, signature):
            return None
        padded = payload_part + "=" * ((4 - (len(payload_part) % 4)) % 4)
        try:
            decoded = urlsafe_b64decode(padded.encode("ascii")).decode("utf-8")
            payload = json.loads(decoded)
            if not isinstance(payload, dict):
                return None
            return DashboardSession(
                state=str(payload.get("state", "")),
                user_id=str(payload.get("user_id", "")),
                username=str(payload.get("username", "")),
                global_name=str(payload.get("global_name", "")),
                access_token=str(payload.get("access_token", "")),
                refresh_token=str(payload.get("refresh_token", "")),
                expires_at_epoch_ms=int(payload.get("expires_at_epoch_ms", 0)),
            )
        except Exception:
            return None

    @staticmethod
    def issue_state() -> str:
        return secrets.token_urlsafe(24)


class DiscordOAuthClient:
    def __init__(
        self,
        client_id: str,
        client_secret: str,
        redirect_uri: str,
    ) -> None:
        self.client_id = client_id.strip()
        self.client_secret = client_secret.strip()
        self.redirect_uri = redirect_uri.strip()

    def build_authorize_url(self, state: str) -> str:
        query = urlencode(
            {
                "client_id": self.client_id,
                "redirect_uri": self.redirect_uri,
                "response_type": "code",
                "scope": "identify guilds",
                "prompt": "consent",
                "state": state,
            }
        )
        return DISCORD_OAUTH_AUTHORIZE_URL + "?" + query

    def exchange_code(self, code: str) -> DashboardSession:
        response = self._post_form(
            "/oauth2/token",
            {
                "client_id": self.client_id,
                "client_secret": self.client_secret,
                "grant_type": "authorization_code",
                "code": code,
                "redirect_uri": self.redirect_uri,
            },
        )
        access_token = str(response.get("access_token", "")).strip()
        refresh_token = str(response.get("refresh_token", "")).strip()
        expires_in = int(response.get("expires_in", 0))
        user = self._get_json("/users/@me", access_token)
        user_id = str(user.get("id", "")).strip()
        username = str(user.get("username", "")).strip()
        global_name = str(user.get("global_name", "")).strip()
        expires_at_epoch_ms = int(
            (datetime.now(UTC) + timedelta(seconds=max(0, expires_in - 30))).timestamp() * 1000
        )
        return DashboardSession(
            state="",
            user_id=user_id,
            username=username,
            global_name=global_name,
            access_token=access_token,
            refresh_token=refresh_token,
            expires_at_epoch_ms=expires_at_epoch_ms,
        )

    def list_user_guilds(self, access_token: str) -> list[DiscordGuildAccess]:
        payload = self._get_json("/users/@me/guilds", access_token)
        if not isinstance(payload, list):
            return []
        guilds: list[DiscordGuildAccess] = []
        for raw in payload:
            if not isinstance(raw, dict):
                continue
            permissions_raw = raw.get("permissions_new", raw.get("permissions", "0"))
            try:
                permissions = int(str(permissions_raw))
            except ValueError:
                permissions = 0
            guilds.append(
                DiscordGuildAccess(
                    id=str(raw.get("id", "")).strip(),
                    name=str(raw.get("name", "")).strip() or "(unnamed guild)",
                    icon=str(raw.get("icon", "")).strip(),
                    permissions=permissions,
                    owner=bool(raw.get("owner", False)),
                )
            )
        return guilds

    def _post_form(self, path: str, payload: dict[str, str]) -> dict[str, Any]:
        def build_request() -> Request:
            body = urlencode(payload).encode("utf-8")
            return Request(
                DISCORD_API_BASE_URL + path,
                data=body,
                headers={
                    "Content-Type": "application/x-www-form-urlencoded",
                    **DISCORD_API_HEADERS,
                },
                method="POST",
            )

        return self._perform_json_request(build_request)

    def _get_json(self, path: str, access_token: str) -> Any:
        def build_request() -> Request:
            return Request(
                DISCORD_API_BASE_URL + path,
                headers={
                    "Authorization": "Bearer " + access_token,
                    **DISCORD_API_HEADERS,
                },
                method="GET",
            )

        return self._perform_json_request(build_request)

    def _perform_json_request(self, build_request: Any) -> Any:
        last_error: HTTPError | None = None
        for attempt in range(DISCORD_RATE_LIMIT_MAX_RETRIES + 1):
            request = build_request()
            try:
                with urlopen(request, timeout=15) as response:
                    return json.loads(response.read().decode("utf-8"))
            except HTTPError as ex:
                if ex.code != 429 or attempt >= DISCORD_RATE_LIMIT_MAX_RETRIES:
                    raise
                last_error = ex
                retry_delay = self._resolve_retry_delay_seconds(ex)
                time.sleep(retry_delay)
        if last_error is not None:
            raise last_error
        raise RuntimeError("Discord API request failed without a response.")

    def _resolve_retry_delay_seconds(self, error: HTTPError) -> float:
        retry_after_header = error.headers.get("Retry-After", "").strip()
        if retry_after_header:
            try:
                return max(float(retry_after_header), 0.25)
            except ValueError:
                pass
        try:
            payload = json.loads(error.read().decode("utf-8"))
            retry_after = payload.get("retry_after")
            if retry_after is not None:
                return max(float(retry_after), 0.25)
        except Exception:
            pass
        return DISCORD_RATE_LIMIT_FALLBACK_DELAY_SECONDS


class DashboardAccessError(RuntimeError):
    pass


class DashboardConfigService:
    def __init__(
        self,
        bot: discord.Client,
        orders_reminder_store: Any,
        orders_reminder_service: Any,
        sales_report_store: Any,
        sales_command_service: Any,
        meta_unread_store: Any,
        dashboard_store: DashboardConfigStore,
    ) -> None:
        self.bot = bot
        self.orders_reminder_store = orders_reminder_store
        self.orders_reminder_service = orders_reminder_service
        self.sales_report_store = sales_report_store
        self.sales_command_service = sales_command_service
        self.meta_unread_store = meta_unread_store
        self.dashboard_store = dashboard_store

    def list_manageable_shared_guilds(
        self, oauth_guilds: list[DiscordGuildAccess], preferred_guild_id: str
    ) -> list[dict[str, str]]:
        bot_guild_ids = {str(guild.id) for guild in self.bot.guilds}
        manageable: list[dict[str, str]] = []
        for guild in oauth_guilds:
            if guild.id not in bot_guild_ids or not guild.can_manage:
                continue
            manageable.append(
                {
                    "id": guild.id,
                    "name": guild.name,
                    "preferred": "true" if preferred_guild_id and guild.id == preferred_guild_id else "false",
                }
            )
        manageable.sort(key=lambda item: item["name"].lower())
        return manageable

    def require_manageable_guild(
        self, guild_id: str, oauth_guilds: list[DiscordGuildAccess], preferred_guild_id: str
    ) -> discord.Guild:
        manageable = {
            guild["id"]: guild
            for guild in self.list_manageable_shared_guilds(oauth_guilds, preferred_guild_id)
        }
        if guild_id not in manageable:
            raise DashboardAccessError("You do not have Manage Server access to this Primo guild.")
        guild = self.bot.get_guild(int(guild_id))
        if guild is None:
            raise DashboardAccessError("The bot is not currently connected to that guild.")
        return guild

    def build_bootstrap(self, guild: discord.Guild) -> dict[str, Any]:
        text_channels = sorted(
            [
                {"id": str(channel.id), "name": channel.name}
                for channel in guild.channels
                if isinstance(channel, discord.TextChannel)
            ],
            key=lambda item: item["name"].lower(),
        )
        forums = sorted(
            [
                {"id": str(channel.id), "name": channel.name}
                for channel in guild.channels
                if isinstance(channel, discord.ForumChannel)
            ],
            key=lambda item: item["name"].lower(),
        )
        delivery_channels = sorted(
            text_channels
            + [
                {"id": str(channel.id), "name": channel.name + " (forum)"}
                for channel in guild.channels
                if isinstance(channel, discord.ForumChannel)
            ],
            key=lambda item: item["name"].lower(),
        )
        roles = sorted(
            [
                {"id": str(role.id), "name": role.name}
                for role in guild.roles
                if not role.is_default()
            ],
            key=lambda item: item["name"].lower(),
        )
        return {
            "guild": {"id": str(guild.id), "name": guild.name},
            "channels": {"text": text_channels, "delivery": delivery_channels, "forums": forums},
            "roles": roles,
        }

    async def get_orders_reminders(self) -> dict[str, Any]:
        config = await self.orders_reminder_store.get_snapshot()
        return {
            "enabled": config.enabled,
            "timezone": config.timezone,
            "time": f"{config.hour:02d}:{config.minute:02d}",
            "routes": [
                {
                    "forumId": route.forumId,
                    "targetTextChannelId": route.targetTextChannelId,
                    "mentionRoleId": route.mentionRoleId,
                }
                for route in config.routes
            ],
            "messageTone": config.messageTone,
            "signature": config.signature,
        }

    async def update_orders_reminders(
        self,
        guild: discord.Guild,
        payload: dict[str, Any],
    ) -> dict[str, Any]:
        config = await self.orders_reminder_store.get_snapshot()
        config.enabled = bool(payload.get("enabled", config.enabled))
        timezone = str(payload.get("timezone", config.timezone)).strip()
        if not is_valid_timezone(timezone):
            raise DashboardAccessError("Orders reminder timezone is invalid.")
        slot = normalize_hhmm(str(payload.get("time", f"{config.hour:02d}:{config.minute:02d}")))
        if not slot:
            raise DashboardAccessError("Orders reminder time must be in HH:MM format.")
        hour_text, minute_text = slot.split(":", 1)
        config.timezone = timezone
        config.hour = int(hour_text)
        config.minute = int(minute_text)
        routes: list[OrdersReminderRoute] = []
        seen_forum_ids: set[str] = set()
        for raw_route in payload.get("routes", []):
            if not isinstance(raw_route, dict):
                continue
            forum_id = str(raw_route.get("forumId", "")).strip()
            target_channel_id = str(raw_route.get("targetTextChannelId", "")).strip()
            mention_role_id = str(raw_route.get("mentionRoleId", "")).strip()
            if not (
                is_snowflake(forum_id)
                and is_snowflake(target_channel_id)
                and is_snowflake(mention_role_id)
            ):
                raise DashboardAccessError("Every orders reminder route requires a forum, channel, and role.")
            if forum_id in seen_forum_ids:
                raise DashboardAccessError("Orders reminder routes must use each forum only once.")
            forum = guild.get_channel(int(forum_id))
            target = guild.get_channel(int(target_channel_id))
            role = guild.get_role(int(mention_role_id))
            if not isinstance(forum, discord.ForumChannel):
                raise DashboardAccessError("Orders reminder route forum was not found.")
            if not self.orders_reminder_service.is_orders_category_forum(forum):
                raise DashboardAccessError("Orders reminder route forum must be under the Orders category.")
            if not isinstance(target, discord.TextChannel):
                raise DashboardAccessError("Orders reminder route target must be a text channel.")
            if role is None:
                raise DashboardAccessError("Orders reminder route role was not found.")
            routes.append(
                OrdersReminderRoute(
                    forumId=forum_id,
                    targetTextChannelId=target_channel_id,
                    mentionRoleId=mention_role_id,
                )
            )
            seen_forum_ids.add(forum_id)
        config.routes = routes
        config.messageTone = str(payload.get("messageTone", config.messageTone)).strip().lower() or "casual"
        config.signature = str(payload.get("signature", config.signature)).strip() or "Thanks, Primo"
        updated = await self.orders_reminder_store.replace_and_persist(config)
        return await self.get_orders_reminders()

    async def get_sales_settings(self) -> dict[str, Any]:
        config = await self.sales_report_store.get_snapshot()
        return {
            "enabled": config.enabled,
            "timezone": config.timezone,
            "times": list(config.times),
            "targetChannelId": config.targetChannelId,
            "overviewTime": config.overviewTime,
            "overviewTargetChannelId": config.overviewTargetChannelId,
            "messageTone": config.messageTone,
            "signature": config.signature,
        }

    async def update_sales_settings(
        self,
        guild: discord.Guild,
        payload: dict[str, Any],
    ) -> dict[str, Any]:
        config = await self.sales_report_store.get_snapshot()
        config.enabled = bool(payload.get("enabled", config.enabled))
        timezone = str(payload.get("timezone", config.timezone)).strip()
        if not is_valid_timezone(timezone):
            raise DashboardAccessError("Sales report timezone is invalid.")
        config.timezone = timezone
        normalized_times: list[str] = []
        for raw_slot in payload.get("times", []):
            slot = normalize_hhmm(str(raw_slot))
            if not slot:
                raise DashboardAccessError("Sales update times must be in HH:MM format.")
            if slot not in normalized_times:
                normalized_times.append(slot)
        config.times = sorted(normalized_times)
        target_channel_id = str(payload.get("targetChannelId", config.targetChannelId)).strip()
        overview_target_channel_id = str(
            payload.get("overviewTargetChannelId", config.overviewTargetChannelId)
        ).strip()
        overview_time = str(payload.get("overviewTime", config.overviewTime)).strip()
        if target_channel_id and not is_snowflake(target_channel_id):
            raise DashboardAccessError("Sales target channel is invalid.")
        if overview_target_channel_id and not is_snowflake(overview_target_channel_id):
            raise DashboardAccessError("Sales overview target channel is invalid.")
        if overview_time:
            normalized_overview = normalize_hhmm(overview_time)
            if not normalized_overview:
                raise DashboardAccessError("Sales overview time must be in HH:MM format.")
            config.overviewTime = normalized_overview
        else:
            config.overviewTime = ""
        for channel_id in [target_channel_id, overview_target_channel_id]:
            if not channel_id:
                continue
            channel = guild.get_channel(int(channel_id))
            if not isinstance(channel, (discord.TextChannel, discord.ForumChannel)):
                raise DashboardAccessError("Sales targets must be text or forum channels.")
        config.targetChannelId = target_channel_id
        config.overviewTargetChannelId = overview_target_channel_id or target_channel_id
        config.messageTone = str(payload.get("messageTone", config.messageTone)).strip().lower() or "casual"
        config.signature = str(payload.get("signature", config.signature)).strip() or "Thanks, Primo"
        await self.sales_report_store.replace_and_persist(config)
        return await self.get_sales_settings()

    async def get_sales_accounts(self) -> dict[str, Any]:
        config = await self.sales_report_store.get_snapshot()
        accounts = []
        for account in sorted(config.accounts, key=lambda item: item.id.lower()):
            platform = account.resolve_platform()
            accounts.append(
                {
                    "id": account.id,
                    "platform": account.platform,
                    "platformLabel": "Unknown" if platform is None else platform.display_name,
                    "name": account.name,
                    "enabled": account.enabled,
                    "username": account.username,
                    "baseUrl": account.baseUrl,
                    "salesPageUrl": account.salesPageUrl,
                    "hasPassword": bool(account.password.strip()),
                    "hasToken": bool(account.token.strip()),
                }
            )
        return {"accounts": accounts}

    async def update_sales_accounts(self, payload: dict[str, Any]) -> dict[str, Any]:
        current = await self.sales_report_store.get_snapshot()
        existing_by_id = {account.id.lower(): account for account in current.accounts}
        updated_accounts: list[SalesAccountConfig] = []
        seen_ids: set[str] = set()
        for raw_account in payload.get("accounts", []):
            if not isinstance(raw_account, dict):
                continue
            platform = str(raw_account.get("platform", "")).strip().upper()
            account_id = str(raw_account.get("id", "")).strip()
            if not account_id:
                raise DashboardAccessError("Every sales account needs an id.")
            normalized_id = account_id.lower()
            if normalized_id in seen_ids:
                raise DashboardAccessError("Sales account ids must be unique.")
            previous = existing_by_id.get(normalized_id)
            account = SalesAccountConfig(
                id=account_id,
                platform=platform,
                name=str(raw_account.get("name", "")).strip(),
                enabled=bool(raw_account.get("enabled", True)),
                username=str(raw_account.get("username", "")).strip(),
                password=str(raw_account.get("password", "")).strip()
                or ("" if previous is None else previous.password),
                token=str(raw_account.get("token", "")).strip()
                or ("" if previous is None else previous.token),
                baseUrl=str(raw_account.get("baseUrl", "")).strip(),
                salesPageUrl=str(raw_account.get("salesPageUrl", "")).strip(),
            )
            error = self.sales_command_service.validate_account_by_platform(account)
            if error:
                raise DashboardAccessError(error)
            updated_accounts.append(account)
            seen_ids.add(normalized_id)
        current.accounts = updated_accounts
        await self.sales_report_store.replace_and_persist(current)
        return await self.get_sales_accounts()

    async def get_forum_auto_mentions(self) -> dict[str, Any]:
        config = await self.dashboard_store.get_snapshot()
        return {
            "targets": [
                {"forumId": forum_id, "roleIds": list(role_ids)}
                for forum_id, role_ids in sorted(config.forumAutoMentionTargets.items())
            ]
        }

    async def update_forum_auto_mentions(
        self,
        guild: discord.Guild,
        payload: dict[str, Any],
    ) -> dict[str, Any]:
        config = await self.dashboard_store.get_snapshot()
        normalized_targets: dict[str, list[str]] = {}
        for raw_target in payload.get("targets", []):
            if not isinstance(raw_target, dict):
                continue
            forum_id = str(raw_target.get("forumId", "")).strip()
            role_ids = [str(role_id).strip() for role_id in raw_target.get("roleIds", [])]
            if not is_snowflake(forum_id):
                raise DashboardAccessError("Forum auto-mention entries need a valid forum.")
            forum = guild.get_channel(int(forum_id))
            if not isinstance(forum, discord.ForumChannel):
                raise DashboardAccessError("Forum auto-mention forum was not found.")
            if not self._is_orders_category_forum(forum):
                raise DashboardAccessError("Forum auto-mention is limited to Orders category forums.")
            validated_role_ids: list[str] = []
            for role_id in role_ids:
                if not is_snowflake(role_id):
                    raise DashboardAccessError("Forum auto-mention roles must be valid.")
                if guild.get_role(int(role_id)) is None:
                    raise DashboardAccessError("Forum auto-mention role was not found.")
                if role_id not in validated_role_ids:
                    validated_role_ids.append(role_id)
            if validated_role_ids:
                normalized_targets[forum_id] = validated_role_ids
        config.forumAutoMentionTargets = normalized_targets
        await self.dashboard_store.replace_and_persist(config)
        return await self.get_forum_auto_mentions()

    async def get_meta_unread(self) -> dict[str, Any]:
        config = await self.meta_unread_store.get_snapshot()
        return {
            "enabled": config.enabled,
            "intervalMinutes": config.intervalMinutes,
            "targetChannelId": config.targetChannelId,
        }

    async def update_meta_unread(
        self,
        guild: discord.Guild,
        payload: dict[str, Any],
    ) -> dict[str, Any]:
        current = await self.meta_unread_store.get_snapshot()
        current.enabled = bool(payload.get("enabled", current.enabled))
        try:
            current.intervalMinutes = int(payload.get("intervalMinutes", current.intervalMinutes))
        except (TypeError, ValueError):
            raise DashboardAccessError("Meta unread interval must be a number.")
        current.intervalMinutes = max(MIN_INTERVAL_MINUTES, min(MAX_INTERVAL_MINUTES, current.intervalMinutes))
        target_channel_id = str(payload.get("targetChannelId", current.targetChannelId)).strip()
        if target_channel_id:
            if not is_snowflake(target_channel_id):
                raise DashboardAccessError("Meta unread target channel is invalid.")
            target_channel = guild.get_channel(int(target_channel_id))
            if not isinstance(
                target_channel,
                (discord.TextChannel, discord.ForumChannel),
            ):
                raise DashboardAccessError("Meta unread target must be a text or forum channel.")
        current.targetChannelId = target_channel_id
        await self.meta_unread_store.replace_and_persist(current)
        return await self.get_meta_unread()

    async def get_meta_webhook(self) -> dict[str, Any]:
        config = await self.dashboard_store.get_snapshot()
        return {"targetChannelId": config.metaWebhookTargetChannelId}

    async def update_meta_webhook(
        self,
        guild: discord.Guild,
        payload: dict[str, Any],
    ) -> dict[str, Any]:
        config = await self.dashboard_store.get_snapshot()
        target_channel_id = str(payload.get("targetChannelId", config.metaWebhookTargetChannelId)).strip()
        if target_channel_id:
            if not is_snowflake(target_channel_id):
                raise DashboardAccessError("Meta webhook target channel is invalid.")
            target_channel = guild.get_channel(int(target_channel_id))
            if not isinstance(
                target_channel,
                (discord.TextChannel, discord.Thread, discord.ForumChannel),
            ):
                raise DashboardAccessError("Meta webhook target must be a text, thread, or forum channel.")
        config.metaWebhookTargetChannelId = target_channel_id
        await self.dashboard_store.replace_and_persist(config)
        return await self.get_meta_webhook()

    @staticmethod
    def _is_orders_category_forum(channel: discord.ForumChannel) -> bool:
        category = channel.category
        return category is not None and category.name.lower() == "orders"


def render_dashboard_login_page(login_url: str) -> str:
    return f"""<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>Primo Dashboard Login</title>
  <style>
    body {{ font-family: ui-sans-serif, system-ui, sans-serif; background: #111827; color: #f9fafb; margin: 0; min-height: 100vh; display: grid; place-items: center; }}
    .card {{ width: min(480px, calc(100vw - 32px)); background: #1f2937; border: 1px solid #374151; border-radius: 16px; padding: 32px; }}
    a {{ display: inline-block; background: #2563eb; color: white; text-decoration: none; padding: 12px 18px; border-radius: 10px; font-weight: 600; }}
    p {{ color: #d1d5db; }}
  </style>
</head>
<body>
  <div class="card">
    <h1>Primo Settings Dashboard</h1>
    <p>Sign in with Discord, then pick a guild where you have Manage Server permission.</p>
    <a href="{html.escape(login_url, quote=True)}">Continue with Discord</a>
  </div>
</body>
</html>"""


def render_guild_selector_page(
    manageable_guilds: list[dict[str, str]],
    username: str,
    logout_path: str,
) -> str:
    items = []
    for guild in manageable_guilds:
        badge = " <small>preferred</small>" if guild["preferred"] == "true" else ""
        items.append(
            f'<li><a href="/dashboard/guilds/{html.escape(guild["id"], quote=True)}">{html.escape(guild["name"])}{badge}</a></li>'
        )
    body = "".join(items) or "<li>No shared guilds with Manage Server permission were found.</li>"
    return f"""<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>Primo Guilds</title>
  <style>
    body {{ font-family: ui-sans-serif, system-ui, sans-serif; background: #0f172a; color: #e2e8f0; margin: 0; }}
    main {{ width: min(760px, calc(100vw - 32px)); margin: 40px auto; background: #111827; border: 1px solid #334155; border-radius: 16px; padding: 28px; }}
    ul {{ padding-left: 18px; }}
    a {{ color: #93c5fd; }}
    form {{ margin-top: 24px; }}
    button {{ background: #ef4444; color: white; border: 0; border-radius: 8px; padding: 10px 14px; }}
  </style>
</head>
<body>
  <main>
    <h1>Primo Guild Settings</h1>
    <p>Signed in as {html.escape(username)}.</p>
    <ul>{body}</ul>
    <form method="post" action="{html.escape(logout_path, quote=True)}">
      <button type="submit">Log Out</button>
    </form>
  </main>
</body>
</html>"""


def render_guild_dashboard_page(guild_id: str, guild_name: str, username: str) -> str:
    safe_guild_name = html.escape(guild_name)
    safe_username = html.escape(username)
    return f"""<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>Primo Dashboard · {safe_guild_name}</title>
  <style>
    :root {{
      color-scheme: light;
      --bg: #f6f1e7;
      --bg-strong: #efe6d5;
      --panel: #ffffff;
      --panel-soft: #fffaf0;
      --panel-accent: #fff3df;
      --border: #e7dcc8;
      --border-strong: #d9c19a;
      --text: #1f2937;
      --muted: #6b7280;
      --muted-strong: #475569;
      --navy: #081a36;
      --navy-soft: #10213d;
      --amber: #d97706;
      --amber-strong: #b45309;
      --amber-soft: #fff7ed;
      --success: #15803d;
      --success-soft: #dcfce7;
      --danger: #b91c1c;
      --danger-soft: #fee2e2;
      --shadow: 0 16px 40px rgba(8, 26, 54, 0.08);
    }}
    * {{ box-sizing: border-box; }}
    body {{
      margin: 0;
      font-family: "Inter", "Segoe UI", ui-sans-serif, system-ui, sans-serif;
      color: var(--text);
      background:
        radial-gradient(circle at top left, rgba(217, 119, 6, 0.16), transparent 30%),
        radial-gradient(circle at top right, rgba(8, 26, 54, 0.08), transparent 28%),
        linear-gradient(180deg, #fbf7f1 0%, var(--bg) 100%);
    }}
    a {{ color: inherit; }}
    h1, h2, h3, p {{ margin: 0; }}
    .shell {{
      width: min(1180px, calc(100vw - 32px));
      margin: 28px auto 72px;
      display: grid;
      gap: 18px;
    }}
    .shell-card {{
      background: var(--panel);
      border: 1px solid var(--border);
      border-radius: 24px;
      box-shadow: var(--shadow);
    }}
    .hero {{
      display: grid;
      grid-template-columns: minmax(0, 1fr) auto;
      gap: 20px;
      padding: 26px 28px;
      background:
        linear-gradient(135deg, rgba(8, 26, 54, 0.98), rgba(16, 33, 61, 0.94)),
        linear-gradient(120deg, rgba(217, 119, 6, 0.18), transparent 55%);
      color: #fff7ed;
      border-color: rgba(8, 26, 54, 0.35);
    }}
    .eyebrow {{
      display: inline-flex;
      align-items: center;
      gap: 8px;
      padding: 6px 10px;
      border-radius: 999px;
      background: rgba(255, 247, 237, 0.12);
      border: 1px solid rgba(255, 247, 237, 0.18);
      font-size: 12px;
      font-weight: 700;
      letter-spacing: 0.08em;
      text-transform: uppercase;
    }}
    .hero h1 {{
      margin-top: 14px;
      font-size: clamp(2rem, 3vw, 2.75rem);
      line-height: 1.04;
      letter-spacing: -0.04em;
    }}
    .hero p {{
      margin-top: 10px;
      max-width: 60ch;
      color: rgba(255, 247, 237, 0.78);
      font-size: 15px;
      line-height: 1.6;
    }}
    .hero-chips {{
      margin-top: 16px;
      display: flex;
      flex-wrap: wrap;
      gap: 10px;
    }}
    .hero-chip {{
      display: inline-flex;
      align-items: center;
      padding: 8px 12px;
      border-radius: 999px;
      background: rgba(255, 247, 237, 0.12);
      border: 1px solid rgba(255, 247, 237, 0.16);
      color: rgba(255, 247, 237, 0.92);
      font-size: 13px;
      font-weight: 600;
    }}
    .hero-actions {{
      display: flex;
      flex-wrap: wrap;
      align-items: flex-start;
      justify-content: flex-end;
      gap: 10px;
    }}
    .inline-form {{ margin: 0; }}
    .button {{
      display: inline-flex;
      align-items: center;
      justify-content: center;
      min-height: 42px;
      padding: 10px 16px;
      border: 1px solid transparent;
      border-radius: 12px;
      font-size: 14px;
      font-weight: 700;
      text-decoration: none;
      cursor: pointer;
      transition: transform 120ms ease, box-shadow 120ms ease, background 120ms ease, border-color 120ms ease;
    }}
    .button:hover {{ transform: translateY(-1px); }}
    .button:disabled {{
      opacity: 0.55;
      cursor: not-allowed;
      transform: none;
    }}
    .button.primary {{
      background: var(--amber);
      color: white;
      box-shadow: 0 10px 24px rgba(180, 83, 9, 0.18);
    }}
    .button.primary:hover {{ background: var(--amber-strong); }}
    .button.secondary {{
      background: rgba(255, 247, 237, 0.12);
      border-color: rgba(255, 247, 237, 0.18);
      color: #fff7ed;
    }}
    .button.ghost {{
      background: white;
      border-color: var(--border);
      color: var(--muted-strong);
      box-shadow: 0 8px 18px rgba(8, 26, 54, 0.05);
    }}
    .button.ghost.danger-text {{
      border-color: rgba(185, 28, 28, 0.15);
      background: #fff5f5;
      color: var(--danger);
      box-shadow: none;
    }}
    .button.danger {{
      background: var(--danger);
      color: white;
      box-shadow: 0 10px 24px rgba(185, 28, 28, 0.18);
    }}
    .summary-grid {{
      display: grid;
      gap: 14px;
      grid-template-columns: repeat(4, minmax(0, 1fr));
    }}
    .metric-card {{
      padding: 18px 18px 16px;
      background: var(--panel);
      border: 1px solid var(--border);
      border-radius: 20px;
      box-shadow: var(--shadow);
    }}
    .metric-label {{
      font-size: 13px;
      font-weight: 700;
      color: var(--muted-strong);
      text-transform: uppercase;
      letter-spacing: 0.05em;
    }}
    .metric-value {{
      margin-top: 10px;
      font-size: 28px;
      font-weight: 800;
      letter-spacing: -0.04em;
      color: var(--navy);
    }}
    .metric-meta {{
      margin-top: 12px;
      display: flex;
      flex-wrap: wrap;
      gap: 8px;
      align-items: center;
      color: var(--muted);
      font-size: 13px;
      line-height: 1.45;
    }}
    .tabs-shell {{
      padding: 18px 20px 20px;
      background: linear-gradient(180deg, rgba(255, 247, 237, 0.9), rgba(255, 255, 255, 0.96));
    }}
    .tabs-copy {{
      display: flex;
      flex-wrap: wrap;
      align-items: flex-end;
      justify-content: space-between;
      gap: 12px;
      margin-bottom: 14px;
    }}
    .tabs-copy p {{
      margin-top: 6px;
      color: var(--muted);
      font-size: 14px;
    }}
    .tab-track {{
      display: flex;
      gap: 8px;
      padding: 6px;
      border-radius: 18px;
      background: #f3eadc;
      overflow-x: auto;
      scrollbar-width: none;
    }}
    .tab-track::-webkit-scrollbar {{ display: none; }}
    .tab-button {{
      border: 0;
      border-radius: 14px;
      padding: 12px 16px;
      background: transparent;
      color: var(--muted-strong);
      font-size: 14px;
      font-weight: 700;
      white-space: nowrap;
      cursor: pointer;
      transition: background 120ms ease, color 120ms ease, box-shadow 120ms ease;
    }}
    .tab-button.active {{
      background: white;
      color: var(--navy);
      box-shadow: 0 10px 22px rgba(8, 26, 54, 0.08);
    }}
    .banner {{
      display: none;
      border-radius: 16px;
      padding: 14px 16px;
      font-size: 14px;
      font-weight: 600;
      box-shadow: var(--shadow);
    }}
    .banner.visible {{ display: block; }}
    .banner.ok {{
      background: var(--success-soft);
      border: 1px solid rgba(21, 128, 61, 0.18);
      color: var(--success);
    }}
    .banner.error {{
      background: var(--danger-soft);
      border: 1px solid rgba(185, 28, 28, 0.16);
      color: var(--danger);
    }}
    .panel-stack {{ display: grid; gap: 18px; }}
    .panel {{
      display: none;
      gap: 18px;
    }}
    .panel.active {{
      display: grid;
    }}
    .stack-grid {{
      display: grid;
      gap: 18px;
      grid-template-columns: repeat(2, minmax(0, 1fr));
    }}
    .section-card {{
      padding: 22px;
      background: var(--panel);
      border: 1px solid var(--border);
      border-radius: 24px;
      box-shadow: var(--shadow);
    }}
    .section-card.accent {{
      background: linear-gradient(180deg, rgba(255, 247, 237, 0.95), rgba(255, 255, 255, 0.98));
    }}
    .section-header {{
      display: flex;
      flex-wrap: wrap;
      justify-content: space-between;
      align-items: flex-start;
      gap: 14px;
      margin-bottom: 18px;
    }}
    .section-header h2,
    .section-header h3 {{
      color: var(--navy);
      letter-spacing: -0.02em;
    }}
    .section-header p {{
      margin-top: 6px;
      color: var(--muted);
      font-size: 14px;
      line-height: 1.55;
      max-width: 64ch;
    }}
    .section-tag {{
      display: inline-flex;
      align-items: center;
      gap: 8px;
      margin-bottom: 10px;
      color: var(--amber-strong);
      font-size: 12px;
      font-weight: 800;
      letter-spacing: 0.08em;
      text-transform: uppercase;
    }}
    .field-grid {{
      display: grid;
      gap: 14px;
      grid-template-columns: repeat(2, minmax(0, 1fr));
    }}
    .field-grid.three-up {{
      grid-template-columns: repeat(3, minmax(0, 1fr));
    }}
    .field.span-2 {{
      grid-column: span 2;
    }}
    .field label {{
      display: block;
      margin-bottom: 7px;
      color: var(--muted-strong);
      font-size: 13px;
      font-weight: 700;
    }}
    .hint {{
      margin-top: 6px;
      color: var(--muted);
      font-size: 12px;
      line-height: 1.45;
    }}
    .muted {{
      color: var(--muted);
    }}
    input, select, textarea {{
      width: 100%;
      border: 1px solid var(--border);
      border-radius: 14px;
      padding: 11px 13px;
      background: white;
      color: var(--text);
      font: inherit;
      outline: none;
      transition: border-color 120ms ease, box-shadow 120ms ease, background 120ms ease;
    }}
    input:focus, select:focus, textarea:focus {{
      border-color: var(--amber);
      box-shadow: 0 0 0 4px rgba(217, 119, 6, 0.12);
    }}
    select[multiple] {{
      min-height: 132px;
      padding-block: 8px;
    }}
    textarea {{ min-height: 110px; resize: vertical; }}
    .mono {{ font-variant-numeric: tabular-nums; font-family: ui-monospace, SFMono-Regular, Menlo, monospace; }}
    .table-wrap {{
      overflow-x: auto;
      border: 1px solid var(--border);
      border-radius: 20px;
      background: var(--panel-soft);
    }}
    table {{
      width: 100%;
      border-collapse: collapse;
    }}
    th, td {{
      text-align: left;
      vertical-align: top;
      padding: 14px 16px;
      border-bottom: 1px solid var(--border);
    }}
    thead th {{
      background: rgba(255, 247, 237, 0.88);
      color: var(--muted-strong);
      font-size: 12px;
      font-weight: 800;
      letter-spacing: 0.06em;
      text-transform: uppercase;
    }}
    tbody tr:last-child td {{ border-bottom: 0; }}
    .table-cell-stack {{
      display: grid;
      gap: 12px;
    }}
    .empty-row td {{
      padding: 18px 16px;
      color: var(--muted);
      font-size: 14px;
      text-align: center;
      background: rgba(255, 255, 255, 0.85);
    }}
    .status-pill {{
      display: inline-flex;
      align-items: center;
      padding: 6px 10px;
      border-radius: 999px;
      font-size: 12px;
      font-weight: 800;
      letter-spacing: 0.02em;
    }}
    .status-pill.ok {{
      background: var(--success-soft);
      color: var(--success);
    }}
    .status-pill.off {{
      background: #f3f4f6;
      color: var(--muted-strong);
    }}
    .overview-list {{
      display: grid;
      gap: 12px;
    }}
    .detail-row {{
      display: flex;
      justify-content: space-between;
      gap: 16px;
      padding: 12px 0;
      border-bottom: 1px solid var(--border);
      color: var(--muted-strong);
      font-size: 14px;
    }}
    .detail-row:last-child {{ border-bottom: 0; padding-bottom: 0; }}
    .detail-row strong {{
      color: var(--navy);
      text-align: right;
      font-size: 14px;
    }}
    .list-note {{
      margin-top: 12px;
      color: var(--muted);
      font-size: 13px;
      line-height: 1.55;
    }}
    @media (max-width: 1080px) {{
      .summary-grid {{ grid-template-columns: repeat(2, minmax(0, 1fr)); }}
      .stack-grid {{ grid-template-columns: 1fr; }}
    }}
    @media (max-width: 860px) {{
      .hero {{
        grid-template-columns: 1fr;
        padding: 22px;
      }}
      .hero-actions {{
        justify-content: flex-start;
      }}
      .field-grid,
      .field-grid.three-up {{
        grid-template-columns: 1fr;
      }}
      .field.span-2 {{ grid-column: span 1; }}
      table, thead, tbody, tr, td {{
        display: block;
      }}
      thead {{
        display: none;
      }}
      .table-wrap {{
        padding: 12px;
      }}
      tbody {{
        display: grid;
        gap: 12px;
      }}
      tbody tr {{
        border: 1px solid var(--border);
        border-radius: 18px;
        background: white;
        overflow: hidden;
      }}
      tbody td {{
        border-bottom: 1px solid var(--border);
        padding: 12px 14px;
      }}
      tbody td:last-child {{
        border-bottom: 0;
      }}
      tbody td[data-label]::before {{
        content: attr(data-label);
        display: block;
        margin-bottom: 8px;
        color: var(--muted);
        font-size: 11px;
        font-weight: 800;
        letter-spacing: 0.06em;
        text-transform: uppercase;
      }}
      .empty-row td::before {{
        display: none;
      }}
    }}
    @media (max-width: 640px) {{
      .shell {{
        width: calc(100vw - 16px);
        margin: 16px auto 48px;
      }}
      .summary-grid {{
        grid-template-columns: 1fr;
      }}
      .metric-card,
      .section-card,
      .tabs-shell {{
        border-radius: 20px;
      }}
      .hero h1 {{
        font-size: 1.85rem;
      }}
    }}
  </style>
</head>
<body>
  <div class="shell">
    <header class="hero shell-card">
      <div>
        <div class="eyebrow">Primo Control Center</div>
        <h1>Primo Dashboard</h1>
        <p>{safe_guild_name} stays in sync here. Update schedules, routing, Meta relays, and sales accounts without touching slash commands.</p>
        <div class="hero-chips">
          <span class="hero-chip">{safe_guild_name}</span>
          <span class="hero-chip">Signed in as {safe_username}</span>
          <span class="hero-chip">Discord-authenticated settings</span>
        </div>
      </div>
      <div class="hero-actions">
        <a href="/dashboard/guilds" class="button secondary">Switch Guild</a>
        <form method="post" action="/dashboard/logout" class="inline-form">
          <button type="submit" class="button danger">Log Out</button>
        </form>
      </div>
    </header>

    <section class="summary-grid">
      <article class="metric-card">
        <p class="metric-label">Orders</p>
        <p class="metric-value" id="summary-orders-value">0 routes</p>
        <div class="metric-meta" id="summary-orders-meta"></div>
      </article>
      <article class="metric-card">
        <p class="metric-label">Sales</p>
        <p class="metric-value" id="summary-sales-value">0 accounts</p>
        <div class="metric-meta" id="summary-sales-meta"></div>
      </article>
      <article class="metric-card">
        <p class="metric-label">Mentions</p>
        <p class="metric-value" id="summary-mentions-value">0 mappings</p>
        <div class="metric-meta" id="summary-mentions-meta"></div>
      </article>
      <article class="metric-card">
        <p class="metric-label">Meta</p>
        <p class="metric-value" id="summary-meta-value">Not configured</p>
        <div class="metric-meta" id="summary-meta-meta"></div>
      </article>
    </section>

    <section class="shell-card tabs-shell">
      <div class="tabs-copy">
        <div>
          <div class="section-tag">Dashboard Areas</div>
          <h2>Settings that match the Recipe Tracker layout</h2>
          <p>Use the tabs to focus on one operational area at a time. Each panel saves independently.</p>
        </div>
      </div>
      <div class="tab-track">
        <button type="button" class="tab-button active" data-panel-button="overview" onclick="setActivePanel('overview')">Overview</button>
        <button type="button" class="tab-button" data-panel-button="orders" onclick="setActivePanel('orders')">Orders</button>
        <button type="button" class="tab-button" data-panel-button="sales" onclick="setActivePanel('sales')">Sales</button>
        <button type="button" class="tab-button" data-panel-button="mentions" onclick="setActivePanel('mentions')">Auto-Mentions</button>
        <button type="button" class="tab-button" data-panel-button="meta" onclick="setActivePanel('meta')">Meta</button>
      </div>
    </section>

    <div id="banner" class="banner" aria-live="polite"></div>

    <main class="panel-stack">
      <section class="panel active" data-panel="overview">
        <section class="section-card accent">
          <div class="section-header">
            <div>
              <div class="section-tag">Overview</div>
              <h2>Quick health check</h2>
              <p>Review channel inventory, current routing, and where scheduled automation is pointed before you make changes.</p>
            </div>
          </div>
          <div class="stack-grid">
            <div class="section-card">
              <h3>Available Discord resources</h3>
              <div class="overview-list">
                <div class="detail-row"><span>Text channels</span><strong id="resource-text-count">0</strong></div>
                <div class="detail-row"><span>Forum channels</span><strong id="resource-forum-count">0</strong></div>
                <div class="detail-row"><span>Delivery targets</span><strong id="resource-delivery-count">0</strong></div>
                <div class="detail-row"><span>Assignable roles</span><strong id="resource-role-count">0</strong></div>
              </div>
              <p class="list-note">The target lists below are built from these guild resources. Forum routes remain limited to forums under the Orders category.</p>
            </div>
            <div class="section-card">
              <h3>Current destinations</h3>
              <div class="overview-list">
                <div class="detail-row"><span>Sales default target</span><strong id="resource-sales-target">Not set</strong></div>
                <div class="detail-row"><span>Sales overview target</span><strong id="resource-sales-overview-target">Not set</strong></div>
                <div class="detail-row"><span>Meta unread target</span><strong id="resource-meta-unread-target">Not set</strong></div>
                <div class="detail-row"><span>Meta webhook target</span><strong id="resource-meta-webhook-target">Not set</strong></div>
              </div>
              <p class="list-note">Blank password and token fields keep existing saved secrets. Empty targets remain disabled until you save a valid channel.</p>
            </div>
          </div>
        </section>
      </section>

      <section class="panel" data-panel="orders">
        <section class="section-card">
          <div class="section-header">
            <div>
              <div class="section-tag">Orders</div>
              <h2>Orders reminders</h2>
              <p>Configure the daily reminder schedule, message tone, and signature for branch order follow-ups.</p>
            </div>
            <button type="button" class="button primary" onclick="saveOrdersReminders()">Save Orders Reminders</button>
          </div>
          <div class="field-grid three-up">
            <div class="field">
              <label for="orders-enabled">Status</label>
              <select id="orders-enabled">
                <option value="true">Enabled</option>
                <option value="false">Disabled</option>
              </select>
            </div>
            <div class="field">
              <label for="orders-time">Reminder time</label>
              <input id="orders-time" type="time" class="mono" placeholder="08:00">
            </div>
            <div class="field">
              <label for="orders-timezone">Timezone</label>
              <input id="orders-timezone" class="mono" placeholder="Asia/Manila">
            </div>
            <div class="field">
              <label for="orders-tone">Message tone</label>
              <select id="orders-tone">
                <option value="casual">Casual</option>
                <option value="formal">Formal</option>
              </select>
            </div>
            <div class="field span-2">
              <label for="orders-signature">Signature</label>
              <input id="orders-signature" placeholder="Thanks, Primo">
              <p class="hint">Shown at the end of the generated reminder message.</p>
            </div>
          </div>
        </section>

        <section class="section-card">
          <div class="section-header">
            <div>
              <h3>Reminder routes</h3>
              <p>Map each Orders forum to the text channel and role that should receive the reminder. Each forum can only appear once.</p>
            </div>
            <button type="button" class="button ghost" onclick="addOrdersRoute()">Add Route</button>
          </div>
          <div class="table-wrap">
            <table>
              <thead>
                <tr>
                  <th>Forum</th>
                  <th>Target Channel</th>
                  <th>Mention Role</th>
                  <th></th>
                </tr>
              </thead>
              <tbody id="orders-routes"></tbody>
            </table>
          </div>
        </section>
      </section>

      <section class="panel" data-panel="sales">
        <section class="section-card">
          <div class="section-header">
            <div>
              <div class="section-tag">Sales</div>
              <h2>Sales report settings</h2>
              <p>Choose when sales updates run, where they post, and how the report copy should sound.</p>
            </div>
            <button type="button" class="button primary" onclick="saveSalesSettings()">Save Sales Settings</button>
          </div>
          <div class="field-grid three-up">
            <div class="field">
              <label for="sales-enabled">Status</label>
              <select id="sales-enabled">
                <option value="true">Enabled</option>
                <option value="false">Disabled</option>
              </select>
            </div>
            <div class="field">
              <label for="sales-timezone">Timezone</label>
              <input id="sales-timezone" class="mono" placeholder="Asia/Manila">
            </div>
            <div class="field">
              <label for="sales-times">Update times</label>
              <input id="sales-times" class="mono" placeholder="09:00,12:00,15:00">
              <p class="hint">Use comma-separated `HH:MM` values.</p>
            </div>
            <div class="field">
              <label for="sales-target">Default target</label>
              <select id="sales-target"></select>
            </div>
            <div class="field">
              <label for="sales-overview-time">Overview time</label>
              <input id="sales-overview-time" type="time" class="mono" placeholder="21:00">
            </div>
            <div class="field">
              <label for="sales-overview-target">Overview target</label>
              <select id="sales-overview-target"></select>
            </div>
            <div class="field">
              <label for="sales-tone">Message tone</label>
              <select id="sales-tone">
                <option value="casual">Casual</option>
                <option value="formal">Formal</option>
              </select>
            </div>
            <div class="field span-2">
              <label for="sales-signature">Signature</label>
              <input id="sales-signature" placeholder="Thanks, Primo">
            </div>
          </div>
        </section>

        <section class="section-card">
          <div class="section-header">
            <div>
              <h3>Sales accounts</h3>
              <p>Store UTAK and Loyverse account details here. Leaving password or token blank preserves the current saved secret.</p>
            </div>
            <div style="display:flex;gap:10px;flex-wrap:wrap;">
              <button type="button" class="button ghost" onclick="addSalesAccount()">Add Account</button>
              <button type="button" class="button primary" onclick="saveSalesAccounts()">Save Sales Accounts</button>
            </div>
          </div>
          <div class="table-wrap">
            <table>
              <thead>
                <tr>
                  <th>Identity</th>
                  <th>Connection</th>
                  <th>Secrets</th>
                  <th></th>
                </tr>
              </thead>
              <tbody id="sales-accounts"></tbody>
            </table>
          </div>
        </section>
      </section>

      <section class="panel" data-panel="mentions">
        <section class="section-card">
          <div class="section-header">
            <div>
              <div class="section-tag">Auto-Mentions</div>
              <h2>Forum auto-mentions</h2>
              <p>Pick which roles get pinged when a new thread is created in a selected Orders forum.</p>
            </div>
            <div style="display:flex;gap:10px;flex-wrap:wrap;">
              <button type="button" class="button ghost" onclick="addForumTarget()">Add Mapping</button>
              <button type="button" class="button primary" onclick="saveForumAutoMentions()">Save Auto-Mentions</button>
            </div>
          </div>
          <div class="table-wrap">
            <table>
              <thead>
                <tr>
                  <th>Forum</th>
                  <th>Roles</th>
                  <th></th>
                </tr>
              </thead>
              <tbody id="forum-targets"></tbody>
            </table>
          </div>
        </section>
      </section>

      <section class="panel" data-panel="meta">
        <section class="stack-grid">
          <section class="section-card">
            <div class="section-header">
              <div>
                <div class="section-tag">Meta</div>
                <h2>Unread digest</h2>
                <p>Set the polling interval and where unread Messenger and Instagram summaries should land in Discord.</p>
              </div>
              <button type="button" class="button primary" onclick="saveMetaUnread()">Save Meta Unread</button>
            </div>
            <div class="field-grid">
              <div class="field">
                <label for="meta-unread-enabled">Status</label>
                <select id="meta-unread-enabled">
                  <option value="true">Enabled</option>
                  <option value="false">Disabled</option>
                </select>
              </div>
              <div class="field">
                <label for="meta-unread-interval">Interval minutes</label>
                <input id="meta-unread-interval" type="number" min="5" max="60" class="mono">
              </div>
              <div class="field span-2">
                <label for="meta-unread-target">Digest target</label>
                <select id="meta-unread-target"></select>
              </div>
            </div>
          </section>

          <section class="section-card">
            <div class="section-header">
              <div>
                <div class="section-tag">Meta</div>
                <h2>Webhook relay</h2>
                <p>Choose the channel or forum destination for inbound Messenger and Instagram webhook posts.</p>
              </div>
              <button type="button" class="button primary" onclick="saveMetaWebhook()">Save Meta Webhook</button>
            </div>
            <div class="field-grid">
              <div class="field span-2">
                <label for="meta-webhook-target">Webhook target</label>
                <select id="meta-webhook-target"></select>
              </div>
            </div>
          </section>
        </section>
      </section>
    </main>
  </div>

  <script>
    const PANEL_STORAGE_KEY = "primoDashboardActivePanel";
    const guildId = {json.dumps(guild_id)};
    const apiBase = `/api/dashboard/guilds/${{guildId}}`;
    const state = {{ bootstrap: null, data: {{}}, panelReady: false }};

    function showBanner(kind, message) {{
      const banner = document.getElementById("banner");
      banner.className = `banner visible ${{kind}}`;
      banner.textContent = message;
      window.scrollTo({{ top: 0, behavior: "smooth" }});
    }}

    async function apiFetch(path, options = {{}}) {{
      const response = await fetch(apiBase + path, {{
        credentials: "same-origin",
        headers: {{ "Content-Type": "application/json" }},
        ...options,
      }});
      const text = await response.text();
      const data = text ? JSON.parse(text) : {{}};
      if (!response.ok) {{
        throw new Error(data.detail || "Request failed.");
      }}
      return data;
    }}

    function setActivePanel(panelId) {{
      document.querySelectorAll("[data-panel]").forEach((panel) => {{
        panel.classList.toggle("active", panel.dataset.panel === panelId);
      }});
      document.querySelectorAll("[data-panel-button]").forEach((button) => {{
        button.classList.toggle("active", button.dataset.panelButton === panelId);
      }});
      try {{
        localStorage.setItem(PANEL_STORAGE_KEY, panelId);
      }} catch {{
        // Ignore storage errors.
      }}
    }}

    function restoreActivePanel() {{
      try {{
        const stored = localStorage.getItem(PANEL_STORAGE_KEY);
        if (stored && document.querySelector('[data-panel="' + stored + '"]')) {{
          setActivePanel(stored);
          return;
        }}
      }} catch {{
        // Ignore storage errors.
      }}
      setActivePanel("overview");
    }}

    function buildOptions(items, includeBlank = true, blankLabel = "Not set") {{
      const html = [];
      if (includeBlank) html.push(`<option value="">${{escapeHtml(blankLabel)}}</option>`);
      for (const item of items) {{
        html.push(`<option value="${{item.id}}">${{escapeHtml(item.name)}}</option>`);
      }}
      return html.join("");
    }}

    function escapeHtml(value) {{
      return String(value ?? "")
        .replaceAll("&", "&amp;")
        .replaceAll("<", "&lt;")
        .replaceAll(">", "&gt;")
        .replaceAll('"', "&quot;");
    }}

    function setSelectOptions(selectId, items, value = "") {{
      const select = document.getElementById(selectId);
      select.innerHTML = buildOptions(items);
      select.value = value || "";
    }}

    function lookupName(items, id, fallback = "Not set") {{
      if (!id) return fallback;
      return items.find((item) => item.id === id)?.name || fallback;
    }}

    function statusBadge(enabled, onLabel = "Enabled", offLabel = "Disabled") {{
      return `<span class="status-pill ${{enabled ? "ok" : "off"}}">${{enabled ? onLabel : offLabel}}</span>`;
    }}

    function syncEmptyState(tbodyId, columnCount, message) {{
      const tbody = document.getElementById(tbodyId);
      const dataRows = Array.from(tbody.children).filter((row) => !row.classList.contains("empty-row"));
      const existing = tbody.querySelector(".empty-row");
      if (dataRows.length === 0) {{
        if (!existing) {{
          tbody.insertAdjacentHTML("beforeend", `<tr class="empty-row"><td colspan="${{columnCount}}">${{escapeHtml(message)}}</td></tr>`);
        }}
      }} else if (existing) {{
        existing.remove();
      }}
    }}

    function removeTableRow(button, tbodyId, columnCount, message) {{
      button.closest("tr")?.remove();
      syncEmptyState(tbodyId, columnCount, message);
      updateOverview();
    }}

    function updateOverview() {{
      if (!state.bootstrap) return;

      const orders = state.data.orders || {{ enabled: false, time: "", timezone: "", routes: [] }};
      const sales = state.data.sales || {{ enabled: false, times: [], targetChannelId: "", overviewTargetChannelId: "" }};
      const accounts = state.data.accounts?.accounts || [];
      const forumTargets = state.data.forumTargets?.targets || [];
      const metaUnread = state.data.metaUnread || {{ enabled: false, intervalMinutes: 0, targetChannelId: "" }};
      const metaWebhook = state.data.metaWebhook || {{ targetChannelId: "" }};

      const ordersCount = orders.routes.length;
      const salesCount = accounts.length;
      const mentionRoleCount = forumTargets.reduce((sum, item) => sum + (item.roleIds || []).length, 0);

      document.getElementById("summary-orders-value").textContent = `${{ordersCount}} route${{ordersCount === 1 ? "" : "s"}}`;
      document.getElementById("summary-orders-meta").innerHTML =
        `${{statusBadge(orders.enabled)}}<span>${{escapeHtml(orders.time || "--:--")}} · ${{escapeHtml(orders.timezone || "Timezone not set")}}</span>`;

      document.getElementById("summary-sales-value").textContent = `${{salesCount}} account${{salesCount === 1 ? "" : "s"}}`;
      document.getElementById("summary-sales-meta").innerHTML =
        `${{statusBadge(sales.enabled)}}<span>${{(sales.times || []).length}} update time${{(sales.times || []).length === 1 ? "" : "s"}}</span>`;

      document.getElementById("summary-mentions-value").textContent = `${{forumTargets.length}} mapping${{forumTargets.length === 1 ? "" : "s"}}`;
      document.getElementById("summary-mentions-meta").innerHTML =
        `<span>${{mentionRoleCount}} role mention${{mentionRoleCount === 1 ? "" : "s"}} configured</span>`;

      document.getElementById("summary-meta-value").textContent = metaUnread.enabled
        ? `${{metaUnread.intervalMinutes}} min digest`
        : (metaWebhook.targetChannelId ? "Webhook only" : "Not configured");
      document.getElementById("summary-meta-meta").innerHTML =
        `${{statusBadge(metaUnread.enabled, "Digest on", "Digest off")}}<span>Webhook: ${{escapeHtml(lookupName(state.bootstrap.channels.delivery, metaWebhook.targetChannelId, "Not set"))}}</span>`;

      document.getElementById("resource-text-count").textContent = String(state.bootstrap.channels.text.length);
      document.getElementById("resource-forum-count").textContent = String(state.bootstrap.channels.forums.length);
      document.getElementById("resource-delivery-count").textContent = String(state.bootstrap.channels.delivery.length);
      document.getElementById("resource-role-count").textContent = String(state.bootstrap.roles.length);

      document.getElementById("resource-sales-target").textContent = lookupName(
        state.bootstrap.channels.delivery,
        sales.targetChannelId,
        "Not set"
      );
      document.getElementById("resource-sales-overview-target").textContent = lookupName(
        state.bootstrap.channels.delivery,
        sales.overviewTargetChannelId || sales.targetChannelId,
        "Not set"
      );
      document.getElementById("resource-meta-unread-target").textContent = lookupName(
        state.bootstrap.channels.delivery,
        metaUnread.targetChannelId,
        "Not set"
      );
      document.getElementById("resource-meta-webhook-target").textContent = lookupName(
        state.bootstrap.channels.delivery,
        metaWebhook.targetChannelId,
        "Not set"
      );
    }}

    function renderOrdersRoute(route = {{ forumId: "", targetTextChannelId: "", mentionRoleId: "" }}) {{
      const forums = buildOptions(state.bootstrap.channels.forums, true, "Choose a forum");
      const textChannels = buildOptions(state.bootstrap.channels.text, true, "Choose a text channel");
      const roles = buildOptions(state.bootstrap.roles, true, "Choose a role");
      return `<tr>
        <td data-label="Forum"><select class="orders-forum">${{forums}}</select></td>
        <td data-label="Target Channel"><select class="orders-target">${{textChannels}}</select></td>
        <td data-label="Mention Role"><select class="orders-role">${{roles}}</select></td>
        <td data-label="Actions"><button type="button" class="button ghost danger-text" onclick="removeTableRow(this, 'orders-routes', 4, 'No routing rules yet. Add the forums that should post reminders.')">Remove</button></td>
      </tr>`;
    }}

    function addOrdersRoute(route) {{
      const tbody = document.getElementById("orders-routes");
      tbody.insertAdjacentHTML("beforeend", renderOrdersRoute(route));
      const row = tbody.lastElementChild;
      row.querySelector(".orders-forum").value = route?.forumId || "";
      row.querySelector(".orders-target").value = route?.targetTextChannelId || "";
      row.querySelector(".orders-role").value = route?.mentionRoleId || "";
      syncEmptyState("orders-routes", 4, "No routing rules yet. Add the forums that should post reminders.");
      updateOverview();
    }}

    function renderSalesAccount(account = {{}}) {{
      return `<tr>
        <td data-label="Identity">
          <div class="table-cell-stack">
            <div class="field"><label>ID</label><input class="account-id" value="${{escapeHtml(account.id || "")}}"></div>
            <div class="field"><label>Name</label><input class="account-name" value="${{escapeHtml(account.name || "")}}"></div>
            <div class="field"><label>Platform</label><select class="account-platform"><option value="UTAK">UTAK</option><option value="LOYVERSE">LOYVERSE</option></select></div>
            <div class="field"><label>Status</label><select class="account-enabled"><option value="true">Enabled</option><option value="false">Disabled</option></select></div>
          </div>
        </td>
        <td data-label="Connection">
          <div class="table-cell-stack">
            <div class="field"><label>Username</label><input class="account-username" value="${{escapeHtml(account.username || "")}}"></div>
            <div class="field"><label>Base URL</label><input class="account-base-url" value="${{escapeHtml(account.baseUrl || "")}}"></div>
            <div class="field"><label>Sales Page URL</label><input class="account-sales-url" value="${{escapeHtml(account.salesPageUrl || "")}}"></div>
          </div>
        </td>
        <td data-label="Secrets">
          <div class="table-cell-stack">
            <div class="field"><label>Password</label><input class="account-password" type="password" placeholder="${{account.hasPassword ? "Configured" : ""}}"></div>
            <div class="field"><label>Token</label><input class="account-token" type="password" placeholder="${{account.hasToken ? "Configured" : ""}}"></div>
          </div>
        </td>
        <td data-label="Actions"><button type="button" class="button ghost danger-text" onclick="removeTableRow(this, 'sales-accounts', 4, 'No sales accounts added yet. Add UTAK or Loyverse credentials to enable broadcasts.')">Remove</button></td>
      </tr>`;
    }}

    function addSalesAccount(account) {{
      const tbody = document.getElementById("sales-accounts");
      tbody.insertAdjacentHTML("beforeend", renderSalesAccount(account));
      const row = tbody.lastElementChild;
      row.querySelector(".account-platform").value = account?.platform || "UTAK";
      row.querySelector(".account-enabled").value = String(account?.enabled ?? true);
      syncEmptyState("sales-accounts", 4, "No sales accounts added yet. Add UTAK or Loyverse credentials to enable broadcasts.");
      updateOverview();
    }}

    function renderForumTarget(target = {{ forumId: "", roleIds: [] }}) {{
      return `<tr>
        <td data-label="Forum"><select class="forum-target-forum">${{buildOptions(state.bootstrap.channels.forums, true, "Choose a forum")}}</select></td>
        <td data-label="Roles"><select class="forum-target-roles" multiple size="5">${{state.bootstrap.roles.map((item) => `<option value="${{item.id}}">${{escapeHtml(item.name)}}</option>`).join("")}}</select></td>
        <td data-label="Actions"><button type="button" class="button ghost danger-text" onclick="removeTableRow(this, 'forum-targets', 3, 'No forum auto-mention mappings yet.')">Remove</button></td>
      </tr>`;
    }}

    function addForumTarget(target) {{
      const tbody = document.getElementById("forum-targets");
      tbody.insertAdjacentHTML("beforeend", renderForumTarget(target));
      const row = tbody.lastElementChild;
      row.querySelector(".forum-target-forum").value = target?.forumId || "";
      const roles = row.querySelector(".forum-target-roles");
      for (const option of roles.options) {{
        option.selected = (target?.roleIds || []).includes(option.value);
      }}
      syncEmptyState("forum-targets", 3, "No forum auto-mention mappings yet.");
      updateOverview();
    }}

    async function loadDashboard() {{
      state.bootstrap = await apiFetch("/bootstrap");
      setSelectOptions("sales-target", state.bootstrap.channels.delivery);
      setSelectOptions("sales-overview-target", state.bootstrap.channels.delivery);
      setSelectOptions("meta-unread-target", state.bootstrap.channels.delivery);
      setSelectOptions("meta-webhook-target", state.bootstrap.channels.delivery);

      const [orders, sales, accounts, forumTargets, metaUnread, metaWebhook] = await Promise.all([
        apiFetch("/orders-reminders"),
        apiFetch("/sales-settings"),
        apiFetch("/sales-accounts"),
        apiFetch("/forum-auto-mentions"),
        apiFetch("/meta-unread"),
        apiFetch("/meta-webhook"),
      ]);
      state.data = {{ orders, sales, accounts, forumTargets, metaUnread, metaWebhook }};

      document.getElementById("orders-enabled").value = String(orders.enabled);
      document.getElementById("orders-time").value = orders.time;
      document.getElementById("orders-timezone").value = orders.timezone;
      document.getElementById("orders-tone").value = orders.messageTone;
      document.getElementById("orders-signature").value = orders.signature;
      document.getElementById("orders-routes").innerHTML = "";
      for (const route of orders.routes) addOrdersRoute(route);
      syncEmptyState("orders-routes", 4, "No routing rules yet. Add the forums that should post reminders.");

      document.getElementById("sales-enabled").value = String(sales.enabled);
      document.getElementById("sales-timezone").value = sales.timezone;
      document.getElementById("sales-times").value = (sales.times || []).join(",");
      document.getElementById("sales-target").value = sales.targetChannelId || "";
      document.getElementById("sales-overview-time").value = sales.overviewTime || "";
      document.getElementById("sales-overview-target").value = sales.overviewTargetChannelId || "";
      document.getElementById("sales-tone").value = sales.messageTone;
      document.getElementById("sales-signature").value = sales.signature;

      document.getElementById("sales-accounts").innerHTML = "";
      for (const account of accounts.accounts) addSalesAccount(account);
      syncEmptyState("sales-accounts", 4, "No sales accounts added yet. Add UTAK or Loyverse credentials to enable broadcasts.");

      document.getElementById("forum-targets").innerHTML = "";
      for (const target of forumTargets.targets) addForumTarget(target);
      syncEmptyState("forum-targets", 3, "No forum auto-mention mappings yet.");

      document.getElementById("meta-unread-enabled").value = String(metaUnread.enabled);
      document.getElementById("meta-unread-interval").value = metaUnread.intervalMinutes;
      document.getElementById("meta-unread-target").value = metaUnread.targetChannelId || "";

      document.getElementById("meta-webhook-target").value = metaWebhook.targetChannelId || "";

      updateOverview();
      if (!state.panelReady) {{
        restoreActivePanel();
        state.panelReady = true;
      }}
    }}

    async function saveOrdersReminders() {{
      const routes = Array.from(document.querySelectorAll("#orders-routes tr")).map((row) => ({{
        forumId: row.querySelector(".orders-forum").value,
        targetTextChannelId: row.querySelector(".orders-target").value,
        mentionRoleId: row.querySelector(".orders-role").value,
      }})).filter((item) => item.forumId || item.targetTextChannelId || item.mentionRoleId);
      await apiFetch("/orders-reminders", {{
        method: "PUT",
        body: JSON.stringify({{
          enabled: document.getElementById("orders-enabled").value === "true",
          time: document.getElementById("orders-time").value,
          timezone: document.getElementById("orders-timezone").value,
          routes,
          messageTone: document.getElementById("orders-tone").value,
          signature: document.getElementById("orders-signature").value,
        }}),
      }});
      await loadDashboard();
      showBanner("ok", "Orders reminders updated.");
    }}

    async function saveSalesSettings() {{
      await apiFetch("/sales-settings", {{
        method: "PUT",
        body: JSON.stringify({{
          enabled: document.getElementById("sales-enabled").value === "true",
          timezone: document.getElementById("sales-timezone").value,
          times: document.getElementById("sales-times").value.split(",").map((item) => item.trim()).filter(Boolean),
          targetChannelId: document.getElementById("sales-target").value,
          overviewTime: document.getElementById("sales-overview-time").value,
          overviewTargetChannelId: document.getElementById("sales-overview-target").value,
          messageTone: document.getElementById("sales-tone").value,
          signature: document.getElementById("sales-signature").value,
        }}),
      }});
      await loadDashboard();
      showBanner("ok", "Sales settings updated.");
    }}

    async function saveSalesAccounts() {{
      const accounts = Array.from(document.querySelectorAll("#sales-accounts tr")).map((row) => ({{
        id: row.querySelector(".account-id").value.trim(),
        platform: row.querySelector(".account-platform").value,
        name: row.querySelector(".account-name").value.trim(),
        enabled: row.querySelector(".account-enabled").value === "true",
        username: row.querySelector(".account-username").value.trim(),
        password: row.querySelector(".account-password").value,
        token: row.querySelector(".account-token").value,
        baseUrl: row.querySelector(".account-base-url").value.trim(),
        salesPageUrl: row.querySelector(".account-sales-url").value.trim(),
      }})).filter((item) => item.id);
      await apiFetch("/sales-accounts", {{
        method: "PUT",
        body: JSON.stringify({{ accounts }}),
      }});
      await loadDashboard();
      showBanner("ok", "Sales accounts updated.");
    }}

    async function saveForumAutoMentions() {{
      const targets = Array.from(document.querySelectorAll("#forum-targets tr")).map((row) => ({{
        forumId: row.querySelector(".forum-target-forum").value,
        roleIds: Array.from(row.querySelector(".forum-target-roles").selectedOptions).map((option) => option.value),
      }})).filter((item) => item.forumId);
      await apiFetch("/forum-auto-mentions", {{
        method: "PUT",
        body: JSON.stringify({{ targets }}),
      }});
      await loadDashboard();
      showBanner("ok", "Forum auto-mentions updated.");
    }}

    async function saveMetaUnread() {{
      await apiFetch("/meta-unread", {{
        method: "PUT",
        body: JSON.stringify({{
          enabled: document.getElementById("meta-unread-enabled").value === "true",
          intervalMinutes: Number(document.getElementById("meta-unread-interval").value),
          targetChannelId: document.getElementById("meta-unread-target").value,
        }}),
      }});
      await loadDashboard();
      showBanner("ok", "Meta unread settings updated.");
    }}

    async function saveMetaWebhook() {{
      await apiFetch("/meta-webhook", {{
        method: "PUT",
        body: JSON.stringify({{
          targetChannelId: document.getElementById("meta-webhook-target").value,
        }}),
      }});
      await loadDashboard();
      showBanner("ok", "Meta webhook target updated.");
    }}

    loadDashboard().catch((error) => showBanner("error", error.message));
  </script>
</body>
</html>"""
