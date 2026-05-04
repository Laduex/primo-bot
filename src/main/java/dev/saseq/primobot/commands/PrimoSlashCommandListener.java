package dev.saseq.primobot.commands;

import dev.saseq.primobot.handlers.CompletedCommandHandler;
import dev.saseq.primobot.handlers.ForumAutoMentionHandler;
import dev.saseq.primobot.handlers.OrderCommandHandler;
import dev.saseq.primobot.handlers.OrdersReminderCommandHandler;
import dev.saseq.primobot.handlers.SalesReportCommandHandler;
import dev.saseq.primobot.handlers.VatCommandHandler;
import dev.saseq.primobot.util.CrossProcessClaimStore;
import net.dv8tion.jda.api.events.channel.ChannelCreateEvent;
import net.dv8tion.jda.api.events.interaction.command.CommandAutoCompleteInteractionEvent;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
public class PrimoSlashCommandListener extends ListenerAdapter {
    private static final Duration SLASH_INTERACTION_TTL = Duration.ofMinutes(15);
    private static final Duration AUTOCOMPLETE_TTL = Duration.ofMinutes(2);

    private final VatCommandHandler vatCommandHandler;
    private final OrderCommandHandler orderCommandHandler;
    private final CompletedCommandHandler completedCommandHandler;
    private final OrdersReminderCommandHandler ordersReminderCommandHandler;
    private final SalesReportCommandHandler salesReportCommandHandler;
    private final ForumAutoMentionHandler forumAutoMentionHandler;
    private final CrossProcessClaimStore claimStore;

    public PrimoSlashCommandListener(VatCommandHandler vatCommandHandler,
                                     OrderCommandHandler orderCommandHandler,
                                     CompletedCommandHandler completedCommandHandler,
                                     OrdersReminderCommandHandler ordersReminderCommandHandler,
                                     SalesReportCommandHandler salesReportCommandHandler,
                                     ForumAutoMentionHandler forumAutoMentionHandler,
                                     CrossProcessClaimStore claimStore) {
        this.vatCommandHandler = vatCommandHandler;
        this.orderCommandHandler = orderCommandHandler;
        this.completedCommandHandler = completedCommandHandler;
        this.ordersReminderCommandHandler = ordersReminderCommandHandler;
        this.salesReportCommandHandler = salesReportCommandHandler;
        this.forumAutoMentionHandler = forumAutoMentionHandler;
        this.claimStore = claimStore;
    }

    @Override
    public void onSlashCommandInteraction(SlashCommandInteractionEvent event) {
        if (!claimStore.tryClaim("slash-interaction", event.getId(), SLASH_INTERACTION_TTL)) {
            return;
        }
        if (PrimoCommands.COMMAND_VAT.equals(event.getName())) {
            vatCommandHandler.handle(event);
            return;
        }
        if (PrimoCommands.COMMAND_ORDER.equals(event.getName())) {
            orderCommandHandler.handle(event);
            return;
        }
        if (PrimoCommands.COMMAND_ORDER_REMIND.equals(event.getName())) {
            ordersReminderCommandHandler.handleManualReminder(event);
            return;
        }
        if (PrimoCommands.COMMAND_COMPLETED.equals(event.getName())) {
            completedCommandHandler.handle(event);
            return;
        }
        if (PrimoCommands.COMMAND_ORDERS_REMINDER.equals(event.getName())) {
            ordersReminderCommandHandler.handle(event);
            return;
        }
        if (PrimoCommands.COMMAND_SALES_REPORT.equals(event.getName())) {
            salesReportCommandHandler.handle(event);
            return;
        }
        if (PrimoCommands.COMMAND_SALES.equals(event.getName())) {
            salesReportCommandHandler.handle(event);
            return;
        }
    }

    @Override
    public void onMessageReceived(MessageReceivedEvent event) {
        if (salesReportCommandHandler.handleDirectRunNowMessage(event)) {
            return;
        }
        orderCommandHandler.handleMessage(event);
    }

    @Override
    public void onCommandAutoCompleteInteraction(CommandAutoCompleteInteractionEvent event) {
        if (!claimStore.tryClaim("autocomplete-interaction", event.getId(), AUTOCOMPLETE_TTL)) {
            return;
        }
        if (PrimoCommands.COMMAND_ORDER.equals(event.getName())
                && PrimoCommands.ORDER_FORUM_OPTION.equals(event.getFocusedOption().getName())) {
            orderCommandHandler.handleForumAutocomplete(event);
            return;
        }

        boolean salesCommand = PrimoCommands.COMMAND_SALES_REPORT.equals(event.getName())
                || PrimoCommands.COMMAND_SALES.equals(event.getName());
        if (salesCommand
                && "run-now".equals(event.getSubcommandName())
                && "account".equals(event.getFocusedOption().getName())) {
            salesReportCommandHandler.handleRunNowAccountAutocomplete(event);
        }
    }

    @Override
    public void onChannelCreate(ChannelCreateEvent event) {
        forumAutoMentionHandler.handleChannelCreate(event);
    }
}
