package dev.saseq.primobot.handlers;

import dev.saseq.primobot.commands.PrimoCommands;
import dev.saseq.primobot.reminders.OrdersReminderConfig;
import dev.saseq.primobot.reminders.OrdersReminderConfigStore;
import dev.saseq.primobot.reminders.OrdersReminderMessageBuilder;
import dev.saseq.primobot.reminders.OrdersReminderRoute;
import dev.saseq.primobot.util.DiscordMessageUtils;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.entities.channel.ChannelType;
import net.dv8tion.jda.api.entities.channel.concrete.ForumChannel;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.entities.channel.concrete.ThreadChannel;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import org.springframework.stereotype.Component;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

@Component
public class OrdersReminderCommandHandler {
    private static final String ORDERS_CATEGORY_NAME = "Orders";
    private static final int DISCORD_MESSAGE_MAX_LENGTH = 2000;
    private static final String FALLBACK_TIMEZONE = "Asia/Manila";

    private final OrdersReminderConfigStore configStore;
    private final OrdersReminderMessageBuilder messageBuilder;

    public OrdersReminderCommandHandler(OrdersReminderConfigStore configStore,
                                        OrdersReminderMessageBuilder messageBuilder) {
        this.configStore = configStore;
        this.messageBuilder = messageBuilder;
    }

    public void handle(SlashCommandInteractionEvent event) {
        if (event.getGuild() == null || event.getMember() == null) {
            event.reply("This command can only be used inside a Discord server.")
                    .setEphemeral(true)
                    .queue();
            return;
        }

        if (!hasManageServer(event.getMember())) {
            event.reply("You need Manage Server permission to use `/orders-reminder`.")
                    .setEphemeral(true)
                    .queue();
            return;
        }

        String subcommand = event.getSubcommandName();
        if (subcommand == null || subcommand.isBlank()) {
            event.reply("Please choose a subcommand: `status`, `set-enabled`, `set-time`, `set-route`, `remove-route`, or `set-copy`.")
                    .setEphemeral(true)
                    .queue();
            return;
        }

        switch (subcommand) {
            case "status" -> handleStatus(event);
            case "set-enabled" -> handleSetEnabled(event);
            case "set-time" -> handleSetTime(event);
            case "set-route" -> handleSetRoute(event);
            case "remove-route" -> handleRemoveRoute(event);
            case "set-copy" -> handleSetCopy(event);
            default -> event.reply("Unknown subcommand.").setEphemeral(true).queue();
        }
    }

    public void handleManualReminder(SlashCommandInteractionEvent event) {
        if (event.getGuild() == null || event.getMember() == null) {
            event.reply("This command can only be used inside a Discord server.")
                    .setEphemeral(true)
                    .queue();
            return;
        }

        if (!hasManageServer(event.getMember())) {
            event.reply("You need Manage Server permission to use `/order-remind`.")
                    .setEphemeral(true)
                    .queue();
            return;
        }

        OptionMapping forumOption = event.getOption(PrimoCommands.ORDER_REMIND_FORUM_OPTION);
        if (forumOption == null || forumOption.getType() != OptionType.CHANNEL
                || forumOption.getAsChannel().getType() != ChannelType.FORUM) {
            event.reply("`forum` must be a forum channel.")
                    .setEphemeral(true)
                    .queue();
            return;
        }

        ForumChannel selectedForum = forumOption.getAsChannel().asForumChannel();
        if (!isOrdersCategoryForum(selectedForum)) {
            event.reply("Forum must be under the `Orders` category.")
                    .setEphemeral(true)
                    .queue();
            return;
        }

        OrdersReminderConfig config = configStore.getSnapshot();
        OrdersReminderRoute route = config.getRoutes().stream()
                .filter(existing -> selectedForum.getId().equals(existing.getForumId()))
                .findFirst()
                .orElse(null);

        if (route == null) {
            event.reply("No route is configured for `%s`. Run `/orders-reminder set-route` first."
                            .formatted(selectedForum.getName()))
                    .setEphemeral(true)
                    .queue();
            return;
        }

        ManualDispatchResult result = dispatchManualReminder(event.getGuild(), route, config);
        switch (result.status()) {
            case SENT -> event.reply("Manual reminder sent for `%s` to <#%s>."
                            .formatted(result.forumName(), result.targetChannelId()))
                    .setEphemeral(true)
                    .queue();
            case NO_OPEN_ORDERS -> event.reply("No open orders found in `%s`, so nothing was sent."
                            .formatted(result.forumName()))
                    .setEphemeral(true)
                    .queue();
            case FORUM_NOT_FOUND -> event.reply("Could not find forum `<#%s>`. Update the route with `/orders-reminder set-route`."
                            .formatted(route.getForumId()))
                    .setEphemeral(true)
                    .queue();
            case TARGET_NOT_FOUND -> event.reply("Could not find target channel `<#%s>`. Update the route with `/orders-reminder set-route`."
                            .formatted(route.getTargetTextChannelId()))
                    .setEphemeral(true)
                    .queue();
            case ROLE_NOT_FOUND -> event.reply("Could not find role `<@&%s>`. Update the route with `/orders-reminder set-route`."
                            .formatted(route.getMentionRoleId()))
                    .setEphemeral(true)
                    .queue();
            case ROLE_CANNOT_BE_MENTIONED -> event.reply("The bot cannot mention that role in the target channel. Make the role mentionable or grant Mention Everyone permission.")
                    .setEphemeral(true)
                    .queue();
            case SEND_FAILED -> event.reply("Failed to send reminder: " + result.errorMessage())
                    .setEphemeral(true)
                    .queue();
        }
    }

    private void handleStatus(SlashCommandInteractionEvent event) {
        OrdersReminderConfig config = configStore.getSnapshot();
        String nextRun = describeNextRun(config);

        String routes = config.getRoutes().isEmpty()
                ? "(none)"
                : config.getRoutes().stream()
                .sorted(Comparator.comparing(OrdersReminderRoute::getForumId))
                .map(route -> "- Forum `<#%s>` -> Channel `<#%s>`, Role `<@&%s>`"
                        .formatted(route.getForumId(), route.getTargetTextChannelId(), route.getMentionRoleId()))
                .reduce((a, b) -> a + "\n" + b)
                .orElse("(none)");

        String response = """
                **Orders Reminder Settings**
                Enabled: `%s`
                Time: `%02d:%02d`
                Timezone: `%s`
                Tone: `%s`
                Signature: `%s`
                Next Run: `%s`

                **Routes**
                %s
                """.formatted(
                config.isEnabled(),
                config.getHour(),
                config.getMinute(),
                config.getTimezone(),
                config.getMessageTone(),
                config.getSignature(),
                nextRun,
                routes
        );

        event.reply(response).setEphemeral(true).queue();
    }

    private void handleSetEnabled(SlashCommandInteractionEvent event) {
        OptionMapping enabledOption = event.getOption("enabled");
        if (enabledOption == null) {
            event.reply("Missing `enabled` option.").setEphemeral(true).queue();
            return;
        }

        boolean enabled = enabledOption.getAsBoolean();
        OrdersReminderConfig config = configStore.getSnapshot();
        config.setEnabled(enabled);
        configStore.replaceAndPersist(config);

        event.reply("Orders reminders are now `%s`.".formatted(enabled ? "enabled" : "disabled"))
                .setEphemeral(true)
                .queue();
    }

    private void handleSetTime(SlashCommandInteractionEvent event) {
        OptionMapping hourOption = event.getOption("hour");
        OptionMapping minuteOption = event.getOption("minute");

        if (hourOption == null || minuteOption == null) {
            event.reply("Missing required options `hour` and `minute`.").setEphemeral(true).queue();
            return;
        }

        int hour = hourOption.getAsInt();
        int minute = minuteOption.getAsInt();
        if (hour < 0 || hour > 23 || minute < 0 || minute > 59) {
            event.reply("Invalid time. Use hour 0-23 and minute 0-59.").setEphemeral(true).queue();
            return;
        }

        String timezone = event.getOption("timezone", OptionMapping::getAsString);
        if (timezone != null) {
            timezone = timezone.trim();
            if (timezone.isEmpty()) {
                timezone = null;
            }
            if (timezone != null && !isValidTimezone(timezone)) {
                event.reply("Invalid timezone. Use an IANA timezone like `Asia/Manila`.")
                        .setEphemeral(true)
                        .queue();
                return;
            }
        }

        OrdersReminderConfig config = configStore.getSnapshot();
        config.setHour(hour);
        config.setMinute(minute);
        if (timezone != null) {
            config.setTimezone(timezone);
        }
        configStore.replaceAndPersist(config);

        event.reply("Reminder time updated to `%02d:%02d %s`.".formatted(
                        config.getHour(),
                        config.getMinute(),
                        config.getTimezone()))
                .setEphemeral(true)
                .queue();
    }

    private void handleSetRoute(SlashCommandInteractionEvent event) {
        OptionMapping forumOption = event.getOption("forum");
        OptionMapping targetOption = event.getOption("target");
        OptionMapping roleOption = event.getOption("role");

        if (forumOption == null || targetOption == null || roleOption == null) {
            event.reply("Missing required options: `forum`, `target`, and `role`.")
                    .setEphemeral(true)
                    .queue();
            return;
        }

        if (forumOption.getType() != OptionType.CHANNEL || forumOption.getAsChannel().getType() != ChannelType.FORUM) {
            event.reply("`forum` must be a forum channel.").setEphemeral(true).queue();
            return;
        }

        if (targetOption.getType() != OptionType.CHANNEL || targetOption.getAsChannel().getType() != ChannelType.TEXT) {
            event.reply("`target` must be a text channel.").setEphemeral(true).queue();
            return;
        }

        ForumChannel forum = forumOption.getAsChannel().asForumChannel();
        TextChannel target = targetOption.getAsChannel().asTextChannel();
        Role role = roleOption.getAsRole();

        if (!isOrdersCategoryForum(forum)) {
            event.reply("Forum must be under the `Orders` category.").setEphemeral(true).queue();
            return;
        }

        Member selfMember = event.getGuild().getSelfMember();
        if (!canBotMentionRole(selfMember, target, role)) {
            event.reply("The bot cannot mention that role in the target channel. Make the role mentionable or grant Mention Everyone permission.")
                    .setEphemeral(true)
                    .queue();
            return;
        }

        OrdersReminderConfig config = configStore.getSnapshot();
        List<OrdersReminderRoute> updatedRoutes = config.getRoutes().stream()
                .filter(existing -> !forum.getId().equals(existing.getForumId()))
                .toList();

        config.setRoutes(new java.util.ArrayList<>(updatedRoutes));
        config.getRoutes().add(new OrdersReminderRoute(forum.getId(), target.getId(), role.getId()));
        configStore.replaceAndPersist(config);

        event.reply("Route saved: `%s` -> %s (role %s).".formatted(forum.getName(), target.getAsMention(), role.getAsMention()))
                .setEphemeral(true)
                .queue();
    }

    private void handleRemoveRoute(SlashCommandInteractionEvent event) {
        OptionMapping forumOption = event.getOption("forum");
        if (forumOption == null || forumOption.getType() != OptionType.CHANNEL
                || forumOption.getAsChannel().getType() != ChannelType.FORUM) {
            event.reply("`forum` must be a forum channel.").setEphemeral(true).queue();
            return;
        }

        String forumId = forumOption.getAsChannel().getId();
        OrdersReminderConfig config = configStore.getSnapshot();
        int before = config.getRoutes().size();
        config.setRoutes(config.getRoutes().stream()
                .filter(route -> !forumId.equals(route.getForumId()))
                .toList());

        if (before == config.getRoutes().size()) {
            event.reply("No route found for that forum.").setEphemeral(true).queue();
            return;
        }

        config.getLastRunDateByRoute().remove(forumId);
        configStore.replaceAndPersist(config);

        event.reply("Route removed for forum `<#%s>`.".formatted(forumId))
                .setEphemeral(true)
                .queue();
    }

    private void handleSetCopy(SlashCommandInteractionEvent event) {
        String tone = event.getOption("tone", OptionMapping::getAsString);
        String signature = event.getOption("signature", OptionMapping::getAsString);

        if ((tone == null || tone.isBlank()) && (signature == null || signature.isBlank())) {
            event.reply("Provide at least one option: `tone` or `signature`.")
                    .setEphemeral(true)
                    .queue();
            return;
        }

        OrdersReminderConfig config = configStore.getSnapshot();
        if (tone != null && !tone.isBlank()) {
            config.setMessageTone(tone.trim().toLowerCase(Locale.ENGLISH));
        }
        if (signature != null && !signature.isBlank()) {
            config.setSignature(signature.trim());
        }
        configStore.replaceAndPersist(config);

        event.reply("Reminder copy updated. Tone: `%s`, Signature: `%s`.".formatted(
                        config.getMessageTone(),
                        config.getSignature()))
                .setEphemeral(true)
                .queue();
    }

    private ManualDispatchResult dispatchManualReminder(Guild guild,
                                                        OrdersReminderRoute route,
                                                        OrdersReminderConfig config) {
        ForumChannel forum = guild.getForumChannelById(route.getForumId());
        if (forum == null) {
            return ManualDispatchResult.forStatus(ManualDispatchStatus.FORUM_NOT_FOUND);
        }

        TextChannel target = guild.getTextChannelById(route.getTargetTextChannelId());
        if (target == null) {
            return ManualDispatchResult.forStatus(ManualDispatchStatus.TARGET_NOT_FOUND);
        }

        Role role = guild.getRoleById(route.getMentionRoleId());
        if (role == null) {
            return ManualDispatchResult.forStatus(ManualDispatchStatus.ROLE_NOT_FOUND);
        }

        Member selfMember = guild.getSelfMember();
        if (!canBotMentionRole(selfMember, target, role)) {
            return ManualDispatchResult.forStatus(ManualDispatchStatus.ROLE_CANNOT_BE_MENTIONED);
        }

        List<ThreadChannel> openThreads = new ArrayList<>(forum.getThreadChannels().stream()
                .filter(thread -> !thread.isArchived())
                .sorted(Comparator.comparing(ThreadChannel::getName, String.CASE_INSENSITIVE_ORDER))
                .toList());
        if (openThreads.isEmpty()) {
            return ManualDispatchResult.forStatus(ManualDispatchStatus.NO_OPEN_ORDERS, forum.getName(), target.getId(), null);
        }

        ZoneId zoneId = resolveZoneId(config.getTimezone());
        ZonedDateTime now = ZonedDateTime.now(zoneId);
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
        } catch (Exception ex) {
            return ManualDispatchResult.forStatus(ManualDispatchStatus.SEND_FAILED, forum.getName(), target.getId(), ex.getMessage());
        }

        config.getLastRunDateByRoute().put(route.getForumId(), now.toLocalDate().toString());
        configStore.replaceAndPersist(config);
        return ManualDispatchResult.forStatus(ManualDispatchStatus.SENT, forum.getName(), target.getId(), null);
    }

    private boolean hasManageServer(Member member) {
        return member != null && member.hasPermission(Permission.MANAGE_SERVER);
    }

    private boolean isOrdersCategoryForum(ForumChannel forum) {
        return forum != null
                && forum.getParentCategory() != null
                && ORDERS_CATEGORY_NAME.equalsIgnoreCase(forum.getParentCategory().getName());
    }

    private boolean canBotMentionRole(Member selfMember, TextChannel targetChannel, Role role) {
        if (selfMember == null || targetChannel == null || role == null) {
            return false;
        }
        return role.isMentionable() || selfMember.hasPermission(targetChannel, Permission.MESSAGE_MENTION_EVERYONE);
    }

    private boolean isValidTimezone(String timezone) {
        try {
            ZoneId.of(timezone);
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    private String describeNextRun(OrdersReminderConfig config) {
        try {
            ZoneId zoneId = ZoneId.of(config.getTimezone());
            ZonedDateTime now = ZonedDateTime.now(zoneId);
            ZonedDateTime run = now.withHour(config.getHour()).withMinute(config.getMinute()).withSecond(0).withNano(0);
            if (!run.isAfter(now)) {
                run = run.plusDays(1);
            }
            return run.toString();
        } catch (Exception ignored) {
            return "Invalid timezone";
        }
    }

    private ZoneId resolveZoneId(String timezone) {
        try {
            return ZoneId.of(timezone);
        } catch (Exception ignored) {
            return ZoneId.of(FALLBACK_TIMEZONE);
        }
    }

    private enum ManualDispatchStatus {
        SENT,
        NO_OPEN_ORDERS,
        FORUM_NOT_FOUND,
        TARGET_NOT_FOUND,
        ROLE_NOT_FOUND,
        ROLE_CANNOT_BE_MENTIONED,
        SEND_FAILED
    }

    private record ManualDispatchResult(ManualDispatchStatus status,
                                        String forumName,
                                        String targetChannelId,
                                        String errorMessage) {
        private static ManualDispatchResult forStatus(ManualDispatchStatus status) {
            return new ManualDispatchResult(status, "", "", null);
        }

        private static ManualDispatchResult forStatus(ManualDispatchStatus status,
                                                      String forumName,
                                                      String targetChannelId,
                                                      String errorMessage) {
            return new ManualDispatchResult(
                    status,
                    forumName == null ? "" : forumName,
                    targetChannelId == null ? "" : targetChannelId,
                    errorMessage
            );
        }
    }
}
