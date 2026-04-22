package dev.saseq.primobot.sales;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Iterator;
import java.util.Map;

@Component
public class UtakBrowserSalesProvider implements SalesProvider {
    private static final String DEFAULT_FIREBASE_API_KEY = "AIzaSyAIPL4akKeV62h4RRUn7jPhHy1JfXBqW-g";
    private static final String DEFAULT_FIREBASE_DB_URL = "https://posfire-8d2cb.firebaseio.com";
    private static final String FIREBASE_API_KEY_ENV = "UTAK_FIREBASE_API_KEY";
    private static final String FIREBASE_DB_URL_ENV = "UTAK_FIREBASE_DB_URL";

    private final RestClient restClient = RestClient.create();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public SalesPlatform platform() {
        return SalesPlatform.UTAK;
    }

    @Override
    public SalesAccountResult fetchTodayCumulative(SalesAccountConfig account, SalesFetchContext context) {
        String username = trim(account.getUsername());
        String password = trim(account.getPassword());
        if (username.isBlank() || password.isBlank()) {
            throw new IllegalArgumentException("UTAK account requires username and password");
        }

        try {
            String apiKey = resolveFirebaseApiKey();
            String dbBaseUrl = resolveFirebaseDbBaseUrl(account);

            AuthSession session = authenticateWithFirebase(username, password, apiKey);
            String transactionsJson = fetchTransactionsJson(
                    dbBaseUrl,
                    session.uid(),
                    session.idToken(),
                    context.reportDate(),
                    context.zoneId()
            );
            BigDecimal totalNetSales = sumTotalNetSalesFromTransactionsJson(transactionsJson);

            return SalesAccountResult.success(account, SalesPlatform.UTAK, SalesPlatform.UTAK.getMetricLabel(), totalNetSales);
        } catch (Exception ex) {
            throw new IllegalStateException("Failed UTAK fetch for account '%s': %s"
                    .formatted(account.getName(), ex.getMessage()), ex);
        }
    }

    private AuthSession authenticateWithFirebase(String username, String password, String apiKey) throws Exception {
        String authUrl = "https://identitytoolkit.googleapis.com/v1/accounts:signInWithPassword?key=" + encode(apiKey);

        JsonNode payload = objectMapper.createObjectNode()
                .put("email", username)
                .put("password", password)
                .put("returnSecureToken", true);

        String responseBody = restClient.post()
                .uri(authUrl)
                .contentType(MediaType.APPLICATION_JSON)
                .body(payload.toString())
                .retrieve()
                .body(String.class);

        if (responseBody == null || responseBody.isBlank()) {
            throw new IllegalStateException("UTAK auth returned empty response");
        }

        JsonNode root = objectMapper.readTree(responseBody);
        String uid = trim(readString(root, "localId"));
        String idToken = trim(readString(root, "idToken"));
        if (uid.isBlank() || idToken.isBlank()) {
            throw new IllegalStateException("UTAK auth missing localId/idToken");
        }

        return new AuthSession(uid, idToken);
    }

    private String fetchTransactionsJson(String dbBaseUrl,
                                         String uid,
                                         String idToken,
                                         LocalDate reportDate,
                                         ZoneId zoneId) {
        long startEpoch = reportDate.atStartOfDay(zoneId).toEpochSecond();
        long endEpoch = reportDate.plusDays(1).atStartOfDay(zoneId).minusSeconds(1).toEpochSecond();

        String transactionsUrl = dbBaseUrl
                + "/"
                + encodePath(uid)
                + "/transactions.json"
                + "?orderBy=%22$key%22"
                + "&startAt=%22" + startEpoch + "%22"
                + "&endAt=%22" + endEpoch + "%22"
                + "&auth=" + encode(idToken);

        String body = restClient.get()
                .uri(URI.create(transactionsUrl))
                .retrieve()
                .body(String.class);

        return body == null ? "null" : body;
    }

    static BigDecimal sumTotalNetSalesFromTransactionsJson(String transactionsJson) throws Exception {
        if (transactionsJson == null || transactionsJson.isBlank() || "null".equalsIgnoreCase(transactionsJson.trim())) {
            return BigDecimal.ZERO;
        }

        ObjectMapper mapper = new ObjectMapper();
        JsonNode root = mapper.readTree(transactionsJson);
        if (root == null || root.isNull() || !root.isObject()) {
            return BigDecimal.ZERO;
        }

        BigDecimal total = BigDecimal.ZERO;
        Iterator<Map.Entry<String, JsonNode>> entries = root.fields();
        while (entries.hasNext()) {
            Map.Entry<String, JsonNode> entry = entries.next();
            JsonNode transaction = entry.getValue();
            if (transaction == null || transaction.isNull() || !transaction.isObject()) {
                continue;
            }
            if (transaction.path("_deleted").asBoolean(false)) {
                continue;
            }

            JsonNode totalNode = transaction.get("total");
            BigDecimal amount = parseAmount(totalNode);
            if (amount != null) {
                total = total.add(amount);
            }
        }

        return total;
    }

    private static BigDecimal parseAmount(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isNumber()) {
            return node.decimalValue();
        }
        if (node.isTextual()) {
            String raw = node.asText().replace(",", "").trim();
            if (raw.isEmpty()) {
                return null;
            }
            try {
                return new BigDecimal(raw);
            } catch (Exception ignored) {
                return null;
            }
        }
        return null;
    }

    private String resolveFirebaseApiKey() {
        String fromEnv = trim(System.getenv(FIREBASE_API_KEY_ENV));
        if (!fromEnv.isBlank()) {
            return fromEnv;
        }
        return DEFAULT_FIREBASE_API_KEY;
    }

    private String resolveFirebaseDbBaseUrl(SalesAccountConfig account) {
        String fromEnv = trim(System.getenv(FIREBASE_DB_URL_ENV));
        if (!fromEnv.isBlank()) {
            return stripTrailingSlash(fromEnv);
        }

        String configured = trim(account.getBaseUrl());
        if (configured.contains("firebaseio.com")) {
            return stripTrailingSlash(configured);
        }

        return DEFAULT_FIREBASE_DB_URL;
    }

    private String readString(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? "" : value.asText("");
    }

    private String encode(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }

    private String encodePath(String value) {
        return encode(value).replace("+", "%20");
    }

    private String stripTrailingSlash(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String trimmed = value.trim();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }

    private String trim(String value) {
        return value == null ? "" : value.trim();
    }

    private record AuthSession(String uid, String idToken) {
    }
}
