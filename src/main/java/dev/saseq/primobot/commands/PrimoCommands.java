package dev.saseq.primobot.commands;

import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.CommandData;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;

public final class PrimoCommands {
    public static final String COMMAND_VAT = "vat";
    public static final String COMMAND_ORDER = "order";
    public static final String COMMAND_COMPLETED = "completed";

    public static final String VAT_AMOUNT_OPTION = "amount";
    public static final String VAT_BASIS_OPTION = "basis";

    public static final String ORDER_FORUM_OPTION = "forum";
    public static final String ORDER_CUSTOMER_OPTION = "customer";

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
}
