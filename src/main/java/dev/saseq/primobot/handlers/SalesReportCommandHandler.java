package dev.saseq.primobot.handlers;

import dev.saseq.primobot.sales.SalesAccountConfig;
import dev.saseq.primobot.sales.SalesPlatform;
import dev.saseq.primobot.sales.SalesReportConfig;
import dev.saseq.primobot.sales.SalesReportConfigStore;
import dev.saseq.primobot.sales.SalesReportExecutorService;
import dev.saseq.primobot.sales.SalesReportSchedulerService;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.channel.ChannelType;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import org.springframework.stereotype.Component;

import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

@Component
public class SalesReportCommandHandler {
    private static final Pattern SNOWFLAKE_PATTERN = Pattern.compile("\\d+");
    private static final DateTimeFormatter SLOT_FORMATTER = DateTimeFormatter.ofPattern("HH:mm");

    private final SalesReportConfigStore configStore;
    private final SalesReportSchedulerService schedulerService;

    public SalesReportCommandHandler(SalesReportConfigStore configStore,
                                     SalesReportSchedulerService schedulerService) {
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
            event.reply("You need Manage Server permission to use `/sales-report`.")
                    .setEphemeral(true)
                    .queue();
            return;
        }

        String subcommand = event.getSubcommandName();
        if (subcommand == null || subcommand.isBlank()) {
            event.reply("Please choose a subcommand for `/sales-report`.")
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

    private void handleStatus(SlashCommandInteractionEvent event) {
        SalesReportConfig config = configStore.getSnapshot();
        String timesText = config.getTimes().isEmpty() ? "(none)" : String.join(", ", config.getTimes());
        String targetChannel = config.getTargetChannelId().isBlank() ? "(not set)" : "<#" + config.getTargetChannelId() + ">";

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
                Times: `%s`
                Target Channel: %s
                Tone: `%s`
                Signature: `%s`
                Next Run: `%s`

                **Accounts**
                %s
                """.formatted(
                config.isEnabled(),
                config.getTimezone(),
                timesText,
                targetChannel,
                config.getMessageTone(),
                config.getSignature(),
                describeNextRun(config),
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
            event.reply("Time `%s` is already in the schedule.".formatted(formatted))
                    .setEphemeral(true)
                    .queue();
            return;
        }

        List<String> updated = new ArrayList<>(config.getTimes());
        updated.add(formatted);
        updated.sort(String::compareTo);
        config.setTimes(updated);
        configStore.replaceAndPersist(config);

        event.reply("Added `%s` to sales report schedule.".formatted(formatted))
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
            event.reply("Time `%s` is not in the schedule.".formatted(formatted))
                    .setEphemeral(true)
                    .queue();
            return;
        }

        config.setTimes(config.getTimes().stream()
                .filter(existing -> !formatted.equals(existing))
                .toList());
        config.getLastRunDateBySlot().remove(formatted);
        configStore.replaceAndPersist(config);

        event.reply("Removed `%s` from sales report schedule.".formatted(formatted))
                .setEphemeral(true)
                .queue();
    }

    private void handleClearTimes(SlashCommandInteractionEvent event) {
        OptionMapping confirmOption = event.getOption("confirm");
        if (confirmOption == null || !confirmOption.getAsBoolean()) {
            event.reply("Set `confirm:true` to clear all scheduled times.")
                    .setEphemeral(true)
                    .queue();
            return;
        }

        SalesReportConfig config = configStore.getSnapshot();
        config.setTimes(new ArrayList<>());
        config.setLastRunDateBySlot(new java.util.LinkedHashMap<>());
        configStore.replaceAndPersist(config);

        event.reply("Cleared all sales report schedule times.")
                .setEphemeral(true)
                .queue();
    }

    private void handleSetChannel(SlashCommandInteractionEvent event) {
        OptionMapping targetOption = event.getOption("target");
        if (targetOption == null || targetOption.getType() != OptionType.CHANNEL
                || targetOption.getAsChannel().getType() != ChannelType.TEXT) {
            event.reply("`target` must be a text channel.")
                    .setEphemeral(true)
                    .queue();
            return;
        }

        TextChannel target = targetOption.getAsChannel().asTextChannel();
        SalesReportConfig config = configStore.getSnapshot();
        config.setTargetChannelId(target.getId());
        configStore.replaceAndPersist(config);

        event.reply("Sales report channel set to %s.".formatted(target.getAsMention()))
                .setEphemeral(true)
                .queue();
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

        String scope = event.getOption("scope", "all", OptionMapping::getAsString).trim().toLowerCase(Locale.ENGLISH);
        String selectedAccountId = "";
        if ("single".equals(scope)) {
            selectedAccountId = event.getOption("account-id", "", OptionMapping::getAsString).trim();
            if (selectedAccountId.isBlank()) {
                event.reply("`account-id` is required when `scope` is Single Account.")
                        .setEphemeral(true)
                        .queue();
                return;
            }
        }

        SalesReportExecutorService.DispatchResult result = schedulerService.runNow(event.getGuild(), overrideTargetId, selectedAccountId);
        switch (result.status()) {
            case SENT -> event.reply("Sales report sent to <#%s>. Success: `%d`, Failed: `%d`."
                            .formatted(result.targetChannelId(), result.successCount(), result.failureCount()))
                    .setEphemeral(true)
                    .queue();
            case TARGET_NOT_CONFIGURED -> event.reply("No target channel configured. Run `/sales-report set-channel` or pass `target` in `/sales-report run-now`.")
                    .setEphemeral(true)
                    .queue();
            case TARGET_NOT_FOUND -> event.reply("Target channel `<#%s>` was not found.".formatted(result.targetChannelId()))
                    .setEphemeral(true)
                    .queue();
            case ACCOUNT_NOT_FOUND -> event.reply("No account found for id `%s`.".formatted(result.accountId()))
                    .setEphemeral(true)
                    .queue();
            case GUILD_NOT_FOUND, SEND_FAILED -> event.reply("Failed to send sales report: " + result.message())
                    .setEphemeral(true)
                    .queue();
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

    private String describeNextRun(SalesReportConfig config) {
        if (config.getTimes() == null || config.getTimes().isEmpty()) {
            return "No scheduled times configured";
        }

        try {
            ZoneId zoneId = ZoneId.of(config.getTimezone());
            ZonedDateTime now = ZonedDateTime.now(zoneId);
            ZonedDateTime next = null;

            for (String slot : config.getTimes()) {
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
}
