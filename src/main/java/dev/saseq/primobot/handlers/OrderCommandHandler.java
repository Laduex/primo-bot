package dev.saseq.primobot.handlers;

import dev.saseq.primobot.commands.PrimoCommands;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.channel.ChannelType;
import net.dv8tion.jda.api.entities.channel.concrete.ForumChannel;
import net.dv8tion.jda.api.entities.channel.middleman.GuildChannel;
import net.dv8tion.jda.api.events.interaction.command.CommandAutoCompleteInteractionEvent;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.interactions.commands.Command;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import net.dv8tion.jda.api.utils.messages.MessageCreateData;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Component
public class OrderCommandHandler {
    private static final DateTimeFormatter ORDER_TITLE_DATE_FORMAT = DateTimeFormatter.ofPattern("MMMM d | EEEE", Locale.ENGLISH);
    private static final String ORDERS_CATEGORY_NAME = "Orders";
    private static final int ORDER_FORUM_TITLE_MAX_LENGTH = 100;
    private static final int DISCORD_MESSAGE_MAX_LENGTH = 2000;
    private static final int DISCORD_AUTOCOMPLETE_MAX_CHOICES = 25;
    private static final long ORDER_REQUEST_TTL_MILLIS = Duration.ofMinutes(10).toMillis();
    private static final Pattern SNOWFLAKE_PATTERN = Pattern.compile("\\d+");
    private final ConcurrentHashMap<String, PendingOrderContext> pendingOrderContexts = new ConcurrentHashMap<>();

    public void handle(SlashCommandInteractionEvent event) {
        var guild = event.getGuild();
        var member = event.getMember();
        if (guild == null || member == null) {
            event.reply("This command can only be used inside a Discord server.").setEphemeral(true).queue();
            return;
        }

        var forumOption = event.getOption(PrimoCommands.ORDER_FORUM_OPTION);
        var customerNameOption = event.getOption(PrimoCommands.ORDER_CUSTOMER_OPTION);

        if (forumOption == null || customerNameOption == null) {
            event.reply("Missing required options. Use `/order forum:<forum> customer:<name>`.")
                    .setEphemeral(true)
                    .queue();
            return;
        }

        ForumChannel forum = resolveForumChannel(forumOption, guild);
        if (forum == null) {
            event.reply("Please select a valid forum channel in the `forum` option.").setEphemeral(true).queue();
            return;
        }
        if (!isOrdersCategoryForum(forum)) {
            event.reply("Please choose a forum under the `%s` category.".formatted(ORDERS_CATEGORY_NAME))
                    .setEphemeral(true)
                    .queue();
            return;
        }

        if (!canMemberCreateForumPost(member, forum)) {
            event.reply("You do not have permission to create posts in %s.".formatted(forum.getAsMention()))
                    .setEphemeral(true)
                    .queue();
            return;
        }

        String customerName = customerNameOption.getAsString().trim();
        if (customerName.isBlank()) {
            event.reply("Customer name cannot be empty.").setEphemeral(true).queue();
            return;
        }

        String titlePrefix = LocalDate.now().format(ORDER_TITLE_DATE_FORMAT);
        String previewTitle = titlePrefix + " | " + customerName;
        if (previewTitle.length() > ORDER_FORUM_TITLE_MAX_LENGTH) {
            int allowedCustomerNameLength = Math.max(1, ORDER_FORUM_TITLE_MAX_LENGTH - (titlePrefix.length() + 3));
            event.reply("Customer name is too long for the title format `Month Day | Weekday | Customer Name`. " +
                            "Use %d characters or fewer."
                            .formatted(allowedCustomerNameLength))
                    .setEphemeral(true).queue();
            return;
        }

        cleanupExpiredPendingOrders();
        String pendingKey = buildPendingKey(guild.getId(), member.getId(), event.getChannelId());
        pendingOrderContexts.put(pendingKey, new PendingOrderContext(
                guild.getId(),
                forum.getId(),
                member.getId(),
                customerName,
                System.currentTimeMillis()
        ));

        event.reply("Forum and customer saved. Send your next message in this channel within 10 minutes " +
                        "and I'll create the order post from it.")
                .setEphemeral(true)
                .queue();
    }

    public void handleForumAutocomplete(CommandAutoCompleteInteractionEvent event) {
        var guild = event.getGuild();
        if (guild == null) {
            event.replyChoices(List.of()).queue();
            return;
        }

        String query = event.getFocusedOption().getValue();
        List<Command.Choice> choices = buildForumAutocompleteChoices(guild, query);
        event.replyChoices(choices).queue();
    }

    public void handleMessage(MessageReceivedEvent event) {
        if (!event.isFromGuild() || event.getAuthor().isBot()) {
            return;
        }

        cleanupExpiredPendingOrders();
        String pendingKey = buildPendingKey(event.getGuild().getId(), event.getAuthor().getId(), event.getChannel().getId());
        PendingOrderContext pendingContext = pendingOrderContexts.get(pendingKey);
        if (pendingContext == null) {
            return;
        }

        if (isExpired(pendingContext)) {
            pendingOrderContexts.remove(pendingKey);
            event.getMessage().reply("Your pending `/order` request expired. Run `/order` again.").queue();
            return;
        }

        var guild = event.getGuild();
        var member = event.getMember();
        if (member == null) {
            pendingOrderContexts.remove(pendingKey);
            return;
        }

        ForumChannel forum = guild.getForumChannelById(pendingContext.forumId());
        if (forum == null) {
            pendingOrderContexts.remove(pendingKey);
            event.getMessage().reply("The selected forum no longer exists. Run `/order` again.").queue();
            return;
        }
        if (!isOrdersCategoryForum(forum)) {
            pendingOrderContexts.remove(pendingKey);
            event.getMessage().reply("The selected forum is no longer under `%s`. Run `/order` again."
                    .formatted(ORDERS_CATEGORY_NAME)).queue();
            return;
        }

        if (!canMemberCreateForumPost(member, forum)) {
            pendingOrderContexts.remove(pendingKey);
            event.getMessage().reply("You do not have permission to create posts in %s."
                    .formatted(forum.getAsMention())).queue();
            return;
        }

        String orderMessage = buildOrderMessage(event.getMessage());
        if (orderMessage.isBlank()) {
            event.getMessage().reply("Order message cannot be empty. Send text or attachment, or run `/order` again.")
                    .queue();
            return;
        }

        String postBody = "Posted by " + member.getAsMention() + "\n" + orderMessage;
        if (postBody.length() > DISCORD_MESSAGE_MAX_LENGTH) {
            event.getMessage().reply("That message is too long after `Posted by @user` (max 2000 chars). " +
                    "Send a shorter message.").queue();
            return;
        }

        String titlePrefix = LocalDate.now().format(ORDER_TITLE_DATE_FORMAT);
        String postTitle = titlePrefix + " | " + pendingContext.customerName();
        if (postTitle.length() > ORDER_FORUM_TITLE_MAX_LENGTH) {
            pendingOrderContexts.remove(pendingKey);
            int allowedCustomerNameLength = Math.max(1, ORDER_FORUM_TITLE_MAX_LENGTH - (titlePrefix.length() + 3));
            event.getMessage().reply("Customer name is too long for today's title format. " +
                    "Run `/order` again and use %d characters or fewer.".formatted(allowedCustomerNameLength)).queue();
            return;
        }

        pendingOrderContexts.remove(pendingKey);
        forum.createForumPost(postTitle, MessageCreateData.fromContent(postBody))
                .queue(
                        forumPost -> event.getMessage().reply("All set. Order created in %s: %s"
                                        .formatted(forum.getAsMention(), forumPost.getThreadChannel().getAsMention()))
                                .queue(),
                        failure -> {
                            pendingOrderContexts.putIfAbsent(pendingKey, pendingContext);
                            event.getMessage().reply("Failed to create forum post: " + failure.getMessage()).queue();
                        }
                );
    }

    private String buildOrderMessage(Message message) {
        String content = message.getContentRaw().trim();
        String attachmentUrls = message.getAttachments().stream()
                .map(Message.Attachment::getUrl)
                .collect(Collectors.joining("\n"));

        if (attachmentUrls.isBlank()) {
            return content;
        }
        if (content.isBlank()) {
            return "Attachments:\n" + attachmentUrls;
        }
        return content + "\n\nAttachments:\n" + attachmentUrls;
    }

    private String buildPendingKey(String guildId, String authorId, String channelId) {
        return guildId + ":" + authorId + ":" + channelId;
    }

    private void cleanupExpiredPendingOrders() {
        pendingOrderContexts.entrySet().removeIf(entry -> isExpired(entry.getValue()));
    }

    private boolean isExpired(PendingOrderContext context) {
        return System.currentTimeMillis() - context.createdAtEpochMillis() > ORDER_REQUEST_TTL_MILLIS;
    }

    private List<Command.Choice> buildForumAutocompleteChoices(net.dv8tion.jda.api.entities.Guild guild, String rawQuery) {
        String query = rawQuery == null ? "" : rawQuery.trim().toLowerCase(Locale.ENGLISH);
        List<ForumChannel> forums = guild.getForumChannels().stream()
                .filter(this::isOrdersCategoryForum)
                .sorted(Comparator.comparing(ForumChannel::getName, String.CASE_INSENSITIVE_ORDER))
                .toList();

        List<Command.Choice> choices = new ArrayList<>();
        for (ForumChannel forum : forums) {
            if (choices.size() >= DISCORD_AUTOCOMPLETE_MAX_CHOICES) {
                break;
            }
            if (!query.isEmpty() && !forum.getName().toLowerCase(Locale.ENGLISH).contains(query)) {
                continue;
            }
            choices.add(new Command.Choice(forum.getName(), forum.getId()));
        }
        return choices;
    }

    private boolean isOrdersCategoryForum(ForumChannel forum) {
        return forum != null
                && forum.getParentCategory() != null
                && ORDERS_CATEGORY_NAME.equalsIgnoreCase(forum.getParentCategory().getName());
    }

    private ForumChannel resolveForumChannel(OptionMapping forumOption, net.dv8tion.jda.api.entities.Guild guild) {
        if (forumOption == null) {
            return null;
        }

        try {
            GuildChannel channel = forumOption.getAsChannel();
            if (channel.getType() == ChannelType.FORUM) {
                return (ForumChannel) channel;
            }
        } catch (RuntimeException ignored) {
            // Some payloads can omit resolved channel objects; fallback to id lookup below.
        }

        if (guild == null) {
            return null;
        }

        String channelId = extractChannelId(forumOption);
        if (channelId == null) {
            return null;
        }

        try {
            return guild.getForumChannelById(channelId);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private String extractChannelId(OptionMapping forumOption) {
        if (forumOption == null) {
            return null;
        }

        String raw = forumOption.getAsString();
        if (raw == null || raw.isBlank()) {
            return null;
        }

        String trimmed = raw.trim();
        if (trimmed.chars().allMatch(Character::isDigit)) {
            return trimmed;
        }

        Matcher matcher = SNOWFLAKE_PATTERN.matcher(trimmed);
        if (matcher.find()) {
            return matcher.group();
        }
        return null;
    }

    private boolean canMemberCreateForumPost(Member member, ForumChannel channel) {
        if (member == null) {
            return false;
        }
        boolean hasVisibility = member.hasPermission(channel, Permission.VIEW_CHANNEL);
        boolean canSend = member.hasPermission(channel, Permission.MESSAGE_SEND);
        return hasVisibility && canSend;
    }

    private record PendingOrderContext(
            String guildId,
            String forumId,
            String authorId,
            String customerName,
            long createdAtEpochMillis
    ) {
    }
}
