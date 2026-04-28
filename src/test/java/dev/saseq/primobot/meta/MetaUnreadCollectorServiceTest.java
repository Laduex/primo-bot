package dev.saseq.primobot.meta;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MetaUnreadCollectorServiceTest {

    @Test
    void continuesWhenInstagramFailsAndKeepsFacebookUnread() {
        MetaUnreadApiClient fakeClient = new MetaUnreadApiClient() {
            @Override
            public List<MetaPageAccess> listPages() {
                return List.of(new MetaPageAccess(
                        "297424713461571",
                        "Primal Brew Roastery",
                        "page-token",
                        "17841467092463223",
                        "primalbrewroastery"
                ));
            }

            @Override
            public List<MetaUnreadConversation> listUnreadConversations(MetaPageAccess page, String platform) {
                if ("instagram".equals(platform)) {
                    throw new MetaGraphApiException(
                            403,
                            230,
                            null,
                            "OAuthException",
                            "trace123",
                            "Requires instagram_manage_messages permission"
                    );
                }
                return List.of(new MetaUnreadConversation(
                        page.pageId(),
                        page.pageName(),
                        "facebook",
                        "t_123",
                        "Customer A",
                        "Hello",
                        2,
                        "2026-04-25T03:00:00+0000"
                ));
            }
        };

        MetaUnreadCollectorService service = new MetaUnreadCollectorService(fakeClient);
        MetaUnreadSnapshot snapshot = service.collectUnread();

        assertEquals(1, snapshot.pagesScanned());
        assertEquals(1, snapshot.unreadThreadCount());
        assertEquals(2, snapshot.unreadMessageCount());
        assertEquals(1, snapshot.warnings().size());
        assertTrue(snapshot.warnings().get(0).contains("Instagram unread check failed"));
    }

    @Test
    void continuesWhenFacebookTimesOutAndKeepsInstagramUnread() {
        MetaUnreadApiClient fakeClient = new MetaUnreadApiClient() {
            @Override
            public List<MetaPageAccess> listPages() {
                return List.of(new MetaPageAccess(
                        "240494126582711",
                        "Primal Brew Cafe",
                        "page-token",
                        "17841467092463223",
                        "primalbrew"
                ));
            }

            @Override
            public List<MetaUnreadConversation> listUnreadConversations(MetaPageAccess page, String platform) {
                if ("facebook".equals(platform)) {
                    throw new IllegalStateException("Meta API request failed: Request timed out");
                }
                return List.of(new MetaUnreadConversation(
                        page.pageId(),
                        page.pageName(),
                        "instagram",
                        "ig_123",
                        "Customer B",
                        "Hi",
                        1,
                        "2026-04-25T04:00:00+0000"
                ));
            }
        };

        MetaUnreadCollectorService service = new MetaUnreadCollectorService(fakeClient);
        MetaUnreadSnapshot snapshot = service.collectUnread();

        assertEquals(1, snapshot.pagesScanned());
        assertEquals(1, snapshot.unreadThreadCount());
        assertEquals(1, snapshot.unreadMessageCount());
        assertEquals(1, snapshot.warnings().size());
        assertTrue(snapshot.warnings().get(0).contains("Facebook unread check failed"));
        assertTrue(snapshot.warnings().get(0).contains("Request timed out"));
    }
}
