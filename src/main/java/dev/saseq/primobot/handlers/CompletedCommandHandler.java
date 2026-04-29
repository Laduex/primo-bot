package dev.saseq.primobot.handlers;

import net.dv8tion.jda.api.entities.channel.concrete.ForumChannel;
import net.dv8tion.jda.api.entities.channel.concrete.ThreadChannel;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class CompletedCommandHandler {
    private static final Logger LOG = LoggerFactory.getLogger(CompletedCommandHandler.class);
    private static final long INTERACTION_TTL_MS = 5 * 60 * 1000L;

    private final Map<String, Long> processedInteractions = new ConcurrentHashMap<>();

    public void handle(SlashCommandInteractionEvent event) {
        if (!markInteractionInFlight(event.getId())) {
            LOG.warn("Ignoring duplicate /completed interaction {}", event.getId());
            return;
        }

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

    private boolean markInteractionInFlight(String interactionId) {
        evictExpiredInteractions();
        return processedInteractions.putIfAbsent(interactionId, System.currentTimeMillis()) == null;
    }

    private void evictExpiredInteractions() {
        long cutoff = System.currentTimeMillis() - INTERACTION_TTL_MS;
        Iterator<Map.Entry<String, Long>> iterator = processedInteractions.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, Long> entry = iterator.next();
            if (entry.getValue() < cutoff) {
                iterator.remove();
            }
        }
    }
}
