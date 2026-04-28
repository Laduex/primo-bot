package dev.saseq.primobot.meta;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class MetaWebhookEventExtractor {

    public List<MetaInboundChatEvent> extractInboundChatEvents(JsonNode payload) {
        if (payload == null || payload.isMissingNode() || payload.isNull()) {
            return List.of();
        }

        String objectType = safeLower(payload.path("object").asText(""));
        if (!"page".equals(objectType) && !"instagram".equals(objectType)) {
            return List.of();
        }

        String platform = "instagram".equals(objectType) ? "instagram" : "messenger";
        Map<String, MetaInboundChatEvent> deduped = new LinkedHashMap<>();

        for (JsonNode entryNode : iterable(payload.path("entry"))) {
            String entryId = safeTrim(entryNode.path("id").asText(""));
            extractFromMessagingArray(entryNode.path("messaging"), platform, entryId, deduped);
            extractFromChangesArray(entryNode.path("changes"), platform, entryId, deduped);
        }

        return List.copyOf(deduped.values());
    }

    private void extractFromMessagingArray(JsonNode messagingArray,
                                           String platform,
                                           String entryId,
                                           Map<String, MetaInboundChatEvent> deduped) {
        for (JsonNode eventNode : iterable(messagingArray)) {
            JsonNode messageNode = eventNode.path("message");
            if (!messageNode.isObject()) {
                continue;
            }
            addInboundEvent(platform, entryId, eventNode.path("sender"), eventNode.path("recipient"),
                    messageNode, eventNode.path("timestamp").asLong(0L), deduped);
        }
    }

    private void extractFromChangesArray(JsonNode changesArray,
                                         String platform,
                                         String entryId,
                                         Map<String, MetaInboundChatEvent> deduped) {
        for (JsonNode changeNode : iterable(changesArray)) {
            String field = safeLower(changeNode.path("field").asText(""));
            if (!field.contains("message")) {
                continue;
            }

            JsonNode valueNode = changeNode.path("value");
            if (!valueNode.isObject()) {
                continue;
            }

            JsonNode messageNode = valueNode.path("message");
            if (!messageNode.isObject()) {
                JsonNode messages = valueNode.path("messages");
                if (messages.isArray() && !messages.isEmpty()) {
                    messageNode = messages.get(0);
                }
            }
            if (!messageNode.isObject()) {
                continue;
            }

            String senderId = safeTrim(valueNode.path("sender").path("id").asText(""));
            if (senderId.isBlank()) {
                senderId = safeTrim(valueNode.path("from").asText(""));
            }

            String recipientId = safeTrim(valueNode.path("recipient").path("id").asText(""));
            if (recipientId.isBlank()) {
                recipientId = safeTrim(valueNode.path("to").asText(""));
            }

            long timestampMs = valueNode.path("timestamp").asLong(0L);
            if (timestampMs <= 0L) {
                timestampMs = valueNode.path("time").asLong(0L);
            }

            addInboundEvent(platform, entryId, senderId, recipientId, messageNode, timestampMs, deduped);
        }
    }

    private void addInboundEvent(String platform,
                                 String entryId,
                                 JsonNode senderNode,
                                 JsonNode recipientNode,
                                 JsonNode messageNode,
                                 long timestampMs,
                                 Map<String, MetaInboundChatEvent> deduped) {
        addInboundEvent(platform,
                entryId,
                safeTrim(senderNode.path("id").asText("")),
                safeTrim(recipientNode.path("id").asText("")),
                messageNode,
                timestampMs,
                deduped);
    }

    private void addInboundEvent(String platform,
                                 String entryId,
                                 String senderId,
                                 String recipientId,
                                 JsonNode messageNode,
                                 long timestampMs,
                                 Map<String, MetaInboundChatEvent> deduped) {
        if (messageNode.path("is_echo").asBoolean(false)) {
            return;
        }

        if (senderId.isBlank()) {
            return;
        }

        if (!entryId.isBlank() && entryId.equals(senderId)) {
            return;
        }
        if (!recipientId.isBlank() && recipientId.equals(senderId)) {
            return;
        }

        String messageId = safeTrim(messageNode.path("mid").asText(""));
        if (messageId.isBlank()) {
            messageId = safeTrim(messageNode.path("id").asText(""));
        }

        String preview = extractPreview(messageNode);
        MetaInboundChatEvent event = new MetaInboundChatEvent(
                platform,
                entryId,
                senderId,
                messageId,
                preview,
                Math.max(0L, timestampMs));

        String dedupeKey = platform + "|" + entryId + "|" + senderId + "|" + messageId + "|" + event.timestampMs();
        deduped.putIfAbsent(dedupeKey, event);
    }

    private String extractPreview(JsonNode messageNode) {
        String text = safeTrim(messageNode.path("text").asText(""));
        if (!text.isBlank()) {
            return text;
        }
        String body = safeTrim(messageNode.path("body").asText(""));
        if (!body.isBlank()) {
            return body;
        }
        if (messageNode.path("attachments").isArray() && !messageNode.path("attachments").isEmpty()) {
            return "(attachment)";
        }
        return "(non-text message)";
    }

    private List<JsonNode> iterable(JsonNode node) {
        if (node == null || !node.isArray()) {
            return List.of();
        }
        List<JsonNode> nodes = new ArrayList<>();
        node.forEach(nodes::add);
        return nodes;
    }

    private String safeLower(String value) {
        return safeTrim(value).toLowerCase();
    }

    private String safeTrim(String value) {
        return value == null ? "" : value.trim();
    }
}
