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
      color-scheme: dark;
      --bg: #0f172a;
      --panel: #111827;
      --border: #334155;
      --muted: #94a3b8;
      --text: #e2e8f0;
      --accent: #22c55e;
      --button: #2563eb;
      --danger: #dc2626;
    }}
    body {{ margin: 0; font-family: ui-sans-serif, system-ui, sans-serif; background: radial-gradient(circle at top, #1e293b, var(--bg)); color: var(--text); }}
    header {{ padding: 24px 32px; border-bottom: 1px solid var(--border); display: flex; justify-content: space-between; align-items: center; gap: 12px; }}
    main {{ width: min(1100px, calc(100vw - 32px)); margin: 24px auto 64px; display: grid; gap: 16px; }}
    .card {{ background: rgba(15, 23, 42, 0.88); border: 1px solid var(--border); border-radius: 16px; padding: 20px; }}
    h1, h2 {{ margin-top: 0; }}
    p, label, small {{ color: var(--muted); }}
    .grid {{ display: grid; gap: 12px; grid-template-columns: repeat(auto-fit, minmax(240px, 1fr)); }}
    .row {{ display: grid; gap: 8px; margin-bottom: 12px; }}
    input, select, textarea {{ width: 100%; box-sizing: border-box; background: #020617; border: 1px solid var(--border); color: var(--text); border-radius: 10px; padding: 10px 12px; }}
    textarea {{ min-height: 96px; }}
    button {{ background: var(--button); color: white; border: 0; border-radius: 10px; padding: 10px 14px; cursor: pointer; }}
    button.secondary {{ background: #475569; }}
    button.danger {{ background: var(--danger); }}
    .toolbar {{ display: flex; gap: 8px; flex-wrap: wrap; }}
    .banner {{ display: none; border-radius: 12px; padding: 12px 14px; }}
    .banner.visible {{ display: block; }}
    .banner.ok {{ background: rgba(34, 197, 94, 0.12); border: 1px solid rgba(34, 197, 94, 0.35); color: #bbf7d0; }}
    .banner.error {{ background: rgba(239, 68, 68, 0.12); border: 1px solid rgba(239, 68, 68, 0.35); color: #fecaca; }}
    table {{ width: 100%; border-collapse: collapse; }}
    th, td {{ text-align: left; padding: 10px 8px; border-bottom: 1px solid rgba(148, 163, 184, 0.15); vertical-align: top; }}
    .inline {{ display: flex; gap: 8px; flex-wrap: wrap; }}
    .spaced {{ display: flex; justify-content: space-between; align-items: center; gap: 12px; }}
    .muted {{ color: var(--muted); }}
    @media (max-width: 720px) {{
      header {{ padding: 20px 16px; }}
      main {{ width: calc(100vw - 16px); margin: 16px auto 48px; }}
    }}
  </style>
</head>
<body>
  <header>
    <div>
      <h1>Primo Dashboard</h1>
      <p>{safe_guild_name} · signed in as {safe_username}</p>
    </div>
    <div class="toolbar">
      <a href="/dashboard/guilds"><button type="button" class="secondary">Switch Guild</button></a>
      <form method="post" action="/dashboard/logout"><button type="submit" class="danger">Log Out</button></form>
    </div>
  </header>
  <main>
    <div id="banner" class="banner"></div>
    <section class="card">
      <div class="spaced">
        <div>
          <h2>Orders Reminders</h2>
          <p>Schedule, channel routing, and message copy.</p>
        </div>
        <button type="button" onclick="saveOrdersReminders()">Save Orders Reminders</button>
      </div>
      <div class="grid">
        <div class="row"><label>Enabled</label><select id="orders-enabled"><option value="true">Enabled</option><option value="false">Disabled</option></select></div>
        <div class="row"><label>Time</label><input id="orders-time" placeholder="08:00"></div>
        <div class="row"><label>Timezone</label><input id="orders-timezone" placeholder="Asia/Manila"></div>
        <div class="row"><label>Tone</label><select id="orders-tone"><option value="casual">casual</option><option value="formal">formal</option></select></div>
      </div>
      <div class="row"><label>Signature</label><input id="orders-signature"></div>
      <div class="spaced"><h3>Routes</h3><button type="button" class="secondary" onclick="addOrdersRoute()">Add Route</button></div>
      <table><thead><tr><th>Forum</th><th>Target Channel</th><th>Role</th><th></th></tr></thead><tbody id="orders-routes"></tbody></table>
    </section>

    <section class="card">
      <div class="spaced">
        <div>
          <h2>Sales Reports</h2>
          <p>Schedule, delivery targets, and report copy.</p>
        </div>
        <button type="button" onclick="saveSalesSettings()">Save Sales Settings</button>
      </div>
      <div class="grid">
        <div class="row"><label>Enabled</label><select id="sales-enabled"><option value="true">Enabled</option><option value="false">Disabled</option></select></div>
        <div class="row"><label>Timezone</label><input id="sales-timezone" placeholder="Asia/Manila"></div>
        <div class="row"><label>Sales Update Times</label><input id="sales-times" placeholder="09:00,12:00,15:00"></div>
        <div class="row"><label>Default Target</label><select id="sales-target"></select></div>
        <div class="row"><label>Overview Time</label><input id="sales-overview-time" placeholder="21:00"></div>
        <div class="row"><label>Overview Target</label><select id="sales-overview-target"></select></div>
        <div class="row"><label>Tone</label><select id="sales-tone"><option value="casual">casual</option><option value="formal">formal</option></select></div>
      </div>
      <div class="row"><label>Signature</label><input id="sales-signature"></div>
    </section>

    <section class="card">
      <div class="spaced">
        <div>
          <h2>Sales Accounts</h2>
          <p>UTAK and Loyverse credentials. Leave password/token blank to keep the existing secret.</p>
        </div>
        <button type="button" onclick="saveSalesAccounts()">Save Sales Accounts</button>
      </div>
      <div class="toolbar"><button type="button" class="secondary" onclick="addSalesAccount()">Add Account</button></div>
      <table><thead><tr><th>Identity</th><th>Connection</th><th>Secrets</th><th></th></tr></thead><tbody id="sales-accounts"></tbody></table>
    </section>

    <section class="card">
      <div class="spaced">
        <div>
          <h2>Forum Auto-Mentions</h2>
          <p>Ping selected roles when a user creates a new order thread.</p>
        </div>
        <button type="button" onclick="saveForumAutoMentions()">Save Auto-Mentions</button>
      </div>
      <div class="toolbar"><button type="button" class="secondary" onclick="addForumTarget()">Add Mapping</button></div>
      <table><thead><tr><th>Forum</th><th>Roles</th><th></th></tr></thead><tbody id="forum-targets"></tbody></table>
    </section>

    <section class="card">
      <div class="spaced">
        <div>
          <h2>Meta Unread Digest</h2>
          <p>Unread message polling and digest delivery.</p>
        </div>
        <button type="button" onclick="saveMetaUnread()">Save Meta Unread</button>
      </div>
      <div class="grid">
        <div class="row"><label>Enabled</label><select id="meta-unread-enabled"><option value="true">Enabled</option><option value="false">Disabled</option></select></div>
        <div class="row"><label>Interval Minutes</label><input id="meta-unread-interval" type="number" min="5" max="60"></div>
        <div class="row"><label>Target</label><select id="meta-unread-target"></select></div>
      </div>
    </section>

    <section class="card">
      <div class="spaced">
        <div>
          <h2>Meta Webhook Relay</h2>
          <p>Channel or forum destination for inbound Messenger and Instagram webhook posts.</p>
        </div>
        <button type="button" onclick="saveMetaWebhook()">Save Meta Webhook</button>
      </div>
      <div class="row"><label>Target</label><select id="meta-webhook-target"></select></div>
    </section>
  </main>

  <script>
    const guildId = {json.dumps(guild_id)};
    const apiBase = `/api/dashboard/guilds/${{guildId}}`;
    const state = {{ bootstrap: null }};

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

    function buildOptions(items, includeBlank = true) {{
      const html = [];
      if (includeBlank) html.push(`<option value="">Not set</option>`);
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

    function renderOrdersRoute(route = {{ forumId: "", targetTextChannelId: "", mentionRoleId: "" }}) {{
      const forums = buildOptions(state.bootstrap.channels.forums);
      const textChannels = buildOptions(state.bootstrap.channels.text);
      const roles = buildOptions(state.bootstrap.roles, false);
      return `<tr>
        <td><select class="orders-forum">${{forums}}</select></td>
        <td><select class="orders-target">${{textChannels}}</select></td>
        <td><select class="orders-role">${{roles}}</select></td>
        <td><button type="button" class="danger" onclick="this.closest('tr').remove()">Remove</button></td>
      </tr>`;
    }}

    function addOrdersRoute(route) {{
      const tbody = document.getElementById("orders-routes");
      tbody.insertAdjacentHTML("beforeend", renderOrdersRoute(route));
      const row = tbody.lastElementChild;
      row.querySelector(".orders-forum").value = route?.forumId || "";
      row.querySelector(".orders-target").value = route?.targetTextChannelId || "";
      row.querySelector(".orders-role").value = route?.mentionRoleId || "";
    }}

    function renderSalesAccount(account = {{}}) {{
      return `<tr>
        <td>
          <div class="row"><label>ID</label><input class="account-id" value="${{escapeHtml(account.id || "")}}"></div>
          <div class="row"><label>Name</label><input class="account-name" value="${{escapeHtml(account.name || "")}}"></div>
          <div class="row"><label>Platform</label><select class="account-platform"><option value="UTAK">UTAK</option><option value="LOYVERSE">LOYVERSE</option></select></div>
          <div class="row"><label>Enabled</label><select class="account-enabled"><option value="true">Enabled</option><option value="false">Disabled</option></select></div>
        </td>
        <td>
          <div class="row"><label>Username</label><input class="account-username" value="${{escapeHtml(account.username || "")}}"></div>
          <div class="row"><label>Base URL</label><input class="account-base-url" value="${{escapeHtml(account.baseUrl || "")}}"></div>
          <div class="row"><label>Sales Page URL</label><input class="account-sales-url" value="${{escapeHtml(account.salesPageUrl || "")}}"></div>
        </td>
        <td>
          <div class="row"><label>Password</label><input class="account-password" type="password" placeholder="${{account.hasPassword ? "configured" : ""}}"></div>
          <div class="row"><label>Token</label><input class="account-token" type="password" placeholder="${{account.hasToken ? "configured" : ""}}"></div>
        </td>
        <td><button type="button" class="danger" onclick="this.closest('tr').remove()">Remove</button></td>
      </tr>`;
    }}

    function addSalesAccount(account) {{
      const tbody = document.getElementById("sales-accounts");
      tbody.insertAdjacentHTML("beforeend", renderSalesAccount(account));
      const row = tbody.lastElementChild;
      row.querySelector(".account-platform").value = account?.platform || "UTAK";
      row.querySelector(".account-enabled").value = String(account?.enabled ?? true);
    }}

    function renderForumTarget(target = {{ forumId: "", roleIds: [] }}) {{
      return `<tr>
        <td><select class="forum-target-forum">${{buildOptions(state.bootstrap.channels.forums)}}</select></td>
        <td><select class="forum-target-roles" multiple size="4">${{state.bootstrap.roles.map((item) => `<option value="${{item.id}}">${{escapeHtml(item.name)}}</option>`).join("")}}</select></td>
        <td><button type="button" class="danger" onclick="this.closest('tr').remove()">Remove</button></td>
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

      document.getElementById("orders-enabled").value = String(orders.enabled);
      document.getElementById("orders-time").value = orders.time;
      document.getElementById("orders-timezone").value = orders.timezone;
      document.getElementById("orders-tone").value = orders.messageTone;
      document.getElementById("orders-signature").value = orders.signature;
      document.getElementById("orders-routes").innerHTML = "";
      for (const route of orders.routes) addOrdersRoute(route);

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

      document.getElementById("forum-targets").innerHTML = "";
      for (const target of forumTargets.targets) addForumTarget(target);

      document.getElementById("meta-unread-enabled").value = String(metaUnread.enabled);
      document.getElementById("meta-unread-interval").value = metaUnread.intervalMinutes;
      document.getElementById("meta-unread-target").value = metaUnread.targetChannelId || "";

      document.getElementById("meta-webhook-target").value = metaWebhook.targetChannelId || "";
    }}

    async function saveOrdersReminders() {{
      const routes = Array.from(document.querySelectorAll("#orders-routes tr")).map((row) => ({{
        forumId: row.querySelector(".orders-forum").value,
        targetTextChannelId: row.querySelector(".orders-target").value,
        mentionRoleId: row.querySelector(".orders-role").value,
      }}));
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
      showBanner("ok", "Meta unread settings updated.");
    }}

    async function saveMetaWebhook() {{
      await apiFetch("/meta-webhook", {{
        method: "PUT",
        body: JSON.stringify({{
          targetChannelId: document.getElementById("meta-webhook-target").value,
        }}),
      }});
      showBanner("ok", "Meta webhook target updated.");
    }}

    loadDashboard().catch((error) => showBanner("error", error.message));
  </script>
</body>
</html>"""
