package dev.saseq.primobot.handlers;

import dev.saseq.primobot.commands.PrimoCommands;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.channel.ChannelType;
import net.dv8tion.jda.api.entities.channel.concrete.ForumChannel;
import net.dv8tion.jda.api.entities.channel.forums.ForumTag;
import net.dv8tion.jda.api.entities.channel.middleman.GuildChannel;
import net.dv8tion.jda.api.events.interaction.command.CommandAutoCompleteInteractionEvent;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.commands.Command;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import net.dv8tion.jda.api.utils.messages.MessageCreateData;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Component
public class OrderCommandHandler {
    private static final DateTimeFormatter ORDER_TITLE_FORMAT = DateTimeFormatter.ofPattern("MMMM d | EEEE", Locale.ENGLISH);
    private static final int MAX_FORUM_TAGS_PER_POST = 5;
    private static final int DISCORD_MESSAGE_MAX_LENGTH = 2000;
    private static final int DISCORD_AUTOCOMPLETE_MAX_CHOICES = 25;
    private static final int DISCORD_CHOICE_VALUE_MAX_LENGTH = 100;
    private static final Pattern SNOWFLAKE_PATTERN = Pattern.compile("\\d+");

    public void handle(SlashCommandInteractionEvent event) {
        var guild = event.getGuild();
        var member = event.getMember();
        if (guild == null || member == null) {
            event.reply("This command can only be used inside a Discord server.").setEphemeral(true).queue();
            return;
        }

        var forumOption = event.getOption(PrimoCommands.ORDER_FORUM_OPTION);
        var messageOption = event.getOption(PrimoCommands.ORDER_MESSAGE_OPTION);
        var tagsOption = event.getOption(PrimoCommands.ORDER_TAGS_OPTION);

        if (forumOption == null || tagsOption == null || messageOption == null) {
            event.reply("Missing required options. Use `/order forum:<forum> tags:<tag1, tag2> message:<order text>`.")
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

        String orderMessage = messageOption.getAsString().trim();
        if (orderMessage.isBlank()) {
            event.reply("`message` cannot be empty.").setEphemeral(true).queue();
            return;
        }

        String postBody = member.getAsMention() + "\n" + orderMessage;
        if (postBody.length() > DISCORD_MESSAGE_MAX_LENGTH) {
            event.reply("`message` is too long after adding your @mention. Discord allows up to %d characters."
                            .formatted(DISCORD_MESSAGE_MAX_LENGTH))
                    .setEphemeral(true)
                    .queue();
            return;
        }

        String tagsRaw = tagsOption.getAsString();
        List<ForumTag> selectedTags = resolveForumTags(forum, tagsRaw);
        if (selectedTags == null) {
            event.reply(buildTagUsageError(forum, tagsRaw)).setEphemeral(true).queue();
            return;
        }

        String postTitle = LocalDate.now().format(ORDER_TITLE_FORMAT);
        var action = forum.createForumPost(postTitle, MessageCreateData.fromContent(postBody));
        if (!selectedTags.isEmpty()) {
            action = action.setTags(selectedTags);
        }

        action.queue(
                forumPost -> event.reply("All set. Your order has been posted in %s with the title **%s**: %s."
                                .formatted(forum.getAsMention(), postTitle, forumPost.getThreadChannel().getAsMention()))
                        .setEphemeral(true)
                        .queue(),
                failure -> event.reply("Failed to create forum post: " + failure.getMessage())
                        .setEphemeral(true)
                        .queue()
        );
    }

    public void handleAutocomplete(CommandAutoCompleteInteractionEvent event) {
        OptionMapping forumOption = event.getOption(PrimoCommands.ORDER_FORUM_OPTION);
        ForumChannel forum = resolveForumChannel(forumOption, event.getGuild());
        if (forum == null) {
            event.replyChoices(List.of()).queue();
            return;
        }

        List<Command.Choice> choices = buildTagAutocompleteChoices(forum, event.getFocusedOption().getValue());
        event.replyChoices(choices).queue();
    }

    private List<ForumTag> resolveForumTags(ForumChannel forum, String tagsRaw) {
        List<ForumTag> availableTags = forum.getAvailableTags();
        if (availableTags.isEmpty()) {
            return null;
        }

        List<String> requestedTokens = parseTagTokens(tagsRaw);
        if (requestedTokens.isEmpty()) {
            return null;
        }
        if (requestedTokens.size() > MAX_FORUM_TAGS_PER_POST) {
            return null;
        }

        Map<String, ForumTag> byId = availableTags.stream()
                .collect(Collectors.toMap(ForumTag::getId, tag -> tag));

        Map<String, ForumTag> byLowerName = new LinkedHashMap<>();
        for (ForumTag tag : availableTags) {
            byLowerName.putIfAbsent(tag.getName().toLowerCase(Locale.ENGLISH), tag);
        }

        Set<String> selectedIds = new LinkedHashSet<>();
        for (String token : requestedTokens) {
            ForumTag byExactId = byId.get(token);
            if (byExactId != null) {
                selectedIds.add(byExactId.getId());
                continue;
            }

            ForumTag byName = byLowerName.get(token.toLowerCase(Locale.ENGLISH));
            if (byName == null) {
                return null;
            }
            selectedIds.add(byName.getId());
        }

        if (selectedIds.isEmpty()) {
            return null;
        }

        return availableTags.stream()
                .filter(tag -> selectedIds.contains(tag.getId()))
                .toList();
    }

    private List<String> parseTagTokens(String tagsRaw) {
        if (tagsRaw == null || tagsRaw.isBlank()) {
            return List.of();
        }

        List<String> tokens = new ArrayList<>();
        for (String piece : tagsRaw.split(",")) {
            String token = piece.trim();
            if (!token.isEmpty()) {
                tokens.add(token);
            }
        }
        return tokens;
    }

    private List<Command.Choice> buildTagAutocompleteChoices(ForumChannel forum, String tagsRawInput) {
        List<ForumTag> availableTags = forum.getAvailableTags();
        if (availableTags.isEmpty()) {
            return List.of();
        }

        String raw = tagsRawInput == null ? "" : tagsRawInput;
        boolean endsWithComma = raw.stripTrailing().endsWith(",");
        List<String> enteredTokens = parseTagTokens(raw);
        List<String> lockedTokens = new ArrayList<>(enteredTokens);
        String currentQuery = "";

        if (!endsWithComma && !enteredTokens.isEmpty()) {
            currentQuery = enteredTokens.get(enteredTokens.size() - 1);
            lockedTokens.remove(lockedTokens.size() - 1);
        }

        if (lockedTokens.size() >= MAX_FORUM_TAGS_PER_POST) {
            return List.of();
        }

        Set<String> selectedLowerNames = lockedTokens.stream()
                .map(token -> token.toLowerCase(Locale.ENGLISH))
                .collect(Collectors.toSet());

        String lowerQuery = currentQuery.toLowerCase(Locale.ENGLISH);
        List<Command.Choice> choices = new ArrayList<>();
        for (ForumTag tag : availableTags) {
            if (choices.size() >= DISCORD_AUTOCOMPLETE_MAX_CHOICES) {
                break;
            }

            String lowerName = tag.getName().toLowerCase(Locale.ENGLISH);
            String lowerId = tag.getId().toLowerCase(Locale.ENGLISH);
            if (selectedLowerNames.contains(lowerName) || selectedLowerNames.contains(lowerId)) {
                continue;
            }
            if (!lowerQuery.isEmpty() && !lowerName.contains(lowerQuery) && !lowerId.startsWith(lowerQuery)) {
                continue;
            }

            boolean canAddMoreTags = lockedTokens.size() + 1 < MAX_FORUM_TAGS_PER_POST;
            String candidateValue = appendToken(lockedTokens, tag.getName(), canAddMoreTags);
            if (candidateValue.length() > DISCORD_CHOICE_VALUE_MAX_LENGTH) {
                continue;
            }

            choices.add(new Command.Choice(tag.getName(), candidateValue));
        }

        return choices;
    }

    private String appendToken(List<String> existingTokens, String newToken, boolean addTrailingDelimiter) {
        String combined;
        if (existingTokens.isEmpty()) {
            combined = newToken;
        } else {
            combined = String.join(", ", existingTokens) + ", " + newToken;
        }

        if (addTrailingDelimiter) {
            return combined + ", ";
        }
        return combined;
    }

    private String buildTagUsageError(ForumChannel forum, String tagsRaw) {
        List<ForumTag> availableTags = forum.getAvailableTags();
        if (availableTags.isEmpty()) {
            return "This forum has no available tags. Please choose a different forum or configure tags first.";
        }

        String availableTagNames = availableTags.stream()
                .map(ForumTag::getName)
                .collect(Collectors.joining(", "));

        if (tagsRaw == null || tagsRaw.isBlank()) {
            return "Please select at least 1 tag in `tags` (comma-separated, max %d). Available tags in %s: %s"
                    .formatted(MAX_FORUM_TAGS_PER_POST, forum.getAsMention(), availableTagNames);
        }

        return "Invalid `tags` value. Use comma-separated tag names or tag IDs (max %d). Available tags in %s: %s"
                .formatted(MAX_FORUM_TAGS_PER_POST, forum.getAsMention(), availableTagNames);
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
}
