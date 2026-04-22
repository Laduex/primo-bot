package dev.saseq.primobot.sales;

public class SalesAccountConfig {
    private String id;
    private String platform;
    private String name;
    private boolean enabled = true;

    private String username;
    private String password;
    private String token;

    private String baseUrl;
    private String salesPageUrl;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getPlatform() {
        return platform;
    }

    public void setPlatform(String platform) {
        this.platform = platform;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public String getToken() {
        return token;
    }

    public void setToken(String token) {
        this.token = token;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getSalesPageUrl() {
        return salesPageUrl;
    }

    public void setSalesPageUrl(String salesPageUrl) {
        this.salesPageUrl = salesPageUrl;
    }

    public SalesPlatform resolvePlatform() {
        return SalesPlatform.fromRaw(platform);
    }
}
