package dev.saseq.primobot.meta;

public record MetaUnreadConversation(String pageId,
                                     String pageName,
                                     String platform,
                                     String conversationId,
                                     String senderName,
                                     String snippet,
                                     int unreadCount,
                                     String updatedTime) {
}
