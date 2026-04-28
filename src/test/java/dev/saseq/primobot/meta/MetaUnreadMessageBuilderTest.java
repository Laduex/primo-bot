package dev.saseq.primobot.meta;

import dev.saseq.primobot.util.DiscordMessageUtils;
import org.junit.jupiter.api.Test;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MetaUnreadMessageBuilderTest {

    @Test
    void buildsDeterministicDigestWithWarnings() {
        MetaUnreadMessageBuilder builder = new MetaUnreadMessageBuilder();

        MetaUnreadSnapshot snapshot = new MetaUnreadSnapshot(
                2,
                2,
                5,
                List.of(
                        new MetaUnreadConversation(
                                "297424713461571",
                                "Primal Brew Roastery",
                                "facebook",
                                "t_abc",
                                "Customer A",
                                "Need price list",
                                3,
                                "2026-04-25T03:00:00+0000"
                        ),
                        new MetaUnreadConversation(
                                "297424713461571",
                                "Primal Brew Roastery",
                                "instagram",
                                "t_xyz",
                                "Customer B",
                                "Can I order?",
                                2,
                                "2026-04-25T02:30:00+0000"
                        )
                ),
                List.of("Instagram unread check failed for `Primal Brew`: status=403 code=230 message=Missing permission")
        );

        String message = builder.buildDigest(snapshot,
                ZonedDateTime.of(2026, 4, 28, 9, 30, 0, 0, ZoneId.of("Asia/Manila")));

        assertTrue(message.contains("**Meta Unread Digest**"));
        assertTrue(message.contains("Pages Scanned: `2`"));
        assertTrue(message.contains("Unread Conversations: `2`"));
        assertTrue(message.contains("Unread Messages: `5`"));
        assertTrue(message.contains("**By Platform**"));
        assertTrue(message.contains("- Messenger: threads `1`, messages `3`"));
        assertTrue(message.contains("- Instagram: threads `1`, messages `2`"));
        assertTrue(message.contains("**By Page**"));
        assertTrue(message.contains("**Recent Unread Conversations**"));
        assertTrue(message.contains("1.\n   Name: Customer A"));
        assertTrue(message.contains("Platform: Messenger (account)"));
        assertTrue(message.contains("Snippet: Need price list"));
        assertTrue(message.contains("Time Received: `2026-04-25T03:00:00+0000`"));
        assertTrue(message.contains("Days Ago: `"));
        assertTrue(message.contains("2.\n   Name: Customer B"));
        assertTrue(message.contains("Platform: Instagram"));
        assertTrue(message.contains("Snippet: Can I order?"));
        assertTrue(message.contains("**Instagram Warnings**"));
    }

    @Test
    void supportsChunkingForLargeDigest() {
        MetaUnreadMessageBuilder builder = new MetaUnreadMessageBuilder();

        List<MetaUnreadConversation> conversations = new ArrayList<>();
        for (int index = 0; index < 80; index++) {
            conversations.add(new MetaUnreadConversation(
                    "240494126582711",
                    "Primal Brew Coffee + Roastery",
                    "facebook",
                    "t_" + index,
                    "Customer " + index,
                    "Message content " + index + " lorem ipsum dolor sit amet consectetur adipiscing elit",
                    1,
                    "2026-04-25T03:00:00+0000"
            ));
        }

        MetaUnreadSnapshot snapshot = new MetaUnreadSnapshot(
                1,
                conversations.size(),
                conversations.size(),
                conversations,
                IntStream.range(0, 40)
                        .mapToObj(index -> "Instagram unread check failed for `Primal Brew`: status=403 code=230 message=Missing permission " + index)
                        .toList());

        String message = builder.buildDigest(snapshot,
                ZonedDateTime.of(2026, 4, 28, 9, 30, 0, 0, ZoneId.of("Asia/Manila")));
        List<String> chunks = DiscordMessageUtils.chunkMessage(message, 2000);

        assertTrue(message.contains("...and `65` more unread conversation(s)."));
        assertTrue(chunks.size() > 1);
        assertEquals(message.length(), chunks.stream().mapToInt(String::length).sum());
    }

    @Test
    void rendersMinutesInDaysAgoForRecentMessages() {
        MetaUnreadMessageBuilder builder = new MetaUnreadMessageBuilder();

        MetaUnreadSnapshot snapshot = new MetaUnreadSnapshot(
                1,
                1,
                1,
                List.of(
                        new MetaUnreadConversation(
                                "297424713461571",
                                "Primal Brew Roastery",
                                "facebook",
                                "t_recent",
                                "Recent Customer",
                                "Hello, available today?",
                                1,
                                "2026-04-28T01:18:00+0000"
                        )
                ),
                List.of()
        );

        String message = builder.buildDigest(
                snapshot,
                ZonedDateTime.of(2026, 4, 28, 9, 30, 0, 0, ZoneId.of("Asia/Manila"))
        );

        assertTrue(message.contains("Days Ago: `12m`"));
    }
}
