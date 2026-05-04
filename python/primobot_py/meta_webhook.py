from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime
import hashlib
import hmac
import json
import logging

import discord

LOG = logging.getLogger(__name__)

SIGNATURE_PREFIX = "sha256="
FORUM_POST_TITLE_MAX_LENGTH = 100


@dataclass(frozen=True, slots=True)
class MetaInboundChatEvent:
    platform: str
    page_or_account_id: str
    sender_id: str
    message_id: str
    preview_text: str
    timestamp_ms: int


class MetaWebhookEventExtractor:
    def extract_inbound_chat_events(self, payload: dict[str, object] | None) -> list[MetaInboundChatEvent]:
        if not isinstance(payload, dict):
            return []

        object_type = str(payload.get("object", "")).strip().lower()
        if object_type not in {"page", "instagram"}:
            return []

        platform = "instagram" if object_type == "instagram" else "messenger"
        deduped: dict[str, MetaInboundChatEvent] = {}

        for entry in self._iter_array(payload.get("entry")):
            entry_id = self._safe_trim(self._lookup(entry, "id"))
            self._extract_from_messaging(entry.get("messaging"), platform, entry_id, deduped)
            self._extract_from_changes(entry.get("changes"), platform, entry_id, deduped)

        return list(deduped.values())

    def _extract_from_messaging(
        self,
        messaging_array: object,
        platform: str,
        entry_id: str,
        deduped: dict[str, MetaInboundChatEvent],
    ) -> None:
        for event in self._iter_array(messaging_array):
            message = event.get("message")
            if not isinstance(message, dict):
                continue
            self._add_inbound_event(
                platform,
                entry_id,
                self._lookup(event.get("sender"), "id"),
                self._lookup(event.get("recipient"), "id"),
                message,
                self._as_int(event.get("timestamp")),
                deduped,
            )

    def _extract_from_changes(
        self,
        changes_array: object,
        platform: str,
        entry_id: str,
        deduped: dict[str, MetaInboundChatEvent],
    ) -> None:
        for change in self._iter_array(changes_array):
            field = self._safe_trim(change.get("field")).lower()
            if "message" not in field:
                continue
            value = change.get("value")
            if not isinstance(value, dict):
                continue
            message = value.get("message")
            if not isinstance(message, dict):
                messages = value.get("messages")
                if isinstance(messages, list) and messages and isinstance(messages[0], dict):
                    message = messages[0]
            if not isinstance(message, dict):
                continue

            sender_id = self._safe_trim(self._lookup(value.get("sender"), "id")) or self._safe_trim(
                value.get("from")
            )
            recipient_id = self._safe_trim(self._lookup(value.get("recipient"), "id")) or self._safe_trim(
                value.get("to")
            )
            timestamp_ms = self._as_int(value.get("timestamp")) or self._as_int(value.get("time"))
            self._add_inbound_event(
                platform,
                entry_id,
                sender_id,
                recipient_id,
                message,
                timestamp_ms,
                deduped,
            )

    def _add_inbound_event(
        self,
        platform: str,
        entry_id: str,
        sender_id: object,
        recipient_id: object,
        message: dict[str, object],
        timestamp_ms: int,
        deduped: dict[str, MetaInboundChatEvent],
    ) -> None:
        if bool(message.get("is_echo")):
            return
        sender = self._safe_trim(sender_id)
        if not sender:
            return
        recipient = self._safe_trim(recipient_id)
        if entry_id and entry_id == sender:
            return
        if recipient and recipient == sender:
            return

        message_id = self._safe_trim(message.get("mid")) or self._safe_trim(message.get("id"))
        preview = self._extract_preview(message)
        event = MetaInboundChatEvent(
            platform=platform,
            page_or_account_id=entry_id,
            sender_id=sender,
            message_id=message_id,
            preview_text=preview,
            timestamp_ms=max(0, timestamp_ms),
        )
        dedupe_key = (
            f"{platform}|{entry_id}|{sender}|{message_id}|{event.timestamp_ms}"
        )
        deduped.setdefault(dedupe_key, event)

    def _extract_preview(self, message: dict[str, object]) -> str:
        text = self._safe_trim(message.get("text"))
        if text:
            return text
        body = self._safe_trim(message.get("body"))
        if body:
            return body
        attachments = message.get("attachments")
        if isinstance(attachments, list) and attachments:
            return "(attachment)"
        return "(non-text message)"

    @staticmethod
    def _iter_array(value: object) -> list[dict[str, object]]:
        if not isinstance(value, list):
            return []
        return [item for item in value if isinstance(item, dict)]

    @staticmethod
    def _safe_trim(value: object) -> str:
        return "" if value is None else str(value).strip()

    @staticmethod
    def _lookup(value: object, key: str) -> object:
        if not isinstance(value, dict):
            return ""
        return value.get(key, "")

    @staticmethod
    def _as_int(value: object) -> int:
        try:
            return int(value) if value is not None else 0
        except (TypeError, ValueError):
            return 0


class MetaWebhookRelayService:
    def __init__(
        self,
        bot: discord.Client,
        extractor: MetaWebhookEventExtractor,
        default_guild_id: str,
        verify_token: str,
        fallback_target_channel_id: str,
        app_secret: str,
    ) -> None:
        self.bot = bot
        self.extractor = extractor
        self.default_guild_id = default_guild_id.strip()
        self.verify_token = verify_token.strip()
        self.fallback_target_channel_id = fallback_target_channel_id.strip()
        self.app_secret = app_secret.strip()

    def is_verification_request_valid(self, mode: str | None, token: str | None) -> bool:
        return (
            (mode or "").strip().lower() == "subscribe"
            and self.verify_token
            and self.verify_token == (token or "").strip()
        )

    def is_signature_valid(self, raw_body: str | None, header_signature: str | None) -> bool:
        if not self.app_secret:
            return True
        signature = (header_signature or "").strip()
        if not signature.startswith(SIGNATURE_PREFIX):
            return False
        provided_hex = signature[len(SIGNATURE_PREFIX) :].strip().lower()
        if not provided_hex:
            return False
        digest = hmac.new(
            self.app_secret.encode("utf-8"),
            (raw_body or "").encode("utf-8"),
            hashlib.sha256,
        ).hexdigest()
        return hmac.compare_digest(digest, provided_hex)

    async def relay_inbound_chat_payload(self, raw_body: str | None) -> int:
        try:
            payload = json.loads(raw_body or "{}")
        except json.JSONDecodeError as ex:
            LOG.warning("Ignoring invalid Meta webhook payload: %s", ex)
            return 0

        events = self.extractor.extract_inbound_chat_events(payload)
        if not events:
            return 0

        guild = self._resolve_guild()
        if guild is None:
            LOG.warning("Skipping Meta webhook relay: no Discord guild is available.")
            return 0

        target_channel_id = self.fallback_target_channel_id
        if not target_channel_id:
            LOG.warning("Skipping Meta webhook relay: target channel is not configured.")
            return 0

        relayed = 0
        for event in events:
            try:
                if await self._send_to_target(guild, int(target_channel_id), self._format_event_message(event)):
                    relayed += 1
            except Exception as ex:
                LOG.warning(
                    "Failed relaying Meta chat event to Discord channel %s: %s",
                    target_channel_id,
                    ex,
                )
        return relayed

    async def _send_to_target(
        self, guild: discord.Guild, target_channel_id: int, content: str
    ) -> bool:
        direct_target = guild.get_channel(target_channel_id)
        if isinstance(direct_target, (discord.TextChannel, discord.Thread)):
            await direct_target.send(content)
            return True
        if isinstance(direct_target, discord.ForumChannel):
            await direct_target.create_thread(name=self._build_forum_post_title(), content=content)
            return True
        return False

    def _format_event_message(self, event: MetaInboundChatEvent) -> str:
        platform_label = "Instagram" if event.platform.lower() == "instagram" else "Messenger"
        page_id = event.page_or_account_id.strip() or "(unknown)"
        sender = event.sender_id.strip() or "(unknown)"
        return (
            f"**New {platform_label} chat received**\n"
            f"Sender: `{sender}`\n"
            f"Page/Account: `{page_id}`\n"
            f"Message: {self._sanitize_preview(event.preview_text)}\n"
        )

    def _sanitize_preview(self, preview: str) -> str:
        value = preview.strip()
        if not value:
            return "`(no preview)`"
        if len(value) > 500:
            value = value[:500] + "..."
        return value.replace("@everyone", "@\u200beveryone").replace("@here", "@\u200bhere")

    def _build_forum_post_title(self) -> str:
        title = f"Meta Chat Alert | {datetime.now().strftime('%Y-%m-%d %H:%M')}"
        return title[:FORUM_POST_TITLE_MAX_LENGTH]

    def _resolve_guild(self) -> discord.Guild | None:
        if self.default_guild_id:
            guild = self.bot.get_guild(int(self.default_guild_id))
            if guild is not None:
                return guild
        return self.bot.guilds[0] if self.bot.guilds else None
