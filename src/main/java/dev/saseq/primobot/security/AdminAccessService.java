package dev.saseq.primobot.security;

import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

@Component
public class AdminAccessService {
    private final Set<String> configuredAdminUserIds;

    public AdminAccessService(@Value("${PRIMO_ADMIN_USER_IDS:}") String rawAdminIds) {
        configuredAdminUserIds = Arrays.stream(rawAdminIds.split(","))
                .map(String::trim)
                .filter(id -> !id.isBlank())
                .collect(Collectors.toUnmodifiableSet());
    }

    public boolean hasAccess(SlashCommandInteractionEvent event) {
        if (event == null || event.getUser() == null) {
            return false;
        }

        String userId = event.getUser().getId();
        if (configuredAdminUserIds.contains(userId)) {
            return true;
        }

        if (!event.isFromGuild()) {
            return false;
        }

        var member = event.getMember();
        return member != null && member.hasPermission(Permission.ADMINISTRATOR);
    }

    public String noAccessMessage() {
        return "This command is admin-only. In servers, you need Administrator permission. " +
                "For DM use, add your Discord user ID to `PRIMO_ADMIN_USER_IDS`.";
    }
}
