package dev.saseq.primobot.handlers;

import dev.saseq.primobot.commands.PrimoCommands;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.channel.ChannelType;
import net.dv8tion.jda.api.entities.channel.concrete.ForumChannel;
import net.dv8tion.jda.api.entities.channel.middleman.GuildChannel;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.interaction.ModalInteractionEvent;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import net.dv8tion.jda.api.interactions.modals.Modal;
import net.dv8tion.jda.api.interactions.components.text.TextInput;
import net.dv8tion.jda.api.interactions.components.text.TextInputStyle;
import net.dv8tion.jda.api.utils.messages.MessageCreateData;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class OrderCommandHandler {
    private static final String ORDER_MODAL_PREFIX = "order:create:";
    private static final String ORDER_MODAL_MESSAGE_INPUT_ID = "message";
    private static final DateTimeFormatter ORDER_TITLE_DATE_FORMAT = DateTimeFormatter.ofPattern("MMMM d | EEEE", Locale.ENGLISH);
    private static final int ORDER_FORUM_TITLE_MAX_LENGTH = 100;
    private static final int DISCORD_MESSAGE_MAX_LENGTH = 2000;
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
        var customerNameOption = event.getOption(PrimoCommands.ORDER_CUSTOMER_NAME_OPTION);

        if (forumOption == null || customerNameOption == null) {
            event.reply("Missing required options. Use `/order forum:<forum> customer_name:<name>`.")
                    .setEphemeral(true)
                    .queue();
            return;
        }

        ForumChannel forum = resolveForumChannel(forumOption, guild);
        if (forum == null) {
            event.reply("Please select a valid forum channel in the `forum` option.").setEphemeral(true).queue();
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
            event.reply("`customer_name` cannot be empty.").setEphemeral(true).queue();
            return;
        }
        String titlePrefix = LocalDate.now().format(ORDER_TITLE_DATE_FORMAT);
        String previewTitle = titlePrefix + " | " + customerName;
        if (previewTitle.length() > ORDER_FORUM_TITLE_MAX_LENGTH) {
            int allowedCustomerNameLength = Math.max(1, ORDER_FORUM_TITLE_MAX_LENGTH - (titlePrefix.length() + 3));
            event.reply("`customer_name` is too long for the title format `Month Day | Weekday | Customer Name`. " +
                            "Use %d characters or fewer."
                            .formatted(allowedCustomerNameLength))
                    .setEphemeral(true).queue();
            return;
        }

        String contextId = UUID.randomUUID().toString();
        pendingOrderContexts.put(contextId, new PendingOrderContext(
                guild.getId(),
                forum.getId(),
                member.getId(),
                customerName
        ));

        TextInput messageInput = TextInput.create(
                        ORDER_MODAL_MESSAGE_INPUT_ID,
                        "Order Message",
                        TextInputStyle.PARAGRAPH
                )
                .setRequired(true)
                .setPlaceholder("Write the order details. Discord markdown is supported.")
                .setMaxLength(DISCORD_MESSAGE_MAX_LENGTH)
                .build();

        Modal modal = Modal.create(ORDER_MODAL_PREFIX + contextId, "Create Order")
                .addActionRow(messageInput)
                .build();
        event.replyModal(modal).queue();
    }

    public void handleModalSubmit(ModalInteractionEvent event) {
        String modalId = event.getModalId();
        if (modalId == null || !modalId.startsWith(ORDER_MODAL_PREFIX)) {
            return;
        }

        String contextId = modalId.substring(ORDER_MODAL_PREFIX.length());
        PendingOrderContext pendingContext = pendingOrderContexts.remove(contextId);
        if (pendingContext == null) {
            event.reply("This order form has expired. Please run `/order` again.")
                    .setEphemeral(true)
                    .queue();
            return;
        }

        var guild = event.getGuild();
        var member = event.getMember();
        if (guild == null || member == null) {
            event.reply("This command can only be used inside a Discord server.")
                    .setEphemeral(true)
                    .queue();
            return;
        }

        if (!guild.getId().equals(pendingContext.guildId())) {
            event.reply("This order form belongs to a different server. Please run `/order` again.")
                    .setEphemeral(true)
                    .queue();
            return;
        }

        if (!member.getId().equals(pendingContext.authorId())) {
            event.reply("Only the user who opened this order form can submit it.")
                    .setEphemeral(true)
                    .queue();
            return;
        }

        ForumChannel forum = guild.getForumChannelById(pendingContext.forumId());
        if (forum == null) {
            event.reply("The selected forum no longer exists. Please run `/order` again.")
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

        var messageValue = event.getValue(ORDER_MODAL_MESSAGE_INPUT_ID);
        String orderMessage = messageValue == null ? "" : messageValue.getAsString().trim();
        if (orderMessage.isBlank()) {
            event.reply("`message` cannot be empty.").setEphemeral(true).queue();
            return;
        }

        String postBody = "Posted by " + member.getAsMention() + "\n" + orderMessage;
        if (postBody.length() > DISCORD_MESSAGE_MAX_LENGTH) {
            event.reply("`message` is too long after adding `Posted by @user`. Discord allows up to %d characters."
                            .formatted(DISCORD_MESSAGE_MAX_LENGTH))
                    .setEphemeral(true)
                    .queue();
            return;
        }

        String titlePrefix = LocalDate.now().format(ORDER_TITLE_DATE_FORMAT);
        String postTitle = titlePrefix + " | " + pendingContext.customerName();
        if (postTitle.length() > ORDER_FORUM_TITLE_MAX_LENGTH) {
            int allowedCustomerNameLength = Math.max(1, ORDER_FORUM_TITLE_MAX_LENGTH - (titlePrefix.length() + 3));
            event.reply("`customer_name` is too long for today's title format. Use %d characters or fewer."
                            .formatted(allowedCustomerNameLength))
                    .setEphemeral(true)
                    .queue();
            return;
        }

        forum.createForumPost(postTitle, MessageCreateData.fromContent(postBody))
                .queue(
                        forumPost -> event.reply("All set. Order created in %s: %s"
                                        .formatted(forum.getAsMention(), forumPost.getThreadChannel().getAsMention()))
                                .setEphemeral(true)
                                .queue(),
                        failure -> event.reply("Failed to create forum post: " + failure.getMessage())
                                .setEphemeral(true)
                                .queue()
                );
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
            // Some autocomplete payloads can omit resolved channel objects; fallback to id lookup below.
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
            String customerName
    ) {
    }
}
