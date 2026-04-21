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
}
