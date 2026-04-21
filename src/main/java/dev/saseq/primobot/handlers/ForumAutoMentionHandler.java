package dev.saseq.primobot.handlers;

import net.dv8tion.jda.api.entities.channel.concrete.ForumChannel;
import net.dv8tion.jda.api.entities.channel.concrete.ThreadChannel;
import net.dv8tion.jda.api.events.channel.ChannelCreateEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Component
public class ForumAutoMentionHandler {
    private static final Logger LOG = LoggerFactory.getLogger(ForumAutoMentionHandler.class);
    private static final Pattern SNOWFLAKE_PATTERN = Pattern.compile("\\d+");
    private static final String ORDERS_CATEGORY_NAME = "Orders";
    private static final String FOLLOW_HINT = "New Order! Acknowledge and follow the post for updates.";
    private static final int HISTORY_LOOKBACK_COUNT = 15;

    private final Map<String, List<String>> forumRoleTargets;
    private final Set<String> processedThreadIds = ConcurrentHashMap.newKeySet();

    public ForumAutoMentionHandler(@Value("${FORUM_AUTO_MENTION_TARGETS:}") String rawForumRoleTargets) {
        this.forumRoleTargets = parseForumRoleTargets(rawForumRoleTargets);
        if (forumRoleTargets.isEmpty()) {
            LOG.info("Forum auto-mention is disabled. Set FORUM_AUTO_MENTION_TARGETS to enable it.");
        } else {
            LOG.info("Forum auto-mention enabled for {} forum(s).", forumRoleTargets.size());
        }
    }

    public void handleChannelCreate(ChannelCreateEvent event) {
        if (forumRoleTargets.isEmpty()) {
            return;
        }

        if (!(event.getChannel() instanceof ThreadChannel thread)) {
            return;
        }

        if (event.getJDA().getSelfUser().getIdLong() == thread.getOwnerIdLong()) {
            return;
        }

        if (!(thread.getParentChannel() instanceof ForumChannel forumChannel)) {
            return;
        }
        if (!isOrdersCategoryForum(forumChannel)) {
            return;
        }

        List<String> roleIds = forumRoleTargets.get(forumChannel.getId());
        if (roleIds == null || roleIds.isEmpty()) {
            return;
        }

        if (!processedThreadIds.add(thread.getId())) {
            return;
        }

        String mentions = roleIds.stream()
                .map(roleId -> "<@&" + roleId + ">")
                .collect(Collectors.joining(" "));
        String content = mentions + "\n" + FOLLOW_HINT;
        String selfUserId = event.getJDA().getSelfUser().getId();

        thread.getHistory().retrievePast(HISTORY_LOOKBACK_COUNT).queue(
                messages -> {
                    boolean alreadyMentioned = messages.stream()
                            .anyMatch(message -> selfUserId.equals(message.getAuthor().getId())
                                    && message.getContentRaw().contains(FOLLOW_HINT));
                    if (alreadyMentioned) {
                        LOG.info("Skipped forum auto-mention in thread {} because hint already exists.", thread.getId());
                        return;
                    }
                    sendAutoMention(thread, forumChannel, content);
                },
                failure -> {
                    LOG.warn("Could not inspect thread {} history before auto-mention: {}",
                            thread.getId(), failure.getMessage());
                    sendAutoMention(thread, forumChannel, content);
                }
        );
    }

    private void sendAutoMention(ThreadChannel thread, ForumChannel forumChannel, String content) {
        thread.sendMessage(content).queue(
                ignored -> LOG.info("Posted forum auto-mention in thread {} (forum {}).", thread.getId(), forumChannel.getId()),
                failure -> {
                    processedThreadIds.remove(thread.getId());
                    LOG.warn("Failed to post forum auto-mention in thread {}: {}", thread.getId(), failure.getMessage());
                }
        );
    }

    private Map<String, List<String>> parseForumRoleTargets(String rawForumRoleTargets) {
        if (rawForumRoleTargets == null || rawForumRoleTargets.isBlank()) {
            return Map.of();
        }

        Map<String, List<String>> parsedTargets = new LinkedHashMap<>();
        String[] forumEntries = rawForumRoleTargets.split(";");
        for (String rawEntry : forumEntries) {
            String entry = rawEntry == null ? "" : rawEntry.trim();
            if (entry.isEmpty()) {
                continue;
            }

            String[] forumAndRoles = entry.split(":", 2);
            if (forumAndRoles.length != 2) {
                LOG.warn("Ignoring invalid FORUM_AUTO_MENTION_TARGETS entry '{}'. Expected forumId:roleId,roleId.", entry);
                continue;
            }

            String forumId = forumAndRoles[0].trim();
            if (!isSnowflake(forumId)) {
                LOG.warn("Ignoring forum target with invalid forum ID '{}'.", forumId);
                continue;
            }

            List<String> roleIds = new ArrayList<>();
            String[] rawRoleIds = forumAndRoles[1].split(",");
            for (String rawRoleId : rawRoleIds) {
                String roleId = rawRoleId == null ? "" : rawRoleId.trim();
                if (roleId.isEmpty()) {
                    continue;
                }
                if (!isSnowflake(roleId)) {
                    LOG.warn("Ignoring invalid role ID '{}' in forum target '{}'.", roleId, forumId);
                    continue;
                }
                if (!roleIds.contains(roleId)) {
                    roleIds.add(roleId);
                }
            }

            if (roleIds.isEmpty()) {
                LOG.warn("Ignoring forum target '{}' because it has no valid role IDs.", forumId);
                continue;
            }

            parsedTargets.put(forumId, List.copyOf(roleIds));
        }

        if (parsedTargets.isEmpty()) {
            return Map.of();
        }
        return Collections.unmodifiableMap(parsedTargets);
    }

    private boolean isOrdersCategoryForum(ForumChannel forumChannel) {
        return forumChannel != null
                && forumChannel.getParentCategory() != null
                && ORDERS_CATEGORY_NAME.equalsIgnoreCase(forumChannel.getParentCategory().getName());
    }

    private boolean isSnowflake(String value) {
        return value != null && SNOWFLAKE_PATTERN.matcher(value).matches();
    }
}
