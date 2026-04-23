package dev.saseq.primobot.sales;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class SalesReportConfig {
    private boolean enabled = true;
    private String timezone = "Asia/Manila";
    private List<String> times = new ArrayList<>();
    private String targetChannelId = "";
    private String overviewTime = "";
    private String overviewTargetChannelId = "";
    private String messageTone = "casual";
    private String signature = "Thanks, Primo";
    private List<SalesAccountConfig> accounts = new ArrayList<>();
    private Map<String, String> lastRunDateBySlot = new LinkedHashMap<>();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getTimezone() {
        return timezone;
    }

    public void setTimezone(String timezone) {
        this.timezone = timezone;
    }

    public List<String> getTimes() {
        return times;
    }

    public void setTimes(List<String> times) {
        this.times = times;
    }

    public String getTargetChannelId() {
        return targetChannelId;
    }

    public void setTargetChannelId(String targetChannelId) {
        this.targetChannelId = targetChannelId;
    }

    public String getOverviewTime() {
        return overviewTime;
    }

    public void setOverviewTime(String overviewTime) {
        this.overviewTime = overviewTime;
    }

    public String getOverviewTargetChannelId() {
        return overviewTargetChannelId;
    }

    public void setOverviewTargetChannelId(String overviewTargetChannelId) {
        this.overviewTargetChannelId = overviewTargetChannelId;
    }

    public String getMessageTone() {
        return messageTone;
    }

    public void setMessageTone(String messageTone) {
        this.messageTone = messageTone;
    }

    public String getSignature() {
        return signature;
    }

    public void setSignature(String signature) {
        this.signature = signature;
    }

    public List<SalesAccountConfig> getAccounts() {
        return accounts;
    }

    public void setAccounts(List<SalesAccountConfig> accounts) {
        this.accounts = accounts;
    }

    public Map<String, String> getLastRunDateBySlot() {
        return lastRunDateBySlot;
    }

    public void setLastRunDateBySlot(Map<String, String> lastRunDateBySlot) {
        this.lastRunDateBySlot = lastRunDateBySlot;
    }
}
