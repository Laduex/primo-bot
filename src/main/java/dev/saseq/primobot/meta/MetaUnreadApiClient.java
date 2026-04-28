package dev.saseq.primobot.meta;

import java.util.List;

public interface MetaUnreadApiClient {
    List<MetaPageAccess> listPages();

    List<MetaUnreadConversation> listUnreadConversations(MetaPageAccess page, String platform);
}
