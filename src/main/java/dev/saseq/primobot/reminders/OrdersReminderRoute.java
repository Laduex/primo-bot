package dev.saseq.primobot.reminders;

public class OrdersReminderRoute {
    private String forumId;
    private String targetTextChannelId;
    private String mentionRoleId;

    public OrdersReminderRoute() {
    }

    public OrdersReminderRoute(String forumId, String targetTextChannelId, String mentionRoleId) {
        this.forumId = forumId;
        this.targetTextChannelId = targetTextChannelId;
        this.mentionRoleId = mentionRoleId;
    }

    public String getForumId() {
        return forumId;
    }

    public void setForumId(String forumId) {
        this.forumId = forumId;
    }

    public String getTargetTextChannelId() {
        return targetTextChannelId;
    }

    public void setTargetTextChannelId(String targetTextChannelId) {
        this.targetTextChannelId = targetTextChannelId;
    }

    public String getMentionRoleId() {
        return mentionRoleId;
    }

    public void setMentionRoleId(String mentionRoleId) {
        this.mentionRoleId = mentionRoleId;
    }
}
