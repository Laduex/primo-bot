package dev.saseq.primobot.meta;

import java.util.List;

public record MetaUnreadSnapshot(int pagesScanned,
                                 int unreadThreadCount,
                                 int unreadMessageCount,
                                 List<MetaUnreadConversation> conversations,
                                 List<String> warnings) {
}
