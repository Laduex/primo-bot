package dev.saseq.primobot.meta;

public class MetaUnreadConfig {
    private boolean enabled = false;
    private String targetChannelId = "";
    private int intervalMinutes = 15;
    private long lastRunAtEpochMs = 0L;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getTargetChannelId() {
        return targetChannelId;
    }

    public void setTargetChannelId(String targetChannelId) {
        this.targetChannelId = targetChannelId;
    }

    public int getIntervalMinutes() {
        return intervalMinutes;
    }

    public void setIntervalMinutes(int intervalMinutes) {
        this.intervalMinutes = intervalMinutes;
    }

    public long getLastRunAtEpochMs() {
        return lastRunAtEpochMs;
    }

    public void setLastRunAtEpochMs(long lastRunAtEpochMs) {
        this.lastRunAtEpochMs = lastRunAtEpochMs;
    }
}
