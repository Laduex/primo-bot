package dev.saseq.primobot.commands;

import dev.saseq.primobot.handlers.OrderCommandHandler;
import dev.saseq.primobot.handlers.VatCommandHandler;
import net.dv8tion.jda.api.events.interaction.command.CommandAutoCompleteInteractionEvent;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import org.springframework.stereotype.Component;

@Component
public class PrimoSlashCommandListener extends ListenerAdapter {
    private final VatCommandHandler vatCommandHandler;
    private final OrderCommandHandler orderCommandHandler;

    public PrimoSlashCommandListener(VatCommandHandler vatCommandHandler,
                                     OrderCommandHandler orderCommandHandler) {
        this.vatCommandHandler = vatCommandHandler;
        this.orderCommandHandler = orderCommandHandler;
    }

    @Override
    public void onSlashCommandInteraction(SlashCommandInteractionEvent event) {
        if (PrimoCommands.COMMAND_VAT.equals(event.getName())) {
            vatCommandHandler.handle(event);
            return;
        }
        if (PrimoCommands.COMMAND_ORDER.equals(event.getName())) {
            orderCommandHandler.handle(event);
        }
    }

    @Override
    public void onCommandAutoCompleteInteraction(CommandAutoCompleteInteractionEvent event) {
        if (!PrimoCommands.COMMAND_ORDER.equals(event.getName())) {
            return;
        }
        if (!PrimoCommands.ORDER_TAGS_OPTION.equals(event.getFocusedOption().getName())) {
            return;
        }
        orderCommandHandler.handleAutocomplete(event);
    }
}
