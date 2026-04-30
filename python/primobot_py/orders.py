from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime, timedelta
import logging
import re

import discord
from discord import app_commands

from .utils import normalize_snowflake

LOG = logging.getLogger(__name__)

ORDERS_CATEGORY_NAME = "Orders"
FOLLOW_HINT = "New Order! Acknowledge and follow the post for updates."
ORDER_FORUM_TITLE_MAX_LENGTH = 100
DISCORD_MESSAGE_MAX_LENGTH = 2000
DISCORD_AUTOCOMPLETE_MAX_CHOICES = 25
ORDER_REQUEST_TTL = timedelta(minutes=10)
HISTORY_LOOKBACK_COUNT = 15
SNOWFLAKE_RE = re.compile(r"\d+")


@dataclass(frozen=True, slots=True)
class PendingOrderContext:
    guild_id: int
    forum_id: int
    author_id: int
    customer_name: str
    created_at: datetime


class OrderHandler:
    def __init__(self, bot_user_id: callable[[], int | None]) -> None:
        self._pending: dict[str, PendingOrderContext] = {}
        self._completed_interactions: dict[int, datetime] = {}
        self._processed_thread_ids: set[int] = set()
        self._bot_user_id = bot_user_id

    async def create_order(
        self, interaction: discord.Interaction[discord.Client], forum_value: str, customer_name: str
    ) -> None:
        guild = interaction.guild
        member = interaction.user if isinstance(interaction.user, discord.Member) else None
        if guild is None or member is None:
            await interaction.response.send_message(
                "This command can only be used inside a Discord server.",
                ephemeral=True,
            )
            return

        forum = self._resolve_forum(guild, forum_value)
        if forum is None:
            await interaction.response.send_message(
                "Please select a valid forum channel in the `forum` option.",
                ephemeral=True,
            )
            return
        if not self._is_orders_category_forum(forum):
            await interaction.response.send_message(
                f"Please choose a forum under the `{ORDERS_CATEGORY_NAME}` category.",
                ephemeral=True,
            )
            return
        if not self._can_member_create_forum_post(member, forum):
            await interaction.response.send_message(
                f"You do not have permission to create posts in {forum.mention}.",
                ephemeral=True,
            )
            return

        customer = customer_name.strip()
        if not customer:
            await interaction.response.send_message(
                "Customer name cannot be empty.",
                ephemeral=True,
            )
            return

        title_prefix = datetime.now().strftime("%B %-d | %A")
        preview_title = f"{title_prefix} | {customer}"
        if len(preview_title) > ORDER_FORUM_TITLE_MAX_LENGTH:
            allowed = max(1, ORDER_FORUM_TITLE_MAX_LENGTH - (len(title_prefix) + 3))
            await interaction.response.send_message(
                "Customer name is too long for the title format `Month Day | Weekday | Customer Name`. "
                f"Use {allowed} characters or fewer.",
                ephemeral=True,
            )
            return

        self._cleanup_expired_pending_orders()
        key = self._pending_key(guild.id, member.id, interaction.channel_id)
        self._pending[key] = PendingOrderContext(
            guild_id=guild.id,
            forum_id=forum.id,
            author_id=member.id,
            customer_name=customer,
            created_at=datetime.now(),
        )

        await interaction.response.send_message(
            "Forum and customer saved. Send your next message in this channel within 10 minutes "
            "and I'll create the order post from it.",
            ephemeral=True,
        )

    async def complete_order(self, interaction: discord.Interaction[discord.Client]) -> None:
        if self._mark_interaction_in_flight(interaction.id) is False:
            LOG.warning("Ignoring duplicate /completed interaction %s", interaction.id)
            return

        channel = interaction.channel
        thread = channel if isinstance(channel, discord.Thread) else None
        if thread is None or not isinstance(thread.parent, discord.ForumChannel):
            await interaction.response.send_message(
                "Use `/completed` inside a forum post thread to close it.",
                ephemeral=True,
            )
            return

        if thread.archived:
            await interaction.response.send_message(
                "This forum post is already closed.",
                ephemeral=True,
            )
            return

        await interaction.response.defer(ephemeral=True, thinking=True)
        try:
            await thread.send(f"Order complete. Closed by {interaction.user.mention}.")
        except discord.HTTPException as ex:
            await interaction.edit_original_response(
                content=f"Failed to post completion message: {ex}"
            )
            return

        try:
            await thread.edit(archived=True)
        except discord.HTTPException as ex:
            await interaction.edit_original_response(
                content=f"Failed to close this forum post: {ex}"
            )
            return

        await interaction.edit_original_response(
            content="Marked as completed and closed this forum post."
        )

    async def handle_message(self, message: discord.Message) -> None:
        if message.guild is None or message.author.bot:
            return
        if not isinstance(message.author, discord.Member):
            return

        self._cleanup_expired_pending_orders()
        key = self._pending_key(message.guild.id, message.author.id, message.channel.id)
        pending = self._pending.get(key)
        if pending is None:
            return

        if self._is_expired(pending):
            self._pending.pop(key, None)
            await message.reply("Your pending `/order` request expired. Run `/order` again.")
            return

        forum = message.guild.get_channel(pending.forum_id)
        if not isinstance(forum, discord.ForumChannel):
            self._pending.pop(key, None)
            await message.reply("The selected forum no longer exists. Run `/order` again.")
            return
        if not self._is_orders_category_forum(forum):
            self._pending.pop(key, None)
            await message.reply(
                f"The selected forum is no longer under `{ORDERS_CATEGORY_NAME}`. Run `/order` again."
            )
            return
        if not self._can_member_create_forum_post(message.author, forum):
            self._pending.pop(key, None)
            await message.reply(
                f"You do not have permission to create posts in {forum.mention}."
            )
            return

        order_message = self._build_order_message(message)
        if not order_message:
            await message.reply(
                "Order message cannot be empty. Send text or attachment, or run `/order` again."
            )
            return

        post_body = f"Posted by {message.author.mention}\n{order_message}"
        if len(post_body) > DISCORD_MESSAGE_MAX_LENGTH:
            await message.reply(
                "That message is too long after `Posted by @user` (max 2000 chars). "
                "Send a shorter message."
            )
            return

        title_prefix = datetime.now().strftime("%B %-d | %A")
        post_title = f"{title_prefix} | {pending.customer_name}"
        if len(post_title) > ORDER_FORUM_TITLE_MAX_LENGTH:
            self._pending.pop(key, None)
            allowed = max(1, ORDER_FORUM_TITLE_MAX_LENGTH - (len(title_prefix) + 3))
            await message.reply(
                "Customer name is too long for today's title format. "
                f"Run `/order` again and use {allowed} characters or fewer."
            )
            return

        self._pending.pop(key, None)
        try:
            created = await forum.create_thread(name=post_title, content=post_body)
        except discord.HTTPException as ex:
            self._pending.setdefault(key, pending)
            await message.reply(f"Failed to create forum post: {ex}")
            return

        await message.reply(
            f"All set. Order created in {forum.mention}: {created.thread.mention}"
        )

    async def forum_autocomplete(
        self, interaction: discord.Interaction[discord.Client], current: str
    ) -> list[app_commands.Choice[str]]:
        guild = interaction.guild
        if guild is None:
            return []

        query = current.strip().lower()
        forums = sorted(
            (
                forum
                for forum in guild.forums
                if self._is_orders_category_forum(forum)
            ),
            key=lambda forum: forum.name.lower(),
        )

        choices: list[app_commands.Choice[str]] = []
        for forum in forums:
            if len(choices) >= DISCORD_AUTOCOMPLETE_MAX_CHOICES:
                break
            if query and query not in forum.name.lower():
                continue
            choices.append(app_commands.Choice(name=forum.name, value=str(forum.id)))
        return choices

    async def handle_thread_create(self, thread: discord.Thread) -> None:
        if not isinstance(thread.parent, discord.ForumChannel):
            return
        if not self._is_orders_category_forum(thread.parent):
            return
        bot_user_id = self._bot_user_id()
        if bot_user_id is not None and thread.owner_id == bot_user_id:
            return
        if thread.id in self._processed_thread_ids:
            return
        self._processed_thread_ids.add(thread.id)

    async def handle_auto_mention_thread_create(
        self, thread: discord.Thread, forum_role_targets: dict[int, list[int]]
    ) -> None:
        if not isinstance(thread.parent, discord.ForumChannel):
            return
        if not self._is_orders_category_forum(thread.parent):
            return
        role_ids = forum_role_targets.get(thread.parent.id, [])
        if not role_ids:
            return
        bot_user_id = self._bot_user_id()
        if bot_user_id is not None and thread.owner_id == bot_user_id:
            return
        if thread.id in self._processed_thread_ids:
            return
        self._processed_thread_ids.add(thread.id)

        try:
            history = [message async for message in thread.history(limit=HISTORY_LOOKBACK_COUNT)]
        except discord.HTTPException as ex:
            LOG.warning(
                "Could not inspect thread %s history before auto-mention: %s",
                thread.id,
                ex,
            )
            history = []

        if bot_user_id is not None:
            for existing in history:
                if existing.author.id == bot_user_id and FOLLOW_HINT in existing.content:
                    LOG.info(
                        "Skipped forum auto-mention in thread %s because hint already exists.",
                        thread.id,
                    )
                    return

        content = " ".join(f"<@&{role_id}>" for role_id in role_ids) + "\n" + FOLLOW_HINT
        try:
            await thread.send(content)
        except discord.HTTPException as ex:
            self._processed_thread_ids.discard(thread.id)
            LOG.warning("Failed to post forum auto-mention in thread %s: %s", thread.id, ex)

    @staticmethod
    def parse_forum_auto_mention_targets(raw: str) -> dict[int, list[int]]:
        if not raw.strip():
            return {}

        parsed: dict[int, list[int]] = {}
        for raw_entry in raw.split(";"):
            entry = raw_entry.strip()
            if not entry:
                continue
            parts = entry.split(":", 1)
            if len(parts) != 2:
                LOG.warning(
                    "Ignoring invalid FORUM_AUTO_MENTION_TARGETS entry %r. Expected forumId:roleId,roleId.",
                    entry,
                )
                continue
            forum_id_text = normalize_snowflake(parts[0])
            if not forum_id_text:
                LOG.warning("Ignoring forum target with invalid forum ID %r.", parts[0])
                continue
            role_ids: list[int] = []
            for raw_role_id in parts[1].split(","):
                role_id_text = normalize_snowflake(raw_role_id)
                if not role_id_text:
                    if raw_role_id.strip():
                        LOG.warning(
                            "Ignoring invalid role ID %r in forum target %s.",
                            raw_role_id,
                            forum_id_text,
                        )
                    continue
                role_id = int(role_id_text)
                if role_id not in role_ids:
                    role_ids.append(role_id)
            if not role_ids:
                LOG.warning(
                    "Ignoring forum target %s because it has no valid role IDs.",
                    forum_id_text,
                )
                continue
            parsed[int(forum_id_text)] = role_ids
        return parsed

    def _resolve_forum(
        self, guild: discord.Guild, raw_value: str
    ) -> discord.ForumChannel | None:
        channel_id = normalize_snowflake(raw_value)
        if not channel_id:
            return None
        channel = guild.get_channel(int(channel_id))
        return channel if isinstance(channel, discord.ForumChannel) else None

    def _build_order_message(self, message: discord.Message) -> str:
        content = message.content.strip()
        attachment_urls = "\n".join(attachment.url for attachment in message.attachments)
        if not attachment_urls:
            return content
        if not content:
            return "Attachments:\n" + attachment_urls
        return content + "\n\nAttachments:\n" + attachment_urls

    def _cleanup_expired_pending_orders(self) -> None:
        expired = [key for key, value in self._pending.items() if self._is_expired(value)]
        for key in expired:
            self._pending.pop(key, None)

    def _mark_interaction_in_flight(self, interaction_id: int) -> bool:
        cutoff = datetime.now() - timedelta(minutes=5)
        for key, created_at in list(self._completed_interactions.items()):
            if created_at < cutoff:
                self._completed_interactions.pop(key, None)
        if interaction_id in self._completed_interactions:
            return False
        self._completed_interactions[interaction_id] = datetime.now()
        return True

    @staticmethod
    def _pending_key(guild_id: int, author_id: int, channel_id: int) -> str:
        return f"{guild_id}:{author_id}:{channel_id}"

    @staticmethod
    def _can_member_create_forum_post(
        member: discord.Member, forum: discord.ForumChannel
    ) -> bool:
        permissions = forum.permissions_for(member)
        return permissions.view_channel and permissions.send_messages

    @staticmethod
    def _is_orders_category_forum(forum: discord.ForumChannel | None) -> bool:
        return bool(
            forum is not None
            and forum.category is not None
            and forum.category.name.lower() == ORDERS_CATEGORY_NAME.lower()
        )

    @staticmethod
    def _is_expired(context: PendingOrderContext) -> bool:
        return datetime.now() - context.created_at > ORDER_REQUEST_TTL
