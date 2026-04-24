package dev.saseq.primobot.handlers;

import dev.saseq.primobot.commands.PrimoCommands;
import dev.saseq.primobot.sales.SalesAccountConfig;
import dev.saseq.primobot.sales.SalesPlatform;
import dev.saseq.primobot.sales.SalesReportConfig;
import dev.saseq.primobot.sales.SalesReportConfigStore;
import dev.saseq.primobot.sales.SalesReportExecutorService;
import dev.saseq.primobot.sales.SalesReportSchedulerService;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.entities.channel.ChannelType;
import net.dv8tion.jda.api.entities.channel.middleman.MessageChannel;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.events.interaction.command.CommandAutoCompleteInteractionEvent;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.InteractionHook;
import net.dv8tion.jda.api.interactions.commands.Command;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.UUID;
import java.util.regex.Pattern;

@Component
public class SalesReportCommandHandler {
    private static final int DISCORD_AUTOCOMPLETE_MAX_CHOICES = 25;
    private static final DateTimeFormatter SLOT_FORMATTER = DateTimeFormatter.ofPattern("HH:mm");
    private static final Pattern DIRECT_RUN_NOW_PATTERN =
            Pattern.compile("(?i)^sales\\s+run\\s+now(?:\\s+(.*))?$");

    private final SalesReportConfigStore configStore;
    private final SalesReportExecutorService executorService;
    private final SalesReportSchedulerService schedulerService;
    private final String defaultGuildId;

    public SalesReportCommandHandler(SalesReportConfigStore configStore,
                                     SalesReportExecutorService executorService,
                                     SalesReportSchedulerService schedulerService,
                                     @Value("${DISCORD_GUILD_ID:}") String defaultGuildId) {
        this.configStore = configStore;
        this.executorService = executorService;
        this.schedulerService = schedulerService;
        this.defaultGuildId = defaultGuildId == null ? "" : defaultGuildId.trim();
    }

    public void handle(SlashCommandInteractionEvent event) {
        if (event.getGuild() == null || event.getMember() == null) {
            if (isDirectRunNowSlash(event)) {
                handleDirectRunNowSlash(event);
                return;
            }
            event.reply("This command can only be used inside a Discord server. In DMs, use `/sales run-now` or `sales run now`.")
                    .setEphemeral(true)
                    .queue();
            return;
        }

        if (!hasManageServer(event.getMember())) {
            event.reply("You need Manage Server permission to use sales commands.")
                    .setEphemeral(true)
                    .queue();
            return;
        }

        String subcommand = event.getSubcommandName();
        if (subcommand == null || subcommand.isBlank()) {
            event.reply("Please choose a sales subcommand.")
                    .setEphemeral(true)
                    .queue();
            return;
        }

        switch (subcommand) {
            case "status" -> handleStatus(event);
            case "set-enabled" -> handleSetEnabled(event);
            case "set-timezone" -> handleSetTimezone(event);
            case "add-time" -> handleAddTime(event);
            case "remove-time" -> handleRemoveTime(event);
            case "clear-times" -> handleClearTimes(event);
            case "set-summary" -> handleSetSummary(event);
            case "set-channel" -> handleSetChannel(event);
            case "run-now" -> handleRunNow(event);
            case "list-accounts" -> handleListAccounts(event);
            case "add-account" -> handleAddAccount(event);
            case "update-account" -> handleUpdateAccount(event);
            case "remove-account" -> handleRemoveAccount(event);
            case "set-account-enabled" -> handleSetAccountEnabled(event);
            case "set-copy" -> handleSetCopy(event);
            default -> event.reply("Unknown subcommand.").setEphemeral(true).queue();
        }
    }

    private boolean isDirectRunNowSlash(SlashCommandInteractionEvent event) {
        if (event == null) {
            return false;
        }
        if (!"run-now".equals(event.getSubcommandName())) {
            return false;
        }
        String commandName = event.getName();
        return PrimoCommands.COMMAND_SALES.equals(commandName)
                || PrimoCommands.COMMAND_SALES_REPORT.equals(commandName);
    }

    private void handleStatus(SlashCommandInteractionEvent event) {
        SalesReportConfig config = configStore.getSnapshot();
        String updateTimesText = config.getTimes().isEmpty() ? "(none)" : String.join(", ", config.getTimes());
        String overviewTimeText = isBlank(config.getOverviewTime()) ? "(not set)" : config.getOverviewTime();
        String updateTargetChannel = formatChannelMention(config.getTargetChannelId());
        String overviewTargetChannel = formatChannelMention(config.getOverviewTargetChannelId());

        String accounts = config.getAccounts().isEmpty()
                ? "(none)"
                : config.getAccounts().stream()
                .sorted(Comparator.comparing(SalesAccountConfig::getId, String.CASE_INSENSITIVE_ORDER))
                .map(account -> "- `%s` | %s | %s | enabled: `%s`"
                        .formatted(
                                account.getId(),
                                account.resolvePlatform() == null ? "Unknown" : account.resolvePlatform().getDisplayName(),
                                account.getName(),
                                account.isEnabled()))
                .reduce((a, b) -> a + "\n" + b)
                .orElse("(none)");

        String response = """
                **Sales Report Settings**
                Enabled: `%s`
                Timezone: `%s`
                Sales Update Times: `%s`
                Daily Overview Time: `%s`
                Sales Update Channel: %s
                Daily Overview Channel: %s
                Tone: `%s`
                Signature: `%s`
                Next Sales Update: `%s`
                Next Daily Overview: `%s`

                **Accounts**
                %s
                """.formatted(
                config.isEnabled(),
                config.getTimezone(),
                updateTimesText,
                overviewTimeText,
                updateTargetChannel,
                overviewTargetChannel,
                config.getMessageTone(),
                config.getSignature(),
                describeNextScheduledRun(config.getTimes(), config.getTimezone()),
                describeNextOverview(config),
                accounts
        );

        event.reply(response).setEphemeral(true).queue();
    }

    private void handleSetEnabled(SlashCommandInteractionEvent event) {
        OptionMapping enabledOption = event.getOption("enabled");
        if (enabledOption == null) {
            event.reply("Missing `enabled` option.").setEphemeral(true).queue();
            return;
        }

        SalesReportConfig config = configStore.getSnapshot();
        config.setEnabled(enabledOption.getAsBoolean());
        configStore.replaceAndPersist(config);

        event.reply("Sales reports are now `%s`.".formatted(config.isEnabled() ? "enabled" : "disabled"))
                .setEphemeral(true)
                .queue();
    }

    private void handleSetTimezone(SlashCommandInteractionEvent event) {
        String timezone = event.getOption("timezone", OptionMapping::getAsString);
        if (timezone == null || timezone.isBlank()) {
            event.reply("Missing `timezone` option.").setEphemeral(true).queue();
            return;
        }

        timezone = timezone.trim();
        if (!isValidTimezone(timezone)) {
            event.reply("Invalid timezone. Use an IANA value like `Asia/Manila`.")
                    .setEphemeral(true)
                    .queue();
            return;
        }

        SalesReportConfig config = configStore.getSnapshot();
        config.setTimezone(timezone);
        configStore.replaceAndPersist(config);

        event.reply("Sales report timezone set to `%s`.".formatted(config.getTimezone()))
                .setEphemeral(true)
                .queue();
    }

    private void handleAddTime(SlashCommandInteractionEvent event) {
        LocalTime slot = parseSlot(event);
        if (slot == null) {
            return;
        }

        SalesReportConfig config = configStore.getSnapshot();
        String formatted = slot.format(SLOT_FORMATTER);
        if (config.getTimes().contains(formatted)) {
            event.reply("Time `%s` is already in the sales update schedule.".formatted(formatted))
                    .setEphemeral(true)
                    .queue();
            return;
        }

        List<String> updated = new ArrayList<>(config.getTimes());
        updated.add(formatted);
        updated.sort(String::compareTo);
        config.setTimes(updated);
        configStore.replaceAndPersist(config);

        event.reply("Added `%s` to sales update schedule.".formatted(formatted))
                .setEphemeral(true)
                .queue();
    }

    private void handleRemoveTime(SlashCommandInteractionEvent event) {
        LocalTime slot = parseSlot(event);
        if (slot == null) {
            return;
        }

        SalesReportConfig config = configStore.getSnapshot();
        String formatted = slot.format(SLOT_FORMATTER);
        if (!config.getTimes().contains(formatted)) {
            event.reply("Time `%s` is not in the sales update schedule.".formatted(formatted))
                    .setEphemeral(true)
                    .queue();
            return;
        }

        config.setTimes(config.getTimes().stream()
                .filter(existing -> !formatted.equals(existing))
                .toList());
        config.getLastRunDateBySlot().remove("update:" + formatted);
        config.getLastRunDateBySlot().remove(formatted);
        configStore.replaceAndPersist(config);

        event.reply("Removed `%s` from sales update schedule.".formatted(formatted))
                .setEphemeral(true)
                .queue();
    }

    private void handleClearTimes(SlashCommandInteractionEvent event) {
        OptionMapping confirmOption = event.getOption("confirm");
        if (confirmOption == null || !confirmOption.getAsBoolean()) {
            event.reply("Set `confirm:true` to clear all sales update schedule times.")
                    .setEphemeral(true)
                    .queue();
            return;
        }

        SalesReportConfig config = configStore.getSnapshot();
        List<String> previousUpdateTimes = new ArrayList<>(config.getTimes());
        config.setTimes(new ArrayList<>());
        for (String slot : previousUpdateTimes) {
            config.getLastRunDateBySlot().remove("update:" + slot);
            config.getLastRunDateBySlot().remove(slot);
        }
        configStore.replaceAndPersist(config);

        event.reply("Cleared all sales update schedule times.")
                .setEphemeral(true)
                .queue();
    }

    private void handleSetChannel(SlashCommandInteractionEvent event) {
        OptionMapping targetOption = event.getOption("target");
        if (targetOption == null || targetOption.getType() != OptionType.CHANNEL
                || (targetOption.getAsChannel().getType() != ChannelType.TEXT
                && targetOption.getAsChannel().getType() != ChannelType.FORUM)) {
            event.reply("`target` must be a text or forum channel.")
                    .setEphemeral(true)
                    .queue();
            return;
        }

        SalesReportConfig config = configStore.getSnapshot();
        config.setTargetChannelId(targetOption.getAsChannel().getId());
        configStore.replaceAndPersist(config);

        event.reply("Sales update channel set to %s.".formatted(targetOption.getAsChannel().getAsMention()))
                .setEphemeral(true)
                .queue();
    }

    private void handleSetSummary(SlashCommandInteractionEvent event) {
        OptionMapping targetOption = event.getOption("target");
        if (targetOption == null || targetOption.getType() != OptionType.CHANNEL
                || (targetOption.getAsChannel().getType() != ChannelType.TEXT
                && targetOption.getAsChannel().getType() != ChannelType.FORUM)) {
            event.reply("`target` must be a text or forum channel.")
                    .setEphemeral(true)
                    .queue();
            return;
        }

        LocalTime slot = parseSlot(event);
        if (slot == null) {
            return;
        }

        String timezone = event.getOption("timezone", "", OptionMapping::getAsString).trim();
        if (!timezone.isBlank() && !isValidTimezone(timezone)) {
            event.reply("Invalid timezone. Use an IANA value like `Asia/Manila`.")
                    .setEphemeral(true)
                    .queue();
            return;
        }

        SalesReportConfig config = configStore.getSnapshot();
        String formatted = slot.format(SLOT_FORMATTER);
        String previousOverviewTime = config.getOverviewTime();
        config.setOverviewTargetChannelId(targetOption.getAsChannel().getId());
        config.setOverviewTime(formatted);
        config.getLastRunDateBySlot().remove("overview:" + formatted);
        config.getLastRunDateBySlot().remove(formatted);
        if (!isBlank(previousOverviewTime)) {
            config.getLastRunDateBySlot().remove("overview:" + previousOverviewTime);
            config.getLastRunDateBySlot().remove(previousOverviewTime);
        }
        if (!timezone.isBlank()) {
            config.setTimezone(timezone);
        }
        configStore.replaceAndPersist(config);

        event.reply("Sales overview set to %s at `%s` (%s)."
                        .formatted(
                                targetOption.getAsChannel().getAsMention(),
                                formatted,
                                config.getTimezone()))
                .setEphemeral(true)
                .queue();
    }

    public void handleRunNowAccountAutocomplete(CommandAutoCompleteInteractionEvent event) {
        if (event.getGuild() != null && event.getMember() != null) {
            if (!hasManageServer(event.getMember())) {
                event.replyChoices(List.of()).queue();
                return;
            }
        } else if (!hasManageServerForDirectRunNow(event.getJDA(), event.getUser())) {
            event.replyChoices(List.of()).queue();
            return;
        }

        OptionMapping scopeOption = event.getOption("scope");
        if (scopeOption != null) {
            String scope = scopeOption.getAsString().trim().toLowerCase(Locale.ENGLISH);
            if (!"single".equals(scope)) {
                event.replyChoices(List.of()).queue();
                return;
            }
        }

        String query = event.getFocusedOption().getValue();
        SalesReportConfig config = configStore.getSnapshot();
        List<Command.Choice> choices = buildRunNowAccountChoices(config, query);
        event.replyChoices(choices).queue();
    }

    private void handleDirectRunNowSlash(SlashCommandInteractionEvent event) {
        if (!hasManageServerForDirectRunNow(event)) {
            event.reply("You need Manage Server permission to run `sales run now`.")
                    .setEphemeral(true)
                    .queue();
            return;
        }
        handleRunNow(event);
    }

    public boolean handleDirectRunNowMessage(MessageReceivedEvent event) {
        if (event == null || event.getAuthor().isBot()) {
            return false;
        }

        DirectRunNowRequest request = parseDirectRunNowRequest(event.getMessage().getContentRaw());
        if (request == null) {
            return false;
        }
        if (request.malformed()) {
            event.getMessage().reply("Usage: `sales run now` or `sales run now account <account-id-or-name>`.").queue();
            return true;
        }

        if (!hasManageServerForDirectRunNow(event)) {
            event.getMessage().reply("You need Manage Server permission to run `sales run now`.").queue();
            return true;
        }

        SalesReportConfig config = configStore.getSnapshot();
        String selectedAccountId = resolveRequestedAccountId(config, request.accountQuery());
        if (!request.accountQuery().isBlank() && selectedAccountId.isBlank()) {
            event.getMessage().reply("No account found for `%s`. Use `/sales-report list-accounts` to check valid account IDs."
                            .formatted(request.accountQuery()))
                    .queue();
            return true;
        }

        var channel = event.getChannel();
        String scopeLabel = selectedAccountId.isBlank()
                ? "all enabled accounts"
                : "account `%s`".formatted(selectedAccountId);
        channel.sendMessage("Running sales report now for %s...".formatted(scopeLabel))
                .queue();

        CompletableFuture
                .supplyAsync(() -> executorService.executeDirect(configStore.getSnapshot(),
                        selectedAccountId,
                        channel,
                        false))
                .whenComplete((result, error) -> {
                    if (error != null) {
                        Throwable cause = error.getCause() != null ? error.getCause() : error;
                        String message = cause.getMessage();
                        if (message == null || message.isBlank()) {
                            message = "Unknown error";
                        }
                        channel.sendMessage("Failed to send sales report: " + message).queue();
                        return;
                    }
                    replyDirectRunNowResult(channel, result);
                });
        return true;
    }

    private void handleRunNow(SlashCommandInteractionEvent event) {
        String overrideTargetId = "";
        OptionMapping targetOption = event.getOption("target");
        if (targetOption != null) {
            if (targetOption.getType() != OptionType.CHANNEL
                    || (targetOption.getAsChannel().getType() != ChannelType.TEXT
                    && targetOption.getAsChannel().getType() != ChannelType.FORUM)) {
                event.reply("`target` must be a text or forum channel.")
                        .setEphemeral(true)
                        .queue();
                return;
            }
            overrideTargetId = targetOption.getAsChannel().getId();
        }

        OptionMapping scopeOption = event.getOption("scope");
        String scope = scopeOption == null
                ? "all"
                : scopeOption.getAsString().trim().toLowerCase(Locale.ENGLISH);
        if (!"all".equals(scope) && !"single".equals(scope)) {
            event.reply("Invalid `scope`. Use `All Accounts` or `Single Account`.")
                    .setEphemeral(true)
                    .queue();
            return;
        }

        String providedAccountId = event.getOption("account", "", OptionMapping::getAsString).trim();
        if (providedAccountId.isBlank()) {
            providedAccountId = event.getOption("account-id", "", OptionMapping::getAsString).trim();
        }
        if (scopeOption == null && !providedAccountId.isBlank()) {
            scope = "single";
        }

        if ("all".equals(scope) && !providedAccountId.isBlank()) {
            event.reply("`account` is only used when `scope` is `Single Account`. Set `scope` to `Single Account` or clear `account`.")
                    .setEphemeral(true)
                    .queue();
            return;
        }

        String selectedAccountId = "";
        if ("single".equals(scope)) {
            selectedAccountId = providedAccountId;
            if (selectedAccountId.isBlank()) {
                event.reply("`account` is required when `scope` is Single Account. Pick an account name from the list.")
                        .setEphemeral(true)
                        .queue();
                return;
            }
        }

        String finalOverrideTargetId = overrideTargetId;
        String finalSelectedAccountId = selectedAccountId;
        var guild = event.getGuild();
        MessageChannel directChannel = guild == null ? event.getChannel() : null;
        event.deferReply(true).queue(hook -> {
            CompletableFuture
                    .supplyAsync(() -> {
                        if (guild == null) {
                            return executorService.executeDirect(
                                    configStore.getSnapshot(),
                                    finalSelectedAccountId,
                                    directChannel,
                                    false);
                        }
                        return schedulerService.runNow(guild, finalOverrideTargetId, finalSelectedAccountId);
                    })
                    .whenComplete((result, error) -> {
                        if (error != null) {
                            Throwable cause = error.getCause() != null ? error.getCause() : error;
                            String message = cause.getMessage();
                            if (message == null || message.isBlank()) {
                                message = "Unknown error";
                            }
                            hook.editOriginal("Failed to send sales report: " + message).queue();
                            return;
                        }
                        if (guild == null) {
                            replyDirectRunNowSlashResult(hook, result);
                        } else {
                            replyRunNowResult(hook, result);
                        }
                    });
        });
    }

    private void replyDirectRunNowSlashResult(InteractionHook hook,
                                              SalesReportExecutorService.DispatchResult result) {
        switch (result.status()) {
            case SENT -> {
                String scopeLabel = result.accountId() == null || result.accountId().isBlank()
                        ? "all enabled accounts"
                        : "account `%s`".formatted(result.accountId());
                hook.editOriginal("Sales report sent here for %s. Success: `%d`, Failed: `%d`."
                                .formatted(scopeLabel, result.successCount(), result.failureCount()))
                        .queue();
            }
            case ACCOUNT_NOT_FOUND -> {
                String accountId = result.accountId() == null ? "" : result.accountId().trim();
                if (accountId.isBlank()) {
                    hook.editOriginal("No matching account was found. Run `/sales-report list-accounts` in the server to check valid account IDs.")
                            .queue();
                } else {
                    hook.editOriginal("No account found for id `%s`. Run `/sales-report list-accounts` in the server to check valid account IDs."
                                    .formatted(accountId))
                            .queue();
                }
            }
            case GUILD_NOT_FOUND, SEND_FAILED, TARGET_NOT_CONFIGURED, TARGET_NOT_FOUND -> {
                String message = result.message();
                if (message == null || message.isBlank()) {
                    message = "Unknown error";
                }
                hook.editOriginal("Failed to send sales report: " + message)
                        .queue();
            }
        }
    }

    private void replyDirectRunNowResult(MessageChannel channel,
                                         SalesReportExecutorService.DispatchResult result) {
        switch (result.status()) {
            case SENT -> {
                String scopeLabel = result.accountId() == null || result.accountId().isBlank()
                        ? "all enabled accounts"
                        : "account `%s`".formatted(result.accountId());
                channel.sendMessage("Sales report sent here for %s. Success: `%d`, Failed: `%d`."
                                .formatted(scopeLabel, result.successCount(), result.failureCount()))
                        .queue();
            }
            case ACCOUNT_NOT_FOUND -> {
                String accountId = result.accountId() == null ? "" : result.accountId().trim();
                if (accountId.isBlank()) {
                    channel.sendMessage("No matching account was found. Run `/sales-report list-accounts` to check valid account IDs.")
                            .queue();
                } else {
                    channel.sendMessage("No account found for id `%s`. Run `/sales-report list-accounts` to check valid account IDs."
                                    .formatted(accountId))
                            .queue();
                }
            }
            case SEND_FAILED, TARGET_NOT_FOUND, TARGET_NOT_CONFIGURED, GUILD_NOT_FOUND -> {
                String message = result.message();
                if (message == null || message.isBlank()) {
                    message = "Unknown error";
                }
                channel.sendMessage("Failed to send sales report: " + message).queue();
            }
        }
    }

    private DirectRunNowRequest parseDirectRunNowRequest(String rawMessage) {
        if (rawMessage == null || rawMessage.isBlank()) {
            return null;
        }

        String normalized = rawMessage.trim().replaceAll("\\s+", " ");
        var matcher = DIRECT_RUN_NOW_PATTERN.matcher(normalized);
        if (!matcher.matches()) {
            return null;
        }

        String suffix = matcher.group(1) == null ? "" : matcher.group(1).trim();
        if (suffix.isBlank() || "all".equalsIgnoreCase(suffix)) {
            return new DirectRunNowRequest("", false);
        }

        boolean explicitSingleAccountSyntax = false;
        String lowered = suffix.toLowerCase(Locale.ENGLISH);
        if (lowered.startsWith("account")) {
            explicitSingleAccountSyntax = true;
            suffix = suffix.substring("account".length()).trim();
            if (suffix.startsWith(":")) {
                suffix = suffix.substring(1).trim();
            }
        } else if (lowered.startsWith("single")) {
            explicitSingleAccountSyntax = true;
            suffix = suffix.substring("single".length()).trim();
            if (suffix.startsWith("account")) {
                suffix = suffix.substring("account".length()).trim();
                if (suffix.startsWith(":")) {
                    suffix = suffix.substring(1).trim();
                }
            }
        }

        if (suffix.isBlank() && explicitSingleAccountSyntax) {
            return new DirectRunNowRequest("", true);
        }
        return new DirectRunNowRequest(suffix, false);
    }

    private Member resolveMemberForPermissionCheck(String userId, Guild guild) {
        if (userId == null || userId.isBlank() || guild == null) {
            return null;
        }

        Member cachedMember = guild.getMemberById(userId);
        if (cachedMember != null) {
            return cachedMember;
        }

        try {
            return guild.retrieveMemberById(userId).complete();
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private boolean hasManageServerForDirectRunNow(MessageReceivedEvent event) {
        if (event == null) {
            return false;
        }

        if (event.isFromGuild()) {
            return hasManageServer(event.getMember());
        }

        if (event.getJDA() == null) {
            return false;
        }

        return hasManageServerForDirectRunNow(event.getJDA(), event.getAuthor());
    }

    private boolean hasManageServerForDirectRunNow(SlashCommandInteractionEvent event) {
        if (event == null) {
            return false;
        }

        if (event.isFromGuild()) {
            return hasManageServer(event.getMember());
        }

        if (event.getJDA() == null) {
            return false;
        }

        return hasManageServerForDirectRunNow(event.getJDA(), event.getUser());
    }

    private boolean hasManageServerForDirectRunNow(JDA jda, User user) {
        if (jda == null || user == null) {
            return false;
        }

        if (!defaultGuildId.isBlank()) {
            Guild configuredGuild = jda.getGuildById(defaultGuildId);
            if (hasManageServer(resolveMemberForPermissionCheck(user.getId(), configuredGuild))) {
                return true;
            }
        }

        List<Guild> mutualGuilds = jda.getMutualGuilds(user);
        for (Guild guild : mutualGuilds) {
            if (hasManageServer(resolveMemberForPermissionCheck(user.getId(), guild))) {
                return true;
            }
        }

        return false;
    }

    private String resolveRequestedAccountId(SalesReportConfig config, String rawAccountQuery) {
        if (config == null || config.getAccounts() == null) {
            return "";
        }
        String query = rawAccountQuery == null ? "" : rawAccountQuery.trim();
        if (query.isBlank() || "all".equalsIgnoreCase(query)) {
            return "";
        }

        for (SalesAccountConfig account : config.getAccounts()) {
            if (account != null
                    && account.getId() != null
                    && query.equalsIgnoreCase(account.getId().trim())) {
                return account.getId().trim();
            }
        }
        for (SalesAccountConfig account : config.getAccounts()) {
            if (account != null
                    && account.getName() != null
                    && query.equalsIgnoreCase(account.getName().trim())
                    && account.getId() != null
                    && !account.getId().isBlank()) {
                return account.getId().trim();
            }
        }
        return "";
    }

    private void replyRunNowResult(InteractionHook hook, SalesReportExecutorService.DispatchResult result) {
        switch (result.status()) {
            case SENT -> {
                String scopeLabel = result.accountId() == null || result.accountId().isBlank()
                        ? "all enabled accounts"
                        : "account `%s`".formatted(result.accountId());
                hook.editOriginal("Sales report sent to <#%s> for %s. Success: `%d`, Failed: `%d`."
                                .formatted(result.targetChannelId(), scopeLabel, result.successCount(), result.failureCount()))
                        .queue();
            }
            case TARGET_NOT_CONFIGURED -> hook.editOriginal("No target channel configured. Run `/sales-report set-channel` or pass `target` in `/sales-report run-now`.")
                    .queue();
            case TARGET_NOT_FOUND -> hook.editOriginal("Target channel `<#%s>` was not found.".formatted(result.targetChannelId()))
                    .queue();
            case ACCOUNT_NOT_FOUND -> {
                String accountId = result.accountId() == null ? "" : result.accountId().trim();
                if (accountId.isBlank()) {
                    hook.editOriginal("No matching account was found. Run `/sales-report list-accounts` to check valid account IDs.")
                            .queue();
                } else {
                    hook.editOriginal("No account found for id `%s`. Run `/sales-report list-accounts` to check valid account IDs."
                                    .formatted(accountId))
                            .queue();
                }
            }
            case GUILD_NOT_FOUND, SEND_FAILED -> {
                String message = result.message();
                if (message == null || message.isBlank()) {
                    message = "Unknown error";
                }
                hook.editOriginal("Failed to send sales report: " + message)
                        .queue();
            }
        }
    }

    private void handleListAccounts(SlashCommandInteractionEvent event) {
        SalesReportConfig config = configStore.getSnapshot();
        if (config.getAccounts().isEmpty()) {
            event.reply("No sales accounts configured yet.")
                    .setEphemeral(true)
                    .queue();
            return;
        }

        String body = config.getAccounts().stream()
                .sorted(Comparator.comparing(SalesAccountConfig::getId, String.CASE_INSENSITIVE_ORDER))
                .map(account -> {
                    SalesPlatform platform = account.resolvePlatform();
                    return "- `%s` | %s | `%s` | enabled: `%s` | credentials: `%s`"
                            .formatted(
                                    account.getId(),
                                    platform == null ? "Unknown" : platform.getDisplayName(),
                                    account.getName(),
                                    account.isEnabled(),
                                    credentialsSummary(account));
                })
                .reduce((a, b) -> a + "\n" + b)
                .orElse("(none)");

        event.reply("**Sales Accounts**\n" + body)
                .setEphemeral(true)
                .queue();
    }

    private void handleAddAccount(SlashCommandInteractionEvent event) {
        String rawPlatform = event.getOption("platform", OptionMapping::getAsString);
        String name = event.getOption("name", OptionMapping::getAsString);

        if (rawPlatform == null || rawPlatform.isBlank() || name == null || name.isBlank()) {
            event.reply("Missing required options `platform` and `name`.")
                    .setEphemeral(true)
                    .queue();
            return;
        }

        SalesPlatform platform = SalesPlatform.fromRaw(rawPlatform);
        if (platform == null) {
            event.reply("Invalid platform. Use `UTAK` or `LOYVERSE`.")
                    .setEphemeral(true)
                    .queue();
            return;
        }

        String accountId = event.getOption("account-id", OptionMapping::getAsString);
        if (accountId == null || accountId.isBlank()) {
            accountId = defaultAccountId(platform);
        }
        accountId = accountId.trim();
        final String finalAccountId = accountId;

        SalesReportConfig config = configStore.getSnapshot();
        boolean exists = config.getAccounts().stream().anyMatch(existing -> finalAccountId.equalsIgnoreCase(existing.getId()));
        if (exists) {
            event.reply("Account id `%s` already exists. Choose another id.".formatted(finalAccountId))
                    .setEphemeral(true)
                    .queue();
            return;
        }

        SalesAccountConfig account = new SalesAccountConfig();
        account.setId(finalAccountId);
        account.setPlatform(platform.name());
        account.setName(name.trim());
        account.setEnabled(true);
        account.setUsername(event.getOption("username", OptionMapping::getAsString));
        account.setPassword(event.getOption("password", OptionMapping::getAsString));
        account.setToken(event.getOption("token", OptionMapping::getAsString));
        account.setBaseUrl(event.getOption("base-url", OptionMapping::getAsString));
        account.setSalesPageUrl(event.getOption("sales-url", OptionMapping::getAsString));

        String validationError = validateAccountByPlatform(account);
        if (validationError != null) {
            event.reply(validationError).setEphemeral(true).queue();
            return;
        }

        config.getAccounts().add(account);
        configStore.replaceAndPersist(config);

        event.reply("Added `%s` (%s) as account id `%s`."
                        .formatted(account.getName(), platform.getDisplayName(), account.getId()))
                .setEphemeral(true)
                .queue();
    }

    private void handleUpdateAccount(SlashCommandInteractionEvent event) {
        String accountId = event.getOption("account-id", OptionMapping::getAsString);
        if (accountId == null || accountId.isBlank()) {
            event.reply("Missing required option `account-id`.")
                    .setEphemeral(true)
                    .queue();
            return;
        }

        SalesReportConfig config = configStore.getSnapshot();
        SalesAccountConfig account = config.getAccounts().stream()
                .filter(existing -> accountId.equalsIgnoreCase(existing.getId()))
                .findFirst()
                .orElse(null);

        if (account == null) {
            event.reply("No account found for id `%s`.".formatted(accountId))
                    .setEphemeral(true)
                    .queue();
            return;
        }

        String name = event.getOption("name", OptionMapping::getAsString);
        if (name != null && !name.isBlank()) {
            account.setName(name.trim());
        }

        OptionMapping enabledOption = event.getOption("enabled");
        if (enabledOption != null) {
            account.setEnabled(enabledOption.getAsBoolean());
        }

        String username = event.getOption("username", OptionMapping::getAsString);
        String password = event.getOption("password", OptionMapping::getAsString);
        String token = event.getOption("token", OptionMapping::getAsString);
        String baseUrl = event.getOption("base-url", OptionMapping::getAsString);
        String salesUrl = event.getOption("sales-url", OptionMapping::getAsString);

        if (username != null) {
            account.setUsername(username.trim());
        }
        if (password != null) {
            account.setPassword(password.trim());
        }
        if (token != null) {
            account.setToken(token.trim());
        }
        if (baseUrl != null) {
            account.setBaseUrl(baseUrl.trim());
        }
        if (salesUrl != null) {
            account.setSalesPageUrl(salesUrl.trim());
        }

        String validationError = validateAccountByPlatform(account);
        if (validationError != null) {
            event.reply(validationError).setEphemeral(true).queue();
            return;
        }

        configStore.replaceAndPersist(config);
        event.reply("Updated sales account `%s` (%s).".formatted(account.getId(), account.getName()))
                .setEphemeral(true)
                .queue();
    }

    private void handleRemoveAccount(SlashCommandInteractionEvent event) {
        String accountId = event.getOption("account-id", OptionMapping::getAsString);
        if (accountId == null || accountId.isBlank()) {
            event.reply("Missing required option `account-id`.")
                    .setEphemeral(true)
                    .queue();
            return;
        }

        SalesReportConfig config = configStore.getSnapshot();
        int before = config.getAccounts().size();
        config.setAccounts(config.getAccounts().stream()
                .filter(existing -> !accountId.equalsIgnoreCase(existing.getId()))
                .toList());

        if (before == config.getAccounts().size()) {
            event.reply("No account found for id `%s`.".formatted(accountId))
                    .setEphemeral(true)
                    .queue();
            return;
        }

        configStore.replaceAndPersist(config);
        event.reply("Removed sales account `%s`.".formatted(accountId))
                .setEphemeral(true)
                .queue();
    }

    private void handleSetAccountEnabled(SlashCommandInteractionEvent event) {
        String accountId = event.getOption("account-id", OptionMapping::getAsString);
        OptionMapping enabledOption = event.getOption("enabled");
        if (accountId == null || accountId.isBlank() || enabledOption == null) {
            event.reply("Missing required options `account-id` and `enabled`.")
                    .setEphemeral(true)
                    .queue();
            return;
        }

        SalesReportConfig config = configStore.getSnapshot();
        SalesAccountConfig account = config.getAccounts().stream()
                .filter(existing -> accountId.equalsIgnoreCase(existing.getId()))
                .findFirst()
                .orElse(null);

        if (account == null) {
            event.reply("No account found for id `%s`.".formatted(accountId))
                    .setEphemeral(true)
                    .queue();
            return;
        }

        account.setEnabled(enabledOption.getAsBoolean());
        configStore.replaceAndPersist(config);

        event.reply("Account `%s` is now `%s`."
                        .formatted(account.getId(), account.isEnabled() ? "enabled" : "disabled"))
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

        SalesReportConfig config = configStore.getSnapshot();
        if (tone != null && !tone.isBlank()) {
            config.setMessageTone(tone.trim().toLowerCase(Locale.ENGLISH));
        }
        if (signature != null && !signature.isBlank()) {
            config.setSignature(signature.trim());
        }

        configStore.replaceAndPersist(config);
        event.reply("Sales report copy updated. Tone: `%s`, Signature: `%s`."
                        .formatted(config.getMessageTone(), config.getSignature()))
                .setEphemeral(true)
                .queue();
    }

    private List<Command.Choice> buildRunNowAccountChoices(SalesReportConfig config, String rawQuery) {
        if (config == null || config.getAccounts() == null || config.getAccounts().isEmpty()) {
            return List.of();
        }

        String query = rawQuery == null ? "" : rawQuery.trim().toLowerCase(Locale.ENGLISH);
        List<SalesAccountConfig> accounts = config.getAccounts().stream()
                .filter(account -> account != null && account.getId() != null && !account.getId().isBlank())
                .filter(SalesAccountConfig::isEnabled)
                .sorted(Comparator.comparing(
                        account -> account.getName() == null ? "" : account.getName(),
                        String.CASE_INSENSITIVE_ORDER))
                .toList();

        List<Command.Choice> choices = new ArrayList<>();
        for (SalesAccountConfig account : accounts) {
            if (choices.size() >= DISCORD_AUTOCOMPLETE_MAX_CHOICES) {
                break;
            }

            String accountId = account.getId().trim();
            String accountName = (account.getName() == null || account.getName().isBlank())
                    ? accountId
                    : account.getName().trim();
            String platformName = account.resolvePlatform() == null
                    ? "Unknown"
                    : account.resolvePlatform().getDisplayName();
            String display = "%s (%s)".formatted(accountName, platformName);

            String searchable = (accountName + " " + accountId + " " + platformName).toLowerCase(Locale.ENGLISH);
            if (!query.isBlank() && !searchable.contains(query)) {
                continue;
            }

            choices.add(new Command.Choice(display, accountId));
        }
        return choices;
    }

    private LocalTime parseSlot(SlashCommandInteractionEvent event) {
        OptionMapping hourOption = event.getOption("hour");
        OptionMapping minuteOption = event.getOption("minute");
        if (hourOption == null || minuteOption == null) {
            event.reply("Missing required options `hour` and `minute`.")
                    .setEphemeral(true)
                    .queue();
            return null;
        }

        int hour = hourOption.getAsInt();
        int minute = minuteOption.getAsInt();
        if (hour < 0 || hour > 23 || minute < 0 || minute > 59) {
            event.reply("Invalid time. Use hour 0-23 and minute 0-59.")
                    .setEphemeral(true)
                    .queue();
            return null;
        }

        return LocalTime.of(hour, minute);
    }

    private String describeNextOverview(SalesReportConfig config) {
        if (config == null || isBlank(config.getOverviewTime())) {
            return "No daily overview time configured";
        }
        return describeNextScheduledRun(List.of(config.getOverviewTime()), config.getTimezone());
    }

    private String describeNextScheduledRun(List<String> slots, String timezone) {
        if (slots == null || slots.isEmpty()) {
            return "No scheduled times configured";
        }

        try {
            ZoneId zoneId = ZoneId.of(timezone);
            ZonedDateTime now = ZonedDateTime.now(zoneId);
            ZonedDateTime next = null;

            for (String slot : slots) {
                LocalTime time = LocalTime.parse(slot, SLOT_FORMATTER);
                ZonedDateTime candidate = now.withHour(time.getHour()).withMinute(time.getMinute()).withSecond(0).withNano(0);
                if (!candidate.isAfter(now)) {
                    candidate = candidate.plusDays(1);
                }
                if (next == null || candidate.isBefore(next)) {
                    next = candidate;
                }
            }

            return next == null ? "No scheduled times configured" : next.toString();
        } catch (RuntimeException ignored) {
            return "Invalid timezone or schedule";
        }
    }

    private String formatChannelMention(String channelId) {
        if (isBlank(channelId)) {
            return "(not set)";
        }
        return "<#" + channelId.trim() + ">";
    }

    private boolean hasManageServer(Member member) {
        return member != null && member.hasPermission(Permission.MANAGE_SERVER);
    }

    private boolean isValidTimezone(String timezone) {
        try {
            ZoneId.of(timezone);
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    private String validateAccountByPlatform(SalesAccountConfig account) {
        SalesPlatform platform = account.resolvePlatform();
        if (platform == null) {
            return "Invalid account platform.";
        }

        if (account.getId() == null || account.getId().isBlank()) {
            return "Account id is required.";
        }

        if (account.getName() == null || account.getName().isBlank()) {
            return "Account name is required.";
        }

        if (platform == SalesPlatform.UTAK) {
            if (isBlank(account.getUsername()) || isBlank(account.getPassword())) {
                return "UTAK accounts require `username` and `password`.";
            }
            if (isBlank(account.getSalesPageUrl()) && isBlank(account.getBaseUrl())) {
                return "UTAK accounts require `sales-url` or `base-url`.";
            }
        }

        if (platform == SalesPlatform.LOYVERSE) {
            if (isBlank(account.getToken())) {
                return "Loyverse accounts require `token`.";
            }
        }

        return null;
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isBlank();
    }

    private String credentialsSummary(SalesAccountConfig account) {
        SalesPlatform platform = account.resolvePlatform();
        if (platform == SalesPlatform.UTAK) {
            return "username:%s password:%s url:%s"
                    .formatted(maskedFlag(account.getUsername()), maskedFlag(account.getPassword()), presentFlag(account.getSalesPageUrl()));
        }
        if (platform == SalesPlatform.LOYVERSE) {
            return "token:%s endpoint:%s"
                    .formatted(maskedFlag(account.getToken()), presentFlag(account.getBaseUrl()));
        }
        return "unknown";
    }

    private String maskedFlag(String value) {
        return isBlank(value) ? "missing" : "set";
    }

    private String presentFlag(String value) {
        return isBlank(value) ? "default" : "custom";
    }

    private String defaultAccountId(SalesPlatform platform) {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        return platform.name().toLowerCase(Locale.ENGLISH) + "-" + suffix;
    }

    private record DirectRunNowRequest(String accountQuery, boolean malformed) {
    }
}
