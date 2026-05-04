package dev.saseq.primobot.meta;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MetaWebhookEventExtractorTest {
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final MetaWebhookEventExtractor extractor = new MetaWebhookEventExtractor();

    @Test
    void extractsInboundMessengerMessagesOnly() throws Exception {
        String payload = """
                {
                  "object": "page",
                  "entry": [{
                    "id": "123456789",
                    "messaging": [
                      {
                        "sender": {"id": "user-1"},
                        "recipient": {"id": "123456789"},
                        "timestamp": 1710000000000,
                        "message": {"mid": "m_1", "text": "Hello Primo"}
                      },
                      {
                        "sender": {"id": "123456789"},
                        "recipient": {"id": "user-1"},
                        "timestamp": 1710000000100,
                        "message": {"mid": "m_2", "text": "Echo", "is_echo": true}
                      }
                    ]
                  }]
                }
                """;

        List<MetaInboundChatEvent> events = extractor.extractInboundChatEvents(objectMapper.readTree(payload));

        assertEquals(1, events.size());
        MetaInboundChatEvent event = events.get(0);
        assertEquals("messenger", event.platform());
        assertEquals("123456789", event.pageOrAccountId());
        assertEquals("user-1", event.senderId());
        assertEquals("m_1", event.messageId());
        assertEquals("Hello Primo", event.previewText());
        assertEquals(1710000000000L, event.timestampMs());
    }

    @Test
    void extractsInboundInstagramChangesMessages() throws Exception {
        String payload = """
                {
                  "object": "instagram",
                  "entry": [{
                    "id": "ig_account_1",
                    "changes": [{
                      "field": "messages",
                      "value": {
                        "sender": {"id": "ig_user_1"},
                        "recipient": {"id": "ig_account_1"},
                        "timestamp": 1710000009999,
                        "message": {"mid": "ig_m_1", "attachments": [{"type":"image"}]}
                      }
                    }]
                  }]
                }
                """;

        List<MetaInboundChatEvent> events = extractor.extractInboundChatEvents(objectMapper.readTree(payload));

        assertEquals(1, events.size());
        MetaInboundChatEvent event = events.get(0);
        assertEquals("instagram", event.platform());
        assertEquals("ig_account_1", event.pageOrAccountId());
        assertEquals("ig_user_1", event.senderId());
        assertEquals("ig_m_1", event.messageId());
        assertEquals("(attachment)", event.previewText());
        assertEquals(1710000009999L, event.timestampMs());
    }

    @Test
    void ignoresNonMetaObjects() throws Exception {
        String payload = """
                {
                  "object": "whatsapp_business_account",
                  "entry": [{
                    "id": "any",
                    "changes": []
                  }]
                }
                """;

        List<MetaInboundChatEvent> events = extractor.extractInboundChatEvents(objectMapper.readTree(payload));
        assertTrue(events.isEmpty());
    }
}
