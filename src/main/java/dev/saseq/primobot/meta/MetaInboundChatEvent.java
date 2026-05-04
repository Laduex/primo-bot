package dev.saseq.primobot.meta;

public record MetaInboundChatEvent(String platform,
                                   String pageOrAccountId,
                                   String senderId,
                                   String messageId,
                                   String previewText,
                                   long timestampMs) {
}
