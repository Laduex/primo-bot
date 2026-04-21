package dev.saseq.primobot.handlers;

import net.dv8tion.jda.api.entities.channel.concrete.ForumChannel;
import net.dv8tion.jda.api.entities.channel.concrete.ThreadChannel;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import org.springframework.stereotype.Component;

@Component
public class CompletedCommandHandler {

    public void handle(SlashCommandInteractionEvent event) {
        if (!(event.getChannel() instanceof ThreadChannel threadChannel)) {
            event.reply("Use `/completed` inside a forum post thread to close it.")
                    .setEphemeral(true)
                    .queue();
            return;
        }

        if (!(threadChannel.getParentChannel() instanceof ForumChannel)) {
            event.reply("Use `/completed` inside a forum post thread to close it.")
                    .setEphemeral(true)
                    .queue();
            return;
        }

        if (threadChannel.isArchived()) {
            event.reply("This forum post is already closed.")
                    .setEphemeral(true)
                    .queue();
            return;
        }

        event.deferReply(true).queue();
        String completedMessage = "Order complete. Closed by " + event.getUser().getAsMention() + ".";

        threadChannel.sendMessage(completedMessage).queue(
                ignored -> archiveThread(event, threadChannel),
                failure -> event.getHook().editOriginal("Failed to post completion message: " + failure.getMessage()).queue()
        );
    }

    private void archiveThread(SlashCommandInteractionEvent event, ThreadChannel threadChannel) {
        threadChannel.getManager()
                .setArchived(true)
                .queue(
                        ignored -> event.getHook().editOriginal("Marked as completed and closed this forum post.").queue(),
                        failure -> event.getHook().editOriginal("Failed to close this forum post: " + failure.getMessage()).queue()
                );
    }
}
