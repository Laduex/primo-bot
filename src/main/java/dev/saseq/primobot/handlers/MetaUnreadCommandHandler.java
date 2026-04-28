package dev.saseq.primobot.handlers;

import dev.saseq.primobot.meta.MetaUnreadConfig;
import dev.saseq.primobot.meta.MetaUnreadConfigStore;
import dev.saseq.primobot.meta.MetaUnreadExecutorService;
import dev.saseq.primobot.meta.MetaUnreadSchedulerService;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.channel.ChannelType;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.InteractionHook;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.CompletableFuture;

@Component
public class MetaUnreadCommandHandler {
    private static final int MIN_INTERVAL_MINUTES = 5;
    private static final int MAX_INTERVAL_MINUTES = 60;
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z");

    private final MetaUnreadConfigStore configStore;
    private final MetaUnreadSchedulerService schedulerService;

    public MetaUnreadCommandHandler(MetaUnreadConfigStore configStore,
                                    MetaUnreadSchedulerService schedulerService) {
        this.configStore = configStore;
        this.schedulerService = schedulerService;
    }

    public void handle(SlashCommandInteractionEvent event) {
        if (event.getGuild() == null || event.getMember() == null) {
            event.reply("This command can only be used inside a Discord server.")
                    .setEphemeral(true)
                    .queue();
            return;
        }

        if (!hasManageServer(event.getMember())) {
            event.reply("You need Manage Server permission to use `/meta-unread`.")
                    .setEphemeral(true)
                    .queue();
            return;
        }

        String subcommand = event.getSubcommandName();
        if (subcommand == null || subcommand.isBlank()) {
            event.reply("Please choose a subcommand: `status`, `set-enabled`, `set-channel`, `set-interval`, or `run-now`.")
                    .setEphemeral(true)
                    .queue();
            return;
        }

        switch (subcommand) {
            case "status" -> handleStatus(event);
            case "set-enabled" -> handleSetEnabled(event);
            case "set-channel" -> handleSetChannel(event);
            case "set-interval" -> handleSetInterval(event);
            case "run-now" -> handleRunNow(event);
            default -> event.reply("Unknown subcommand.").setEphemeral(true).queue();
        }
    }

    private void handleStatus(SlashCommandInteractionEvent event) {
        MetaUnreadConfig config = configStore.getSnapshot();

        long lastRunAt = Math.max(0L, config.getLastRunAtEpochMs());
        String lastRun = lastRunAt <= 0L
                ? "(never)"
                : formatInstant(lastRunAt);

        long nextDueAt = lastRunAt <= 0L
                ? 0L
                : lastRunAt + (Math.max(1, config.getIntervalMinutes()) * 60_000L);
        String nextDue = nextDueAt <= 0L
                ? "(eligible now)"
                : formatInstant(nextDueAt);

        String response = """
                **Meta Unread Settings**
                Enabled: `%s`
                Interval: `%d` minutes
                Target Channel: %s
                Last Run: `%s`
                Next Eligible Run: `%s`
                """.formatted(
                config.isEnabled(),
                config.getIntervalMinutes(),
                formatChannelMention(config.getTargetChannelId()),
                lastRun,
                nextDue
        );

        event.reply(response)
                .setEphemeral(true)
                .queue();
    }

    private void handleSetEnabled(SlashCommandInteractionEvent event) {
        OptionMapping enabledOption = event.getOption("enabled");
        if (enabledOption == null) {
            event.reply("Missing required option `enabled`.")
                    .setEphemeral(true)
                    .queue();
            return;
        }

        MetaUnreadConfig config = configStore.getSnapshot();
        config.setEnabled(enabledOption.getAsBoolean());
        configStore.replaceAndPersist(config);

        event.reply("Meta unread checks are now `%s`.".formatted(config.isEnabled() ? "enabled" : "disabled"))
                .setEphemeral(true)
                .queue();
    }

    private void handleSetChannel(SlashCommandInteractionEvent event) {
        OptionMapping targetOption = event.getOption("target");
        if (targetOption == null || targetOption.getType() != OptionType.CHANNEL) {
            event.reply("Missing required option `target`.")
                    .setEphemeral(true)
                    .queue();
            return;
        }

        ChannelType type = targetOption.getAsChannel().getType();
        if (!isSupportedTargetType(type)) {
            event.reply("Unsupported target channel. Use a text/news/thread channel or a forum channel.")
                    .setEphemeral(true)
                    .queue();
            return;
        }

        MetaUnreadConfig config = configStore.getSnapshot();
        config.setTargetChannelId(targetOption.getAsChannel().getId());
        configStore.replaceAndPersist(config);

        event.reply("Meta unread target channel set to %s.".formatted(targetOption.getAsChannel().getAsMention()))
                .setEphemeral(true)
                .queue();
    }

    private void handleSetInterval(SlashCommandInteractionEvent event) {
        OptionMapping minutesOption = event.getOption("minutes");
        if (minutesOption == null) {
            event.reply("Missing required option `minutes`.")
                    .setEphemeral(true)
                    .queue();
            return;
        }

        int minutes = minutesOption.getAsInt();
        if (minutes < MIN_INTERVAL_MINUTES || minutes > MAX_INTERVAL_MINUTES) {
            event.reply("Invalid interval. Use `%d` to `%d` minutes."
                            .formatted(MIN_INTERVAL_MINUTES, MAX_INTERVAL_MINUTES))
                    .setEphemeral(true)
                    .queue();
            return;
        }

        MetaUnreadConfig config = configStore.getSnapshot();
        config.setIntervalMinutes(minutes);
        configStore.replaceAndPersist(config);

        event.reply("Meta unread interval set to `%d` minutes.".formatted(config.getIntervalMinutes()))
                .setEphemeral(true)
                .queue();
    }

    private void handleRunNow(SlashCommandInteractionEvent event) {
        event.deferReply(true).queue(hook -> CompletableFuture
                .supplyAsync(() -> schedulerService.runNow(event.getGuild()))
                .whenComplete((result, error) -> {
                    if (error != null) {
                        Throwable cause = error.getCause() != null ? error.getCause() : error;
                        String message = cause.getMessage() == null || cause.getMessage().isBlank()
                                ? "Unknown error"
                                : cause.getMessage();
                        hook.editOriginal("Meta unread run failed: " + message).queue();
                        return;
                    }
                    replyRunNowResult(hook, result);
                }));
    }

    private void replyRunNowResult(InteractionHook hook, MetaUnreadExecutorService.DispatchResult result) {
        switch (result.status()) {
            case SENT -> hook.editOriginal(
                    "Meta unread digest sent to <#%s>. Pages: `%d`, Unread conversations: `%d`, Unread messages: `%d`, IG warnings: `%d`."
                            .formatted(result.targetChannelId(),
                                    result.pagesScanned(),
                                    result.unreadThreads(),
                                    result.unreadMessages(),
                                    result.warningCount())
            ).queue();
            case NO_UNREAD -> hook.editOriginal(
                    "No unread conversations found right now. Nothing was sent. Pages scanned: `%d`, IG warnings: `%d`."
                            .formatted(result.pagesScanned(), result.warningCount())
            ).queue();
            case TARGET_NOT_CONFIGURED -> hook.editOriginal(
                    "No target channel configured. Run `/meta-unread set-channel` first."
            ).queue();
            case TARGET_NOT_FOUND -> hook.editOriginal(
                    "Target channel `<#%s>` was not found."
                            .formatted(result.targetChannelId())
            ).queue();
            case TARGET_UNSUPPORTED -> hook.editOriginal(
                    "Configured target channel type is not supported. Use a text/news/thread channel or a forum channel."
            ).queue();
            case GUILD_NOT_FOUND, FETCH_FAILED, SEND_FAILED -> {
                String message = result.message() == null || result.message().isBlank() ? "Unknown error" : result.message();
                hook.editOriginal("Meta unread run failed: " + message).queue();
            }
        }
    }

    private boolean hasManageServer(Member member) {
        return member != null && member.hasPermission(Permission.MANAGE_SERVER);
    }

    private boolean isSupportedTargetType(ChannelType type) {
        if (type == null) {
            return false;
        }
        return type == ChannelType.FORUM || type.isMessage();
    }

    private String formatChannelMention(String channelId) {
        if (channelId == null || channelId.isBlank()) {
            return "(not set)";
        }
        return "<#" + channelId.trim() + ">";
    }

    private String formatInstant(long epochMs) {
        return ZonedDateTime.ofInstant(Instant.ofEpochMilli(epochMs), ZoneId.systemDefault())
                .format(TIME_FORMATTER);
    }
}
