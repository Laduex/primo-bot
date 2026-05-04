from __future__ import annotations

import asyncio
from decimal import Decimal
from datetime import timedelta
import logging

import discord
from discord import app_commands
from discord.ext import commands

from .claims import CrossProcessClaimStore
from .meta_unread import (
    MetaGraphApiClient,
    MetaUnreadCollectorService,
    MetaUnreadConfigStore,
    MetaUnreadExecutorService,
    MetaUnreadMessageBuilder,
    MetaUnreadSchedulerService,
)
from .orders import OrderHandler
from .reminders import OrdersReminderConfigStore, OrdersReminderService, OrdersReminderMessageBuilder
from .sales import (
    DispatchResult,
    LoyverseApiSalesProvider,
    SalesCommandService,
    SalesReportConfigStore,
    SalesReportExecutorService,
    SalesReportMessageBuilder,
    SalesReportSchedulerService,
    SalesAggregatorService,
    UtakBrowserSalesProvider,
)
from .settings import Settings

LOG = logging.getLogger(__name__)
INTERACTION_CLAIM_NAMESPACE = "slash-interaction"
INTERACTION_CLAIM_TTL = timedelta(minutes=15)


class PrimoBot(commands.Bot):
    def __init__(self, settings: Settings) -> None:
        intents = discord.Intents.default()
        intents.guilds = True
        intents.messages = True
        intents.message_content = True
        intents.members = True
        super().__init__(command_prefix="!", intents=intents)
        self.settings = settings
        self.claim_store = CrossProcessClaimStore()
        self.order_handler = OrderHandler(
            lambda: self.user.id if self.user else None,
            self.claim_store,
        )
        self.orders_reminder_store = OrdersReminderConfigStore(
            settings.order_reminder_config_path,
            settings.order_reminder_default_enabled,
            settings.order_reminder_default_time,
            settings.order_reminder_default_timezone,
            settings.order_reminder_default_routes,
        )
        self.orders_reminder_service = OrdersReminderService(
            self,
            self.orders_reminder_store,
            OrdersReminderMessageBuilder(),
            settings.discord_guild_id,
            self.claim_store,
        )
        self.sales_report_store = SalesReportConfigStore(
            settings.sales_report_config_path,
            settings.sales_report_default_enabled,
            settings.sales_report_default_timezone,
            settings.sales_report_default_times,
            settings.sales_report_default_target_channel_id,
            settings.sales_report_default_overview_time,
            settings.sales_report_default_overview_target_channel_id,
            settings.sales_report_default_tone,
            settings.sales_report_default_signature,
        )
        sales_executor = SalesReportExecutorService(
            SalesAggregatorService([UtakBrowserSalesProvider(), LoyverseApiSalesProvider()]),
            SalesReportMessageBuilder(),
            self.claim_store,
        )
        self.sales_scheduler_service = SalesReportSchedulerService(
            self,
            self.sales_report_store,
            sales_executor,
            settings.discord_guild_id,
            self.claim_store,
        )
        self.sales_command_service = SalesCommandService(
            self,
            self.sales_report_store,
            sales_executor,
            self.sales_scheduler_service,
            settings.discord_guild_id,
            self.claim_store,
        )
        self.meta_unread_store = MetaUnreadConfigStore(
            settings.meta_unread_config_path,
            settings.meta_unread_default_enabled,
            settings.meta_unread_default_interval_minutes,
            settings.meta_unread_default_target_channel_id,
        )
        meta_unread_executor = MetaUnreadExecutorService(
            MetaUnreadCollectorService(
                MetaGraphApiClient(
                    api_base_url=settings.meta_api_base_url,
                    graph_version=settings.meta_graph_version,
                    user_access_token=settings.meta_access_token,
                    app_secret=settings.meta_app_secret,
                )
            ),
            MetaUnreadMessageBuilder(),
        )
        self.meta_unread_scheduler_service = MetaUnreadSchedulerService(
            self,
            self.meta_unread_store,
            meta_unread_executor,
            settings.discord_guild_id,
            self.claim_store,
        )
        self.forum_auto_mention_targets = self.order_handler.parse_forum_auto_mention_targets(
            settings.forum_auto_mention_targets
        )
        self._background_tasks: list[asyncio.Task[None]] = []
        self._register_commands()

    async def setup_hook(self) -> None:
        await self.orders_reminder_store.initialize()
        await self.sales_report_store.initialize()
        await self.meta_unread_store.initialize()
        if self.settings.discord_guild_id:
            guild = discord.Object(id=int(self.settings.discord_guild_id))
            self.tree.copy_global_to(guild=guild)
            await self.tree.sync(guild=guild)

            # Keep only DM-safe slash commands global. Syncing global scope after
            # trimming the local tree removes stale dashboard-era commands too.
            for command_name in ("order", "completed", "order-remind"):
                self.tree.remove_command(command_name)
            await self.tree.sync()
        else:
            await self.tree.sync()
        self._background_tasks.append(
            asyncio.create_task(
                self._scheduler_loop(
                    self.orders_reminder_service.run_tick,
                    self.settings.order_reminder_tick_ms,
                    "orders-reminder",
                )
            )
        )
        self._background_tasks.append(
            asyncio.create_task(
                self._scheduler_loop(
                    self.meta_unread_scheduler_service.run_meta_unread_tick,
                    self.settings.meta_unread_tick_ms,
                    "meta-unread",
                )
            )
        )
        self._background_tasks.append(
            asyncio.create_task(
                self._scheduler_loop(
                    self.sales_scheduler_service.run_sales_tick,
                    self.settings.sales_report_tick_ms,
                    "sales-report",
                )
            )
        )

    async def close(self) -> None:
        for task in self._background_tasks:
            task.cancel()
        self._background_tasks.clear()
        await super().close()

    async def on_ready(self) -> None:
        LOG.info("Discord bot ready as %s", self.user)

    async def on_message(self, message: discord.Message) -> None:
        if message.author.bot:
            return
        if await self.sales_command_service.handle_direct_run_now_message(message):
            return
        await self.order_handler.handle_message(message)

    async def on_thread_create(self, thread: discord.Thread) -> None:
        if self.forum_auto_mention_targets:
            await self.order_handler.handle_auto_mention_thread_create(
                thread, self.forum_auto_mention_targets
            )

    def set_forum_auto_mention_targets(self, mapping: dict[str, list[str]]) -> None:
        self.forum_auto_mention_targets = {
            int(forum_id): [int(role_id) for role_id in role_ids]
            for forum_id, role_ids in mapping.items()
            if forum_id.isdigit()
        }

    async def _scheduler_loop(
        self, callback: callable, interval_ms: int, name: str
    ) -> None:
        try:
            while not self.is_closed():
                try:
                    await callback()
                except asyncio.CancelledError:
                    raise
                except Exception:
                    LOG.exception("Scheduler %s failed", name)
                await asyncio.sleep(max(interval_ms, 1000) / 1000)
        except asyncio.CancelledError:
            return

    def _register_commands(self) -> None:
        @self.tree.command(name="vat", description="Calculate PH standard VAT (12%) totals")
        @app_commands.describe(
            amount="Amount to calculate from (PHP)",
            basis="Select whether amount is VAT inclusive or VAT exclusive",
        )
        @app_commands.choices(
            basis=[
                app_commands.Choice(name="VAT Inclusive", value="inclusive"),
                app_commands.Choice(name="VAT Exclusive", value="exclusive"),
            ]
        )
        async def vat(interaction: discord.Interaction[discord.Client], amount: float, basis: str) -> None:
            if interaction.guild is None and not await self._is_shared_guild_member(interaction.user):
                await interaction.response.send_message(
                    "You need to be a member of a server that shares this bot to use `/vat` in DMs.",
                    ephemeral=True,
                )
                return
            from .vat import VatBasis, VatCalculator

            try:
                vat_basis = VatBasis.INCLUSIVE if basis == "inclusive" else VatBasis.EXCLUSIVE
                result = VatCalculator().calculate(
                    amount=Decimal(str(amount)),
                    vat_rate_percent=Decimal("12"),
                    basis=vat_basis,
                )
            except ValueError as ex:
                await interaction.response.send_message(str(ex), ephemeral=True)
                return

            response = (
                "**Primo VAT Calculator**\n"
                f"Basis: {result.basis_label}\n"
                "Rate: 12.00% (fixed)\n"
                "Scope: PH standard VATable transactions only (excludes VAT-exempt and zero-rated sales)\n"
                f"Net (VAT Exclusive): PHP {result.net_amount:,.2f}\n"
                f"VAT: PHP {result.vat_amount:,.2f}\n"
                f"Gross (VAT Inclusive): PHP {result.gross_amount:,.2f}\n"
            )
            await interaction.response.send_message(response)

        @self.tree.command(name="order", description="Create a forum order post")
        @app_commands.describe(
            forum="Order From?",
            customer="Customer name",
        )
        @app_commands.autocomplete(forum=self._forum_autocomplete)
        async def order(
            interaction: discord.Interaction[discord.Client], forum: str, customer: str
        ) -> None:
            if not self._claim_interaction_once(interaction):
                return
            await self.order_handler.create_order(interaction, forum, customer)

        @self.tree.command(
            name="completed", description="Mark this forum post as completed and close it"
        )
        async def completed(interaction: discord.Interaction[discord.Client]) -> None:
            if not self._claim_interaction_once(interaction):
                return
            await self.order_handler.complete_order(interaction)

        @self.tree.command(
            name="order-remind",
            description="Send an orders reminder now for a selected forum",
        )
        @app_commands.default_permissions(manage_guild=True)
        async def order_remind(
            interaction: discord.Interaction[discord.Client], forum: discord.ForumChannel
        ) -> None:
            if not self._claim_interaction_once(interaction):
                return
            guild = interaction.guild
            member = interaction.user if isinstance(interaction.user, discord.Member) else None
            if guild is None or member is None:
                await interaction.response.send_message(
                    "This command can only be used inside a Discord server.",
                    ephemeral=True,
                )
                return
            if not member.guild_permissions.manage_guild:
                await interaction.response.send_message(
                    "You need Manage Server permission to use `/order-remind`.",
                    ephemeral=True,
                )
                return
            if not self.orders_reminder_service.is_orders_category_forum(forum):
                await interaction.response.send_message(
                    "Forum must be under the `Orders` category.",
                    ephemeral=True,
                )
                return
            config = await self.orders_reminder_store.get_snapshot()
            route = next((item for item in config.routes if item.forumId == str(forum.id)), None)
            if route is None:
                await interaction.response.send_message(
                    f"No route is configured for `{forum.name}`. Configure it in the Primo dashboard first.",
                    ephemeral=True,
                )
                return
            status, forum_name, error = await self.orders_reminder_service.dispatch_manual_reminder(
                guild, route
            )
            messages = {
                "SENT": f"Manual reminder sent for `{forum_name}` to <#{route.targetTextChannelId}>.",
                "NO_OPEN_ORDERS": f"No open orders found in `{forum_name}`, so nothing was sent.",
                "FORUM_NOT_FOUND": f"Could not find forum `<#{route.forumId}>`. Update the route in the Primo dashboard.",
                "TARGET_NOT_FOUND": f"Could not find target channel `<#{route.targetTextChannelId}>`. Update the route in the Primo dashboard.",
                "ROLE_NOT_FOUND": f"Could not find role `<@&{route.mentionRoleId}>`. Update the route in the Primo dashboard.",
                "ROLE_CANNOT_BE_MENTIONED": "The bot cannot mention that role in the target channel. Make the role mentionable or grant Mention Everyone permission.",
                "SEND_FAILED": "Failed to send reminder: " + (error or "Unknown error"),
            }
            await interaction.response.send_message(messages[status], ephemeral=True)

        sales = app_commands.Group(name="sales", description="Send sales reports now")

        @sales.command(name="run-now", description="Send a sales report immediately")
        @app_commands.choices(
            scope=[
                app_commands.Choice(name="All Accounts", value="all"),
                app_commands.Choice(name="Single Account", value="single"),
            ]
        )
        @app_commands.autocomplete(account=self._sales_account_autocomplete)
        async def sales_run_now(
            interaction: discord.Interaction[discord.Client],
            scope: str | None = None,
            account: str | None = None,
        ) -> None:
            if not self._claim_interaction_once(interaction):
                return
            if interaction.guild is None:
                if not await self.sales_command_service._has_manage_server_for_direct_run_now_user(
                    interaction.user
                ):
                    await interaction.response.send_message(
                        "You need Manage Server permission to run `sales run now`.",
                        ephemeral=True,
                    )
                    return
            elif not await self._require_manage_server(interaction, "sales commands"):
                return
            await self._handle_sales_run_now(interaction, None, scope, account, False)

        self.tree.add_command(sales)

    async def _handle_sales_run_now(
        self,
        interaction: discord.Interaction[discord.Client],
        target: discord.abc.GuildChannel | None,
        scope: str | None,
        account: str | None,
        allow_target: bool,
    ) -> None:
        override_target_id = ""
        if allow_target and target is not None:
            if not isinstance(target, (discord.TextChannel, discord.ForumChannel)):
                await interaction.response.send_message(
                    "`target` must be a text or forum channel.",
                    ephemeral=True,
                )
                return
            override_target_id = str(target.id)

        normalized_scope = "all" if scope is None else scope.strip().lower()
        if normalized_scope not in {"all", "single"}:
            await interaction.response.send_message(
                "Invalid `scope`. Use `All Accounts` or `Single Account`.",
                ephemeral=True,
            )
            return

        provided_account_id = (account or "").strip()
        if not scope and provided_account_id:
            normalized_scope = "single"
        if normalized_scope == "all" and provided_account_id:
            await interaction.response.send_message(
                "`account` is only used when `scope` is `Single Account`. Set `scope` to `Single Account` or clear `account`.",
                ephemeral=True,
            )
            return
        selected_account_id = ""
        if normalized_scope == "single":
            selected_account_id = provided_account_id
            if not selected_account_id:
                await interaction.response.send_message(
                    "`account` is required when `scope` is Single Account. Pick an account name from the list.",
                    ephemeral=True,
                )
                return

        await interaction.response.defer(ephemeral=True, thinking=True)
        result = await self.sales_command_service.run_now(
            interaction.guild,
            interaction.channel if interaction.guild is None else None,
            override_target_id,
            selected_account_id,
            False,
        )
        await interaction.edit_original_response(
            content=self._sales_run_now_result_message(interaction.guild is None, result)
        )

    def _sales_run_now_result_message(self, direct: bool, result: DispatchResult) -> str:
        if result.status == "SENT":
            scope_label = (
                "all enabled accounts"
                if not result.account_id
                else f"account `{result.account_id}`"
            )
            if direct:
                return (
                    f"Sales report sent here for {scope_label}. Success: `{result.success_count}`, "
                    f"Failed: `{result.failure_count}`."
                )
            return (
                f"Sales report sent to <#{result.target_channel_id}> for {scope_label}. "
                f"Success: `{result.success_count}`, Failed: `{result.failure_count}`."
            )
        if result.status == "TARGET_NOT_CONFIGURED":
            return "No sales target channel is configured. Set it in the Primo dashboard."
        if result.status == "TARGET_NOT_FOUND":
            return f"Target channel `<#{result.target_channel_id}>` was not found."
        if result.status == "ACCOUNT_NOT_FOUND":
            if not result.account_id:
                return "No matching sales account was found. Check the Primo dashboard account list."
            return f"No account found for id `{result.account_id}`. Check the Primo dashboard account list."
        return "Failed to send sales report: " + (result.message or "Unknown error")

    def _claim_interaction_once(self, interaction: discord.Interaction[discord.Client]) -> bool:
        claimed = self.claim_store.try_claim(
            INTERACTION_CLAIM_NAMESPACE,
            str(interaction.id),
            INTERACTION_CLAIM_TTL,
        )
        if not claimed:
            LOG.warning("Ignoring duplicate slash interaction %s", interaction.id)
        return claimed

    async def _require_manage_server(
        self, interaction: discord.Interaction[discord.Client], label: str
    ) -> bool:
        member = interaction.user if isinstance(interaction.user, discord.Member) else None
        if interaction.guild is None or member is None:
            await interaction.response.send_message(
                "This command can only be used inside a Discord server.",
                ephemeral=True,
            )
            return False
        if not member.guild_permissions.manage_guild:
            await interaction.response.send_message(
                f"You need Manage Server permission to use `{label}`.",
                ephemeral=True,
            )
            return False
        return True

    async def _is_shared_guild_member(self, user: discord.abc.User) -> bool:
        if self.settings.discord_guild_id:
            guild = self.get_guild(int(self.settings.discord_guild_id))
            if guild is not None:
                member = guild.get_member(user.id)
                if member is None:
                    try:
                        member = await guild.fetch_member(user.id)
                    except discord.HTTPException:
                        member = None
                if member is not None:
                    return True
        for guild in self.guilds:
            member = guild.get_member(user.id)
            if member is None:
                try:
                    member = await guild.fetch_member(user.id)
                except discord.HTTPException:
                    continue
            if member is not None:
                return True
        return False

    async def _forum_autocomplete(
        self, interaction: discord.Interaction[discord.Client], current: str
    ) -> list[app_commands.Choice[str]]:
        return await self.order_handler.forum_autocomplete(interaction, current)

    async def _sales_account_autocomplete(
        self, interaction: discord.Interaction[discord.Client], current: str
    ) -> list[app_commands.Choice[str]]:
        return await self.sales_command_service.build_run_now_account_choices(
            interaction, current
        )
