package dev.saseq.primobot.meta;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

@Component
public class MetaGraphApiClient implements MetaUnreadApiClient {
    private static final String PAGES_FIELDS = "id,name,access_token,instagram_business_account{id,username}";
    private static final String CONVERSATION_FIELDS = "id,updated_time,snippet,unread_count,senders";

    private final String apiBaseUrl;
    private final String graphVersion;
    private final String userAccessToken;
    private final String appSecret;
    private final GraphTransport transport;
    private final ObjectMapper objectMapper;

    @Autowired
    public MetaGraphApiClient(
            @Value("${META_API_BASE_URL:https://graph.facebook.com}") String apiBaseUrl,
            @Value("${META_GRAPH_VERSION:v24.0}") String graphVersion,
            @Value("${META_ACCESS_TOKEN:}") String userAccessToken,
            @Value("${META_APP_SECRET:}") String appSecret) {
        this(apiBaseUrl, graphVersion, userAccessToken, appSecret,
                new RestClientGraphTransport(), new ObjectMapper());
    }

    MetaGraphApiClient(String apiBaseUrl,
                       String graphVersion,
                       String userAccessToken,
                       String appSecret,
                       GraphTransport transport,
                       ObjectMapper objectMapper) {
        this.apiBaseUrl = normalizeBaseUrl(apiBaseUrl);
        this.graphVersion = normalizeGraphVersion(graphVersion);
        this.userAccessToken = userAccessToken == null ? "" : userAccessToken.trim();
        this.appSecret = appSecret == null ? "" : appSecret.trim();
        this.transport = transport;
        this.objectMapper = objectMapper == null ? new ObjectMapper() : objectMapper;
    }

    @Override
    public List<MetaPageAccess> listPages() {
        if (userAccessToken.isBlank()) {
            throw new IllegalStateException("META_ACCESS_TOKEN is missing.");
        }

        List<MetaPageAccess> pages = new ArrayList<>();
        String after = "";
        while (true) {
            Map<String, String> params = new LinkedHashMap<>();
            params.put("fields", PAGES_FIELDS);
            params.put("limit", "100");
            if (!after.isBlank()) {
                params.put("after", after);
            }

            JsonNode root = callGraph("/me/accounts", userAccessToken, params);
            JsonNode data = root.path("data");
            if (data.isArray()) {
                for (JsonNode node : data) {
                    String pageId = text(node, "id");
                    String pageName = text(node, "name");
                    String pageToken = text(node, "access_token");
                    JsonNode ig = node.path("instagram_business_account");
                    String igId = text(ig, "id");
                    String igUsername = text(ig, "username");

                    if (pageId.isBlank() || pageToken.isBlank()) {
                        continue;
                    }
                    pages.add(new MetaPageAccess(pageId, defaultIfBlank(pageName, pageId), pageToken, igId, igUsername));
                }
            }

            after = nextAfterCursor(root);
            if (after.isBlank()) {
                break;
            }
        }

        pages.sort(Comparator.comparing(MetaPageAccess::pageName, String.CASE_INSENSITIVE_ORDER));
        return pages;
    }

    @Override
    public List<MetaUnreadConversation> listUnreadConversations(MetaPageAccess page, String platform) {
        if (page == null || page.pageId() == null || page.pageId().isBlank() || page.pageAccessToken() == null || page.pageAccessToken().isBlank()) {
            return List.of();
        }

        String normalizedPlatform = normalizePlatform(platform);
        List<MetaUnreadConversation> unread = new ArrayList<>();
        String after = "";
        while (true) {
            Map<String, String> params = new LinkedHashMap<>();
            params.put("fields", CONVERSATION_FIELDS);
            params.put("limit", "100");
            if ("instagram".equals(normalizedPlatform)) {
                params.put("platform", "instagram");
            }
            if (!after.isBlank()) {
                params.put("after", after);
            }

            JsonNode root = callGraph("/" + page.pageId() + "/conversations", page.pageAccessToken(), params);
            JsonNode data = root.path("data");
            if (data.isArray()) {
                for (JsonNode node : data) {
                    int unreadCount = intValue(node.path("unread_count"));
                    if (unreadCount <= 0) {
                        continue;
                    }

                    unread.add(new MetaUnreadConversation(
                            page.pageId(),
                            page.pageName(),
                            "instagram".equals(normalizedPlatform) ? "instagram" : "facebook",
                            text(node, "id"),
                            resolveSenderName(node.path("senders"), page.pageName()),
                            text(node, "snippet"),
                            unreadCount,
                            text(node, "updated_time")
                    ));
                }
            }

            after = nextAfterCursor(root);
            if (after.isBlank()) {
                break;
            }
        }

        unread.sort(Comparator.comparing(MetaUnreadConversation::updatedTime,
                Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)).reversed());
        return unread;
    }

    private JsonNode callGraph(String path, String accessToken, Map<String, String> params) {
        String url = buildUrl(path, accessToken, params);
        return transport.get(url, objectMapper);
    }

    private String buildUrl(String path, String accessToken, Map<String, String> params) {
        String normalizedPath = path == null ? "" : path.trim();
        if (!normalizedPath.startsWith("/")) {
            normalizedPath = "/" + normalizedPath;
        }

        UriComponentsBuilder builder = UriComponentsBuilder
                .fromUriString(apiBaseUrl + "/" + graphVersion + normalizedPath);

        if (params != null) {
            for (Map.Entry<String, String> entry : params.entrySet()) {
                if (entry.getKey() == null || entry.getKey().isBlank()) {
                    continue;
                }
                String value = entry.getValue() == null ? "" : entry.getValue();
                builder.queryParam(entry.getKey(), value);
            }
        }

        builder.queryParam("access_token", accessToken == null ? "" : accessToken);
        String proof = appSecretProof(accessToken);
        if (!proof.isBlank()) {
            builder.queryParam("appsecret_proof", proof);
        }
        return builder.build(true).toUriString();
    }

    private String appSecretProof(String token) {
        if (token == null || token.isBlank() || appSecret.isBlank()) {
            return "";
        }

        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(appSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] digest = mac.doFinal(token.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                out.append(String.format("%02x", b));
            }
            return out.toString();
        } catch (Exception ex) {
            throw new IllegalStateException("Failed generating appsecret_proof", ex);
        }
    }

    private String nextAfterCursor(JsonNode root) {
        JsonNode after = root.path("paging").path("cursors").path("after");
        return after.isTextual() ? after.asText("").trim() : "";
    }

    private String resolveSenderName(JsonNode sendersNode, String fallbackPageName) {
        if (sendersNode == null || sendersNode.isMissingNode()) {
            return defaultIfBlank(fallbackPageName, "Unknown sender");
        }

        JsonNode data = sendersNode.path("data");
        if (!data.isArray()) {
            return defaultIfBlank(fallbackPageName, "Unknown sender");
        }

        for (JsonNode sender : data) {
            String name = text(sender, "name");
            if (!name.isBlank()) {
                return name;
            }
        }

        return defaultIfBlank(fallbackPageName, "Unknown sender");
    }

    private int intValue(JsonNode node) {
        if (node == null || node.isNull()) {
            return 0;
        }
        if (node.isNumber()) {
            return Math.max(0, node.asInt());
        }
        if (node.isTextual()) {
            try {
                return Math.max(0, Integer.parseInt(node.asText("0").trim()));
            } catch (Exception ignored) {
                return 0;
            }
        }
        return 0;
    }

    private String text(JsonNode node, String field) {
        if (node == null || node.isNull() || field == null || field.isBlank()) {
            return "";
        }
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? "" : value.asText("").trim();
    }

    private String defaultIfBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private String normalizePlatform(String platform) {
        if (platform == null || platform.isBlank()) {
            return "";
        }
        String value = platform.trim().toLowerCase(Locale.ENGLISH);
        return switch (value) {
            case "instagram" -> "instagram";
            case "facebook", "messenger" -> "facebook";
            default -> value;
        };
    }

    private String normalizeBaseUrl(String raw) {
        String value = raw == null || raw.isBlank() ? "https://graph.facebook.com" : raw.trim();
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        return value;
    }

    private String normalizeGraphVersion(String raw) {
        String value = raw == null || raw.isBlank() ? "v24.0" : raw.trim();
        if (value.startsWith("/")) {
            value = value.substring(1);
        }
        return value;
    }

    interface GraphTransport {
        JsonNode get(String url, ObjectMapper objectMapper);
    }

    static class RestClientGraphTransport implements GraphTransport {
        private final RestClient restClient = RestClient.create();

        @Override
        public JsonNode get(String url, ObjectMapper objectMapper) {
            try {
                return restClient.get()
                        .uri(url)
                        .exchange((request, response) -> {
                            String body = new String(response.getBody().readAllBytes(), StandardCharsets.UTF_8);
                            int status = response.getStatusCode().value();
                            if (status >= 400) {
                                throw parseApiError(status, body, objectMapper);
                            }

                            try {
                                return objectMapper.readTree(body == null || body.isBlank() ? "{}" : body);
                            } catch (IOException ex) {
                                throw new IllegalStateException("Meta API returned invalid JSON", ex);
                            }
                        });
            } catch (MetaGraphApiException ex) {
                throw ex;
            } catch (RuntimeException ex) {
                throw new IllegalStateException("Meta API request failed: " + ex.getMessage(), ex);
            }
        }

        private MetaGraphApiException parseApiError(int status, String body, ObjectMapper objectMapper) {
            try {
                JsonNode root = objectMapper.readTree(body == null || body.isBlank() ? "{}" : body);
                JsonNode error = root.path("error");
                Integer code = error.path("code").isNumber() ? error.path("code").asInt() : null;
                Integer subcode = error.path("error_subcode").isNumber() ? error.path("error_subcode").asInt() : null;
                String type = error.path("type").asText("");
                String fbTraceId = error.path("fbtrace_id").asText("");
                String message = error.path("message").asText("Unknown Meta API error");
                return new MetaGraphApiException(status, code, subcode, type, fbTraceId, message);
            } catch (IOException ignored) {
                return new MetaGraphApiException(status, null, null, "", "", "Unknown Meta API error");
            }
        }
    }
}
