package dev.saseq.primobot.reminders;

import net.dv8tion.jda.api.entities.channel.concrete.ThreadChannel;
import org.springframework.stereotype.Component;

import java.time.LocalTime;
import java.util.List;

@Component
public class OrdersReminderMessageBuilder {

    public String resolveGreeting(LocalTime localTime) {
        int hour = localTime.getHour();
        if (hour >= 5 && hour <= 11) {
            return "Good Morning";
        }
        if (hour >= 12 && hour <= 17) {
            return "Good Afternoon";
        }
        return "Good Evening";
    }

    public String buildReminderMessage(String mentionRoleId,
                                       String greeting,
                                       String forumName,
                                       String guildId,
                                       List<ThreadChannel> openThreads,
                                       String signature,
                                       String tone) {
        StringBuilder content = new StringBuilder();
        content.append("<@&")
                .append(mentionRoleId)
                .append("> ")
                .append(greeting)
                .append(", team! Here are the orders still open in ")
                .append(forumName)
                .append(":\n\n");

        for (ThreadChannel thread : openThreads) {
            content.append("- [")
                    .append(escapeBrackets(thread.getName()))
                    .append("](https://discord.com/channels/")
                    .append(guildId)
                    .append("/")
                    .append(thread.getId())
                    .append(")\n");
        }

        if ("casual".equalsIgnoreCase(tone)) {
            content.append("\nIf any of these are already done, please run `/completed` inside the order post so I can keep this list updated.");
        } else {
            content.append("\nPlease run `/completed` inside each finished order post so this reminder stays accurate.");
        }

        String trimmedSignature = signature == null ? "" : signature.trim();
        if (!trimmedSignature.isEmpty()) {
            content.append("\n\n").append(trimmedSignature);
        }

        return content.toString();
    }

    private String escapeBrackets(String value) {
        if (value == null || value.isBlank()) {
            return "Untitled Order";
        }
        return value.replace("[", "\\[").replace("]", "\\]");
    }
}
