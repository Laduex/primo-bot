package dev.saseq.primobot.meta;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MetaGraphApiClientTest {
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void listPagesSupportsPagination() throws Exception {
        List<String> urls = new ArrayList<>();
        MetaGraphApiClient.GraphTransport transport = (url, mapper) -> {
            urls.add(url);
            if (url.contains("after=cursor-two")) {
                return json("""
                        {
                          "data": [
                            {"id": "240494126582711", "name": "Primal Brew 2", "access_token": "page-token-2"}
                          ]
                        }
                        """);
            }
            return json("""
                    {
                      "data": [
                        {"id": "297424713461571", "name": "Primal Brew 1", "access_token": "page-token-1"}
                      ],
                      "paging": {"cursors": {"after": "cursor-two"}}
                    }
                    """);
        };

        MetaGraphApiClient client = new MetaGraphApiClient(
                "https://graph.facebook.com",
                "v24.0",
                "user-token",
                "",
                transport,
                objectMapper
        );

        List<MetaPageAccess> pages = client.listPages();

        assertEquals(2, pages.size());
        assertEquals("297424713461571", pages.get(0).pageId());
        assertEquals("240494126582711", pages.get(1).pageId());
        assertEquals(2, urls.size());
        assertTrue(urls.get(0).contains("/v24.0/me/accounts"));
        assertTrue(urls.get(0).contains("instagram_business_account"));
        assertFalse(urls.get(0).contains("%7B"));
        assertFalse(urls.get(0).contains("{"));
    }

    @Test
    void listUnreadConversationsFiltersUnreadAndPaginates() throws Exception {
        MetaGraphApiClient.GraphTransport transport = (url, mapper) -> {
            if (url.contains("after=next-page")) {
                return json("""
                        {
                          "data": [
                            {
                              "id": "t_3",
                              "updated_time": "2026-04-25T02:00:00+0000",
                              "snippet": "Follow-up",
                              "unread_count": 1,
                              "senders": {"data": [{"name": "Cara"}]}
                            }
                          ]
                        }
                        """);
            }
            return json("""
                    {
                      "data": [
                        {
                          "id": "t_1",
                          "updated_time": "2026-04-25T01:00:00+0000",
                          "snippet": "Seen thread",
                          "unread_count": 0,
                          "senders": {"data": [{"name": "Alex"}]}
                        },
                        {
                          "id": "t_2",
                          "updated_time": "2026-04-25T03:00:00+0000",
                          "snippet": "Need help",
                          "unread_count": 2,
                          "senders": {"data": [{"name": "Bea"}]}
                        }
                      ],
                      "paging": {"cursors": {"after": "next-page"}}
                    }
                    """);
        };

        MetaGraphApiClient client = new MetaGraphApiClient(
                "https://graph.facebook.com",
                "v24.0",
                "user-token",
                "",
                transport,
                objectMapper
        );

        MetaPageAccess page = new MetaPageAccess(
                "297424713461571",
                "Primal Brew Roastery",
                "page-token",
                "",
                "");

        List<MetaUnreadConversation> unread = client.listUnreadConversations(page, "instagram");

        assertEquals(2, unread.size());
        assertEquals("t_2", unread.get(0).conversationId());
        assertEquals(2, unread.get(0).unreadCount());
        assertEquals("instagram", unread.get(0).platform());
        assertEquals("t_3", unread.get(1).conversationId());
    }

    private JsonNode json(String raw) {
        try {
            return objectMapper.readTree(raw);
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }
}
