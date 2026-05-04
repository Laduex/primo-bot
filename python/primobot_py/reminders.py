from __future__ import annotations

import asyncio
from dataclasses import asdict, dataclass, field
from datetime import datetime, time, timedelta
import json
import logging
from pathlib import Path
from typing import Any

import discord

from .claims import CrossProcessClaimStore
from .utils import chunk_message, is_snowflake, is_valid_timezone, normalize_hhmm, resolve_zoneinfo

LOG = logging.getLogger(__name__)

ORDERS_CATEGORY_NAME = "Orders"
DISCORD_MESSAGE_MAX_LENGTH = 2000
FALLBACK_TIMEZONE = "Asia/Manila"
FALLBACK_DEFAULT_ROUTES = (
    "1494503996357087443:1494175620287041536:1494215754084651089;"
    "1495586918589661384:1494175557208899736:1494215769347854356;"
    "1495586749802610708:1494175305743601755:1494215727857532980;"
    "1495586880983531680:1494175215071006730:1494215689165213818"
)
SCHEDULE_CLAIM_NAMESPACE = "orders-reminder-schedule"


@dataclass(frozen=True, slots=True)
class ReminderThread:
    thread_id: str
    name: str


@dataclass(slots=True)
class OrdersReminderRoute:
    forumId: str
    targetTextChannelId: str
    mentionRoleId: str


@dataclass(slots=True)
class OrdersReminderConfig:
    enabled: bool = True
    timezone: str = "Asia/Manila"
    hour: int = 8
    minute: int = 0
    routes: list[OrdersReminderRoute] = field(default_factory=list)
    lastRunDateByRoute: dict[str, str] = field(default_factory=dict)
    messageTone: str = "casual"
    signature: str = "Thanks, Primo"


class OrdersReminderMessageBuilder:
    def resolve_greeting(self, local_time: time) -> str:
        hour = local_time.hour
        if 5 <= hour <= 11:
            return "Good Morning"
        if 12 <= hour <= 17:
            return "Good Afternoon"
        return "Good Evening"

    def build_reminder_message(
        self,
        mention_role_id: str,
        greeting: str,
        forum_name: str,
        guild_id: str,
        open_threads: list[ReminderThread],
        signature: str | None,
        tone: str,
    ) -> str:
        content = f"<@&{mention_role_id}> {greeting}, team! Here are the orders still open in {forum_name}:\n\n"
        for thread in open_threads:
            content += (
                f"- [{self._escape_brackets(thread.name)}]"
                f"(https://discord.com/channels/{guild_id}/{thread.thread_id})\n"
            )

        trimmed_signature = "" if signature is None else signature.lower().strip()
        if tone.lower().strip() == "formal":
            reminder_line = (
                "Please run `/completed` inside each finished order post so this reminder stays accurate."
            )
        else:
            reminder_line = (
                "If any of these are already done, please run `/completed` inside the order post so I can keep this list updated."
            )

        if not trimmed_signature:
            return content + "\n" + reminder_line
        return content + "\n" + reminder_line + "\n\n" + signature.strip()

    @staticmethod
    def _escape_brackets(value: str | None) -> str:
        if value is None:
            return "Untitled Order"
        return value.replace("[", r"\[").replace("]", r"\]")


class OrdersReminderConfigStore:
    def __init__(
        self,
        config_path: str,
        default_enabled: bool,
        default_time: str,
        default_timezone: str,
        default_routes_raw: str,
    ) -> None:
        self._config_path = Path(config_path)
        self._default_enabled = default_enabled
        self._default_timezone = default_timezone.strip() if default_timezone else FALLBACK_TIMEZONE
        self._default_routes_raw = default_routes_raw.strip() if default_routes_raw else ""

        parsed = normalize_hhmm(default_time) or "08:00"
        hour_text, minute_text = parsed.split(":", 1)
        self._default_hour = int(hour_text)
        self._default_minute = int(minute_text)
        self._lock = asyncio.Lock()
        self._current_config: OrdersReminderConfig | None = None

    async def initialize(self) -> None:
        async with self._lock:
            self._config_path.parent.mkdir(parents=True, exist_ok=True)
            if self._config_path.exists():
                try:
                    payload = json.loads(self._config_path.read_text(encoding="utf-8"))
                    self._current_config = self._normalize(self._from_payload(payload))
                except Exception as ex:
                    LOG.error(
                        "Failed to initialize orders reminder config from %s: %s",
                        self._config_path,
                        ex,
                    )
                    self._current_config = self._build_default_config()
            else:
                self._current_config = self._build_default_config()
                self._write_config(self._current_config)

    async def get_snapshot(self) -> OrdersReminderConfig:
        async with self._lock:
            if self._current_config is None:
                self._current_config = self._build_default_config()
            return self._copy(self._current_config)

    async def replace_and_persist(self, updated_config: OrdersReminderConfig) -> OrdersReminderConfig:
        async with self._lock:
            normalized = self._normalize(updated_config)
            self._current_config = normalized
            self._write_config(normalized)
            return self._copy(normalized)

    def _build_default_config(self) -> OrdersReminderConfig:
        return OrdersReminderConfig(
            enabled=self._default_enabled,
            timezone=self._default_timezone if is_valid_timezone(self._default_timezone) else FALLBACK_TIMEZONE,
            hour=self._default_hour,
            minute=self._default_minute,
            routes=self._parse_default_routes(self._default_routes_raw or FALLBACK_DEFAULT_ROUTES),
            lastRunDateByRoute={},
            messageTone="casual",
            signature="Thanks, Primo",
        )

    def _normalize(self, source: OrdersReminderConfig | None) -> OrdersReminderConfig:
        config = source or OrdersReminderConfig()
        timezone = config.timezone if is_valid_timezone(config.timezone) else FALLBACK_TIMEZONE
        tone = (config.messageTone or "casual").strip().lower() or "casual"
        signature = (config.signature or "Thanks, Primo").strip() or "Thanks, Primo"
        routes: list[OrdersReminderRoute] = []
        for route in config.routes or []:
            if not route:
                continue
            if not (
                is_snowflake(route.forumId)
                and is_snowflake(route.targetTextChannelId)
                and is_snowflake(route.mentionRoleId)
            ):
                continue
            routes.append(
                OrdersReminderRoute(
                    forumId=route.forumId.strip(),
                    targetTextChannelId=route.targetTextChannelId.strip(),
                    mentionRoleId=route.mentionRoleId.strip(),
                )
            )
        return OrdersReminderConfig(
            enabled=bool(config.enabled),
            timezone=timezone,
            hour=max(0, min(23, int(config.hour))),
            minute=max(0, min(59, int(config.minute))),
            routes=routes,
            lastRunDateByRoute=dict(config.lastRunDateByRoute or {}),
            messageTone=tone,
            signature=signature,
        )

    def _parse_default_routes(self, raw_routes: str) -> list[OrdersReminderRoute]:
        routes: list[OrdersReminderRoute] = []
        if not raw_routes.strip():
            return routes
        for raw_entry in raw_routes.split(";"):
            entry = raw_entry.strip()
            if not entry:
                continue
            parts = entry.split(":", 2)
            if len(parts) != 3:
                LOG.warning("Ignoring invalid ORDER_REMINDER_DEFAULT_ROUTES entry %r.", entry)
                continue
            forum_id, channel_id, role_id = (part.strip() for part in parts)
            if not (is_snowflake(forum_id) and is_snowflake(channel_id) and is_snowflake(role_id)):
                LOG.warning("Ignoring invalid ORDER_REMINDER_DEFAULT_ROUTES entry %r.", entry)
                continue
            routes.append(
                OrdersReminderRoute(
                    forumId=forum_id,
                    targetTextChannelId=channel_id,
                    mentionRoleId=role_id,
                )
            )
        return routes

    def _write_config(self, config: OrdersReminderConfig) -> None:
        payload = {
            "enabled": config.enabled,
            "timezone": config.timezone,
            "hour": config.hour,
            "minute": config.minute,
            "routes": [asdict(route) for route in config.routes],
            "lastRunDateByRoute": config.lastRunDateByRoute,
            "messageTone": config.messageTone,
            "signature": config.signature,
        }
        self._config_path.parent.mkdir(parents=True, exist_ok=True)
        self._config_path.write_text(
            json.dumps(payload, indent=2, sort_keys=False),
            encoding="utf-8",
        )

    def _copy(self, source: OrdersReminderConfig) -> OrdersReminderConfig:
        return OrdersReminderConfig(
            enabled=source.enabled,
            timezone=source.timezone,
            hour=source.hour,
            minute=source.minute,
            routes=[
                OrdersReminderRoute(
                    forumId=route.forumId,
                    targetTextChannelId=route.targetTextChannelId,
                    mentionRoleId=route.mentionRoleId,
                )
                for route in source.routes
            ],
            lastRunDateByRoute=dict(source.lastRunDateByRoute),
            messageTone=source.messageTone,
            signature=source.signature,
        )

    def _from_payload(self, payload: dict[str, Any]) -> OrdersReminderConfig:
        routes = []
        for route_payload in payload.get("routes", []):
            if not isinstance(route_payload, dict):
                continue
            routes.append(
                OrdersReminderRoute(
                    forumId=str(route_payload.get("forumId", "")),
                    targetTextChannelId=str(route_payload.get("targetTextChannelId", "")),
                    mentionRoleId=str(route_payload.get("mentionRoleId", "")),
                )
            )
        return OrdersReminderConfig(
            enabled=bool(payload.get("enabled", True)),
            timezone=str(payload.get("timezone", FALLBACK_TIMEZONE)),
            hour=int(payload.get("hour", 8)),
            minute=int(payload.get("minute", 0)),
            routes=routes,
            lastRunDateByRoute={
                str(key): str(value) for key, value in dict(payload.get("lastRunDateByRoute", {})).items()
            },
            messageTone=str(payload.get("messageTone", "casual")),
            signature=str(payload.get("signature", "Thanks, Primo")),
        )


class OrdersReminderService:
    def __init__(
        self,
        bot: discord.Client,
        config_store: OrdersReminderConfigStore,
        message_builder: OrdersReminderMessageBuilder,
        default_guild_id: str,
        claim_store: CrossProcessClaimStore,
    ) -> None:
        self.bot = bot
        self.config_store = config_store
        self.message_builder = message_builder
        self.default_guild_id = default_guild_id.strip()
        self.claim_store = claim_store

    async def status_text(self) -> str:
        config = await self.config_store.get_snapshot()
        routes = "(none)"
        if config.routes:
            routes = "\n".join(
                f"- Forum `<#{route.forumId}>` -> Channel `<#{route.targetTextChannelId}>`, Role `<@&{route.mentionRoleId}>`"
                for route in sorted(config.routes, key=lambda item: item.forumId)
            )
        return (
            "**Orders Reminder Settings**\n"
            f"Enabled: `{config.enabled}`\n"
            f"Time: `{config.hour:02d}:{config.minute:02d}`\n"
            f"Timezone: `{config.timezone}`\n"
            f"Tone: `{config.messageTone}`\n"
            f"Signature: `{config.signature}`\n"
            f"Next Run: `{self.describe_next_run(config)}`\n\n"
            f"**Routes**\n{routes}"
        )

    async def set_enabled(self, enabled: bool) -> OrdersReminderConfig:
        config = await self.config_store.get_snapshot()
        config.enabled = enabled
        return await self.config_store.replace_and_persist(config)

    async def set_time(self, hour: int, minute: int, timezone: str | None) -> OrdersReminderConfig:
        config = await self.config_store.get_snapshot()
        config.hour = hour
        config.minute = minute
        if timezone is not None and timezone.strip():
            config.timezone = timezone.strip()
        return await self.config_store.replace_and_persist(config)

    async def set_route(
        self, forum: discord.ForumChannel, target: discord.TextChannel, role: discord.Role
    ) -> OrdersReminderConfig:
        config = await self.config_store.get_snapshot()
        config.routes = [route for route in config.routes if route.forumId != str(forum.id)]
        config.routes.append(
            OrdersReminderRoute(
                forumId=str(forum.id),
                targetTextChannelId=str(target.id),
                mentionRoleId=str(role.id),
            )
        )
        return await self.config_store.replace_and_persist(config)

    async def remove_route(self, forum_id: int) -> tuple[bool, OrdersReminderConfig]:
        config = await self.config_store.get_snapshot()
        before = len(config.routes)
        config.routes = [route for route in config.routes if route.forumId != str(forum_id)]
        removed = before != len(config.routes)
        if removed:
            config.lastRunDateByRoute.pop(str(forum_id), None)
        updated = await self.config_store.replace_and_persist(config)
        return removed, updated

    async def set_copy(self, tone: str | None, signature: str | None) -> OrdersReminderConfig:
        config = await self.config_store.get_snapshot()
        if tone is not None and tone.strip():
            config.messageTone = tone.strip().lower()
        if signature is not None and signature.strip():
            config.signature = signature.strip()
        return await self.config_store.replace_and_persist(config)

    async def run_tick(self) -> None:
        config = await self.config_store.get_snapshot()
        if not config.enabled:
            return

        zone = resolve_zoneinfo(config.timezone, FALLBACK_TIMEZONE)
        now = datetime.now(zone)
        if now.hour != config.hour or now.minute != config.minute:
            return

        guild = self.resolve_guild()
        if guild is None:
            LOG.warning("Orders reminder skipped: no guild available.")
            return

        today_text = now.date().isoformat()
        config_updated = False
        for route in config.routes:
            if today_text == config.lastRunDateByRoute.get(route.forumId):
                continue
            claim_key = f"{today_text}|{route.forumId}"
            if not self.claim_store.try_claim(
                SCHEDULE_CLAIM_NAMESPACE,
                claim_key,
                timedelta(days=2),
            ):
                continue

            forum = guild.get_channel(int(route.forumId))
            if not isinstance(forum, discord.ForumChannel):
                self.claim_store.release(SCHEDULE_CLAIM_NAMESPACE, claim_key)
                LOG.warning("Orders reminder route skipped: forum %s not found.", route.forumId)
                continue

            target = guild.get_channel(int(route.targetTextChannelId))
            if not isinstance(target, discord.TextChannel):
                self.claim_store.release(SCHEDULE_CLAIM_NAMESPACE, claim_key)
                LOG.warning(
                    "Orders reminder route skipped: target channel %s not found.",
                    route.targetTextChannelId,
                )
                continue

            role = guild.get_role(int(route.mentionRoleId))
            if role is None:
                self.claim_store.release(SCHEDULE_CLAIM_NAMESPACE, claim_key)
                LOG.warning("Orders reminder route skipped: role %s not found.", route.mentionRoleId)
                continue

            open_threads = sorted(
                [thread for thread in forum.threads if not thread.archived],
                key=lambda thread: thread.name.lower(),
            )
            if not open_threads:
                self.claim_store.release(SCHEDULE_CLAIM_NAMESPACE, claim_key)
                continue

            content = self.message_builder.build_reminder_message(
                mention_role_id=route.mentionRoleId,
                greeting=self.message_builder.resolve_greeting(now.time()),
                forum_name=forum.name,
                guild_id=str(guild.id),
                open_threads=[
                    ReminderThread(thread_id=str(thread.id), name=thread.name) for thread in open_threads
                ],
                signature=config.signature,
                tone=config.messageTone,
            )

            try:
                for chunk in chunk_message(content, DISCORD_MESSAGE_MAX_LENGTH):
                    await target.send(chunk)
            except discord.HTTPException as ex:
                self.claim_store.release(SCHEDULE_CLAIM_NAMESPACE, claim_key)
                LOG.warning("Failed sending orders reminder for forum %s: %s", forum.id, ex)
                continue

            config.lastRunDateByRoute[route.forumId] = today_text
            config_updated = True
            LOG.info(
                "Posted orders reminder for forum %s into channel %s.",
                forum.id,
                target.id,
            )

        if config_updated:
            await self.config_store.replace_and_persist(config)

    async def dispatch_manual_reminder(
        self, guild: discord.Guild, route: OrdersReminderRoute
    ) -> tuple[str, str, str | None]:
        config = await self.config_store.get_snapshot()
        forum = guild.get_channel(int(route.forumId))
        if not isinstance(forum, discord.ForumChannel):
            return "FORUM_NOT_FOUND", "", None

        target = guild.get_channel(int(route.targetTextChannelId))
        if not isinstance(target, discord.TextChannel):
            return "TARGET_NOT_FOUND", "", None

        role = guild.get_role(int(route.mentionRoleId))
        if role is None:
            return "ROLE_NOT_FOUND", "", None

        self_member = guild.me or await guild.fetch_member(self.bot.user.id)  # type: ignore[arg-type]
        if not self.can_bot_mention_role(self_member, target, role):
            return "ROLE_CANNOT_BE_MENTIONED", "", None

        open_threads = sorted(
            [thread for thread in forum.threads if not thread.archived],
            key=lambda thread: thread.name.lower(),
        )
        if not open_threads:
            return "NO_OPEN_ORDERS", forum.name, None

        zone = resolve_zoneinfo(config.timezone, FALLBACK_TIMEZONE)
        now = datetime.now(zone)
        content = self.message_builder.build_reminder_message(
            mention_role_id=route.mentionRoleId,
            greeting=self.message_builder.resolve_greeting(now.time()),
            forum_name=forum.name,
            guild_id=str(guild.id),
            open_threads=[ReminderThread(str(thread.id), thread.name) for thread in open_threads],
            signature=config.signature,
            tone=config.messageTone,
        )

        try:
            for chunk in chunk_message(content, DISCORD_MESSAGE_MAX_LENGTH):
                await target.send(chunk)
        except discord.HTTPException as ex:
            return "SEND_FAILED", forum.name, str(ex)

        config.lastRunDateByRoute[route.forumId] = now.date().isoformat()
        await self.config_store.replace_and_persist(config)
        return "SENT", forum.name, None

    def resolve_guild(self) -> discord.Guild | None:
        if self.default_guild_id:
            guild = self.bot.get_guild(int(self.default_guild_id))
            if guild is not None:
                return guild
        return self.bot.guilds[0] if self.bot.guilds else None

    def describe_next_run(self, config: OrdersReminderConfig) -> str:
        zone = resolve_zoneinfo(config.timezone, FALLBACK_TIMEZONE)
        now = datetime.now(zone)
        run_at = now.replace(hour=config.hour, minute=config.minute, second=0, microsecond=0)
        if run_at <= now:
            run_at += timedelta(days=1)
        return run_at.isoformat()

    @staticmethod
    def is_orders_category_forum(forum: discord.ForumChannel | None) -> bool:
        return bool(
            forum is not None
            and forum.category is not None
            and forum.category.name.lower() == ORDERS_CATEGORY_NAME.lower()
        )

    @staticmethod
    def can_bot_mention_role(
        self_member: discord.Member | None,
        target_channel: discord.TextChannel,
        role: discord.Role,
    ) -> bool:
        if self_member is None:
            return False
        permissions = target_channel.permissions_for(self_member)
        return role.mentionable or permissions.mention_everyone
