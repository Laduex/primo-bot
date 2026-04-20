package dev.saseq.primobot.commands;

import dev.saseq.primobot.handlers.ForumAutoMentionHandler;
import dev.saseq.primobot.handlers.OrderCommandHandler;
import dev.saseq.primobot.handlers.VatCommandHandler;
import net.dv8tion.jda.api.events.channel.ChannelCreateEvent;
import net.dv8tion.jda.api.events.interaction.command.CommandAutoCompleteInteractionEvent;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import org.springframework.stereotype.Component;

@Component
public class PrimoSlashCommandListener extends ListenerAdapter {
    private final VatCommandHandler vatCommandHandler;
    private final OrderCommandHandler orderCommandHandler;
    private final ForumAutoMentionHandler forumAutoMentionHandler;

    public PrimoSlashCommandListener(VatCommandHandler vatCommandHandler,
                                     OrderCommandHandler orderCommandHandler,
                                     ForumAutoMentionHandler forumAutoMentionHandler) {
        this.vatCommandHandler = vatCommandHandler;
        this.orderCommandHandler = orderCommandHandler;
        this.forumAutoMentionHandler = forumAutoMentionHandler;
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
    public void onMessageReceived(MessageReceivedEvent event) {
        orderCommandHandler.handleMessage(event);
    }

    @Override
    public void onCommandAutoCompleteInteraction(CommandAutoCompleteInteractionEvent event) {
        if (!PrimoCommands.COMMAND_ORDER.equals(event.getName())) {
            return;
        }
        if (!PrimoCommands.ORDER_FORUM_OPTION.equals(event.getFocusedOption().getName())) {
            return;
        }
        orderCommandHandler.handleForumAutocomplete(event);
    }

    @Override
    public void onChannelCreate(ChannelCreateEvent event) {
        forumAutoMentionHandler.handleChannelCreate(event);
    }
}
