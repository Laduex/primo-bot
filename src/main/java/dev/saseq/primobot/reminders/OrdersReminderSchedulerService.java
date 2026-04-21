package dev.saseq.primobot.reminders;

import dev.saseq.primobot.util.DiscordMessageUtils;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.entities.channel.concrete.ForumChannel;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.entities.channel.concrete.ThreadChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

@Component
public class OrdersReminderSchedulerService {
    private static final Logger LOG = LoggerFactory.getLogger(OrdersReminderSchedulerService.class);
    private static final int DISCORD_MESSAGE_MAX_LENGTH = 2000;

    private final JDA jda;
    private final OrdersReminderConfigStore configStore;
    private final OrdersReminderMessageBuilder messageBuilder;
    private final String defaultGuildId;

    public OrdersReminderSchedulerService(JDA jda,
                                          OrdersReminderConfigStore configStore,
                                          OrdersReminderMessageBuilder messageBuilder,
                                          @Value("${DISCORD_GUILD_ID:}") String defaultGuildId) {
        this.jda = jda;
        this.configStore = configStore;
        this.messageBuilder = messageBuilder;
        this.defaultGuildId = defaultGuildId == null ? "" : defaultGuildId.trim();
    }

    @Scheduled(fixedDelayString = "${ORDER_REMINDER_TICK_MS:60000}")
    public void runReminderTick() {
        OrdersReminderConfig config = configStore.getSnapshot();
        if (!config.isEnabled()) {
            return;
        }

        ZoneId zoneId = resolveZoneId(config.getTimezone());
        ZonedDateTime now = ZonedDateTime.now(zoneId);
        if (now.getHour() != config.getHour() || now.getMinute() != config.getMinute()) {
            return;
        }

        Guild guild = resolveGuild();
        if (guild == null) {
            LOG.warn("Orders reminder skipped: no guild available.");
            return;
        }

        boolean configUpdated = false;
        LocalDate today = now.toLocalDate();
        String todayString = today.toString();
        Map<String, String> lastRunByRoute = config.getLastRunDateByRoute();

        for (OrdersReminderRoute route : config.getRoutes()) {
            if (route == null) {
                continue;
            }

            String routeKey = route.getForumId();
            if (todayString.equals(lastRunByRoute.get(routeKey))) {
                continue;
            }

            ForumChannel forum = guild.getForumChannelById(route.getForumId());
            if (forum == null) {
                LOG.warn("Orders reminder route skipped: forum {} not found.", route.getForumId());
                continue;
            }

            TextChannel target = guild.getTextChannelById(route.getTargetTextChannelId());
            if (target == null) {
                LOG.warn("Orders reminder route skipped: target channel {} not found.", route.getTargetTextChannelId());
                continue;
            }

            Role role = guild.getRoleById(route.getMentionRoleId());
            if (role == null) {
                LOG.warn("Orders reminder route skipped: role {} not found.", route.getMentionRoleId());
                continue;
            }

            List<ThreadChannel> openThreads = new ArrayList<>(forum.getThreadChannels().stream()
                    .filter(thread -> !thread.isArchived())
                    .sorted(Comparator.comparing(ThreadChannel::getName, String.CASE_INSENSITIVE_ORDER))
                    .toList());

            if (openThreads.isEmpty()) {
                continue;
            }

            String greeting = messageBuilder.resolveGreeting(now.toLocalTime());
            String content = messageBuilder.buildReminderMessage(
                    route.getMentionRoleId(),
                    greeting,
                    forum.getName(),
                    guild.getId(),
                    openThreads,
                    config.getSignature(),
                    config.getMessageTone());

            try {
                for (String chunk : DiscordMessageUtils.chunkMessage(content, DISCORD_MESSAGE_MAX_LENGTH)) {
                    target.sendMessage(chunk).complete();
                }
                lastRunByRoute.put(routeKey, todayString);
                configUpdated = true;
                LOG.info("Posted orders reminder for forum {} into channel {}.", forum.getId(), target.getId());
            } catch (Exception ex) {
                LOG.warn("Failed sending orders reminder for forum {}: {}", forum.getId(), ex.getMessage());
            }
        }

        if (configUpdated) {
            configStore.replaceAndPersist(config);
        }
    }

    private Guild resolveGuild() {
        if (!defaultGuildId.isBlank()) {
            return jda.getGuildById(defaultGuildId);
        }
        return jda.getGuilds().isEmpty() ? null : jda.getGuilds().get(0);
    }

    private ZoneId resolveZoneId(String timezone) {
        try {
            return ZoneId.of(timezone);
        } catch (Exception ignored) {
            return ZoneId.of("Asia/Manila");
        }
    }

    public boolean canBotMentionRole(Member selfMember, TextChannel targetChannel, Role role) {
        if (selfMember == null || targetChannel == null || role == null) {
            return false;
        }
        return role.isMentionable() || selfMember.hasPermission(targetChannel, net.dv8tion.jda.api.Permission.MESSAGE_MENTION_EVERYONE);
    }
}
