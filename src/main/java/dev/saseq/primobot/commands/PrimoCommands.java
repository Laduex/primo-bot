package dev.saseq.primobot.commands;

import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.channel.ChannelType;
import net.dv8tion.jda.api.interactions.commands.DefaultMemberPermissions;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.CommandData;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import net.dv8tion.jda.api.interactions.commands.build.SubcommandData;

public final class PrimoCommands {
    public static final String COMMAND_VAT = "vat";
    public static final String COMMAND_ORDER = "order";
    public static final String COMMAND_ORDER_REMIND = "order-remind";
    public static final String COMMAND_COMPLETED = "completed";
    public static final String COMMAND_ORDERS_REMINDER = "orders-reminder";
    public static final String COMMAND_SALES_REPORT = "sales-report";
    public static final String COMMAND_SALES = "sales";

    public static final String VAT_AMOUNT_OPTION = "amount";
    public static final String VAT_BASIS_OPTION = "basis";

    public static final String ORDER_FORUM_OPTION = "forum";
    public static final String ORDER_CUSTOMER_OPTION = "customer";
    public static final String ORDER_REMIND_FORUM_OPTION = "forum";

    private PrimoCommands() {
    }

    public static CommandData buildVatSlashCommand() {
        return Commands.slash(COMMAND_VAT, "Calculate PH standard VAT (12%) totals")
                .addOptions(
                        new OptionData(OptionType.NUMBER, VAT_AMOUNT_OPTION, "Amount to calculate from (PHP)", true),
                        new OptionData(OptionType.STRING, VAT_BASIS_OPTION, "Select whether amount is VAT inclusive or VAT exclusive", true)
                                .addChoice("VAT Inclusive", "inclusive")
                                .addChoice("VAT Exclusive", "exclusive")
                );
    }

    public static CommandData buildOrderSlashCommand() {
        return Commands.slash(COMMAND_ORDER, "Create a forum order post")
                .addOptions(
                        new OptionData(OptionType.STRING, ORDER_FORUM_OPTION, "Order From?", true)
                                .setAutoComplete(true),
                        new OptionData(OptionType.STRING, ORDER_CUSTOMER_OPTION, "Customer name", true)
                );
    }

    public static CommandData buildCompletedSlashCommand() {
        return Commands.slash(COMMAND_COMPLETED, "Mark this forum post as completed and close it");
    }

    public static CommandData buildOrderRemindSlashCommand() {
        return Commands.slash(COMMAND_ORDER_REMIND, "Send an orders reminder now for a selected forum")
                .setDefaultPermissions(DefaultMemberPermissions.enabledFor(Permission.MANAGE_SERVER))
                .addOptions(
                        new OptionData(OptionType.CHANNEL, ORDER_REMIND_FORUM_OPTION, "Order forum channel", true)
                                .setChannelTypes(ChannelType.FORUM)
                );
    }

    public static CommandData buildOrdersReminderSlashCommand() {
        return Commands.slash(COMMAND_ORDERS_REMINDER, "Manage daily open-orders reminders")
                .setDefaultPermissions(DefaultMemberPermissions.enabledFor(Permission.MANAGE_SERVER))
                .addSubcommands(
                        new SubcommandData("status", "Show current reminder settings"),
                        new SubcommandData("set-enabled", "Enable or disable reminders")
                                .addOption(OptionType.BOOLEAN, "enabled", "True to enable reminders", true),
                        new SubcommandData("set-time", "Set send time and optional timezone")
                                .addOption(OptionType.INTEGER, "hour", "Hour (0-23)", true)
                                .addOption(OptionType.INTEGER, "minute", "Minute (0-59)", true)
                                .addOption(OptionType.STRING, "timezone", "IANA timezone (example: Asia/Manila)", false),
                        new SubcommandData("set-route", "Map one order forum to a target text channel and role")
                                .addOptions(
                                        new OptionData(OptionType.CHANNEL, "forum", "Order forum channel", true)
                                                .setChannelTypes(ChannelType.FORUM),
                                        new OptionData(OptionType.CHANNEL, "target", "Reminder text channel", true)
                                                .setChannelTypes(ChannelType.TEXT),
                                        new OptionData(OptionType.ROLE, "role", "Role to mention", true)
                                ),
                        new SubcommandData("remove-route", "Remove route by forum")
                                .addOptions(new OptionData(OptionType.CHANNEL, "forum", "Order forum channel", true)
                                        .setChannelTypes(ChannelType.FORUM)),
                        new SubcommandData("set-copy", "Set reminder tone and signature")
                                .addOptions(
                                        new OptionData(OptionType.STRING, "tone", "Reminder tone", false)
                                                .addChoice("casual", "casual")
                                                .addChoice("formal", "formal"),
                                        new OptionData(OptionType.STRING, "signature", "Message signature", false)
                                                .setMaxLength(80)
                                )
                );
    }

    public static CommandData buildSalesReportSlashCommand() {
        return Commands.slash(COMMAND_SALES_REPORT, "Manage multi-account UTAK and Loyverse sales reports")
                .setDefaultPermissions(DefaultMemberPermissions.enabledFor(Permission.MANAGE_SERVER))
                .addSubcommands(
                        new SubcommandData("status", "Show current sales report settings"),
                        new SubcommandData("set-enabled", "Enable or disable scheduled sales reports")
                                .addOption(OptionType.BOOLEAN, "enabled", "True to enable scheduled reports", true),
                        new SubcommandData("set-timezone", "Set schedule timezone")
                                .addOption(OptionType.STRING, "timezone", "IANA timezone (example: Asia/Manila)", true),
                        new SubcommandData("add-time", "Add one sales update schedule time")
                                .addOption(OptionType.INTEGER, "hour", "Hour (0-23)", true)
                                .addOption(OptionType.INTEGER, "minute", "Minute (0-59)", true),
                        new SubcommandData("remove-time", "Remove one sales update schedule time")
                                .addOption(OptionType.INTEGER, "hour", "Hour (0-23)", true)
                                .addOption(OptionType.INTEGER, "minute", "Minute (0-59)", true),
                        new SubcommandData("clear-times", "Clear all sales update schedule times")
                                .addOption(OptionType.BOOLEAN, "confirm", "Set true to confirm", true),
                        new SubcommandData("set-summary", "Set daily overview channel and schedule time")
                                .addOptions(
                                        new OptionData(OptionType.CHANNEL, "target", "Target channel (text or forum)", true)
                                                .setChannelTypes(ChannelType.TEXT, ChannelType.FORUM),
                                        new OptionData(OptionType.INTEGER, "hour", "Hour (0-23)", true),
                                        new OptionData(OptionType.INTEGER, "minute", "Minute (0-59)", true),
                                        new OptionData(OptionType.STRING, "timezone", "Optional IANA timezone override", false)
                                ),
                        new SubcommandData("set-channel", "Set default target channel for sales updates")
                                .addOptions(new OptionData(OptionType.CHANNEL, "target", "Target channel (text or forum)", true)
                                        .setChannelTypes(ChannelType.TEXT, ChannelType.FORUM)),
                        new SubcommandData("run-now", "Send a sales report immediately")
                                .addOptions(
                                        new OptionData(OptionType.CHANNEL, "target", "Optional one-time target override (text or forum)", false)
                                                .setChannelTypes(ChannelType.TEXT, ChannelType.FORUM),
                                        new OptionData(OptionType.STRING, "scope", "Run all accounts or a single account", false)
                                                .addChoice("All Accounts", "all")
                                                .addChoice("Single Account", "single"),
                                        new OptionData(OptionType.STRING, "account", "Select account when scope is Single Account", false)
                                                .setAutoComplete(true)
                                ),
                        new SubcommandData("list-accounts", "List configured sales accounts"),
                        new SubcommandData("add-account", "Add a sales account")
                                .addOptions(
                                        new OptionData(OptionType.STRING, "platform", "UTAK or LOYVERSE", true)
                                                .addChoice("UTAK", "UTAK")
                                                .addChoice("LOYVERSE", "LOYVERSE"),
                                        new OptionData(OptionType.STRING, "name", "Display name", true),
                                        new OptionData(OptionType.STRING, "account-id", "Optional account id; autogenerated if empty", false),
                                        new OptionData(OptionType.STRING, "username", "UTAK username", false),
                                        new OptionData(OptionType.STRING, "password", "UTAK password", false),
                                        new OptionData(OptionType.STRING, "token", "Loyverse API token", false),
                                        new OptionData(OptionType.STRING, "base-url", "Base URL or API endpoint", false),
                                        new OptionData(OptionType.STRING, "sales-url", "UTAK sales/report URL", false)
                                ),
                        new SubcommandData("update-account", "Update a sales account")
                                .addOption(OptionType.STRING, "account-id", "Existing account id", true)
                                .addOption(OptionType.STRING, "name", "Display name", false)
                                .addOption(OptionType.BOOLEAN, "enabled", "Enable/disable this account", false)
                                .addOption(OptionType.STRING, "username", "UTAK username", false)
                                .addOption(OptionType.STRING, "password", "UTAK password", false)
                                .addOption(OptionType.STRING, "token", "Loyverse API token", false)
                                .addOption(OptionType.STRING, "base-url", "Base URL or API endpoint", false)
                                .addOption(OptionType.STRING, "sales-url", "UTAK sales/report URL", false),
                        new SubcommandData("remove-account", "Remove a sales account")
                                .addOption(OptionType.STRING, "account-id", "Account id to remove", true),
                        new SubcommandData("set-account-enabled", "Enable or disable one account")
                                .addOption(OptionType.STRING, "account-id", "Account id", true)
                                .addOption(OptionType.BOOLEAN, "enabled", "True to enable", true),
                        new SubcommandData("set-copy", "Set sales report message tone/signature")
                                .addOptions(
                                        new OptionData(OptionType.STRING, "tone", "casual or formal", false)
                                                .addChoice("casual", "casual")
                                                .addChoice("formal", "formal"),
                                        new OptionData(OptionType.STRING, "signature", "Message signature", false)
                                                .setMaxLength(80)
                                )
                );
    }

    public static CommandData buildSalesSlashCommand() {
        return Commands.slash(COMMAND_SALES, "Send sales reports now")
                // Keep /sales visible in DMs; permission checks are enforced at runtime.
                .addSubcommands(
                        new SubcommandData("run-now", "Send a sales report immediately")
                                .addOptions(
                                        new OptionData(OptionType.STRING, "scope", "Run all accounts or a single account", false)
                                                .addChoice("All Accounts", "all")
                                                .addChoice("Single Account", "single"),
                                        new OptionData(OptionType.STRING, "account", "Select account when scope is Single Account", false)
                                                .setAutoComplete(true)
                                )
                );
    }

}
