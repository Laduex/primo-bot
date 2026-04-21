package dev.saseq.primobot.reminders;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class OrdersReminderConfig {
    private boolean enabled = true;
    private String timezone = "Asia/Manila";
    private int hour = 8;
    private int minute = 0;
    private List<OrdersReminderRoute> routes = new ArrayList<>();
    private Map<String, String> lastRunDateByRoute = new LinkedHashMap<>();
    private String messageTone = "casual";
    private String signature = "Thanks, Primo";

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

    public int getHour() {
        return hour;
    }

    public void setHour(int hour) {
        this.hour = hour;
    }

    public int getMinute() {
        return minute;
    }

    public void setMinute(int minute) {
        this.minute = minute;
    }

    public List<OrdersReminderRoute> getRoutes() {
        return routes;
    }

    public void setRoutes(List<OrdersReminderRoute> routes) {
        this.routes = routes;
    }

    public Map<String, String> getLastRunDateByRoute() {
        return lastRunDateByRoute;
    }

    public void setLastRunDateByRoute(Map<String, String> lastRunDateByRoute) {
        this.lastRunDateByRoute = lastRunDateByRoute;
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
}
