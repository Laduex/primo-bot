package dev.saseq.primobot.meta;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@Component
public class MetaUnreadCollectorService {
    private final MetaUnreadApiClient apiClient;

    public MetaUnreadCollectorService(MetaUnreadApiClient apiClient) {
        this.apiClient = apiClient;
    }

    public MetaUnreadSnapshot collectUnread() {
        List<MetaPageAccess> pages = apiClient.listPages();
        List<MetaUnreadConversation> allConversations = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        for (MetaPageAccess page : pages) {
            allConversations.addAll(apiClient.listUnreadConversations(page, "facebook"));

            try {
                allConversations.addAll(apiClient.listUnreadConversations(page, "instagram"));
            } catch (MetaGraphApiException ex) {
                warnings.add("Instagram unread check failed for `%s`: %s"
                        .formatted(page.pageName(), ex.briefMessage()));
            }
        }

        allConversations.sort(Comparator
                .comparing(MetaUnreadConversation::updatedTime,
                        Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER))
                .reversed()
                .thenComparing(MetaUnreadConversation::pageName, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(MetaUnreadConversation::conversationId, String.CASE_INSENSITIVE_ORDER));

        int unreadMessages = allConversations.stream()
                .mapToInt(conversation -> Math.max(0, conversation.unreadCount()))
                .sum();

        return new MetaUnreadSnapshot(
                pages.size(),
                allConversations.size(),
                unreadMessages,
                List.copyOf(allConversations),
                List.copyOf(warnings)
        );
    }
}
