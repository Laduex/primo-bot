package dev.saseq.primobot.sales;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.net.URI;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;

@Component
public class LoyverseApiSalesProvider implements SalesProvider {
    private static final String DEFAULT_RECEIPTS_URL = "https://api.loyverse.com/v1.0/receipts";

    private final RestClient restClient = RestClient.create();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public SalesPlatform platform() {
        return SalesPlatform.LOYVERSE;
    }

    @Override
    public SalesAccountResult fetchTodayCumulative(SalesAccountConfig account, SalesFetchContext context) {
        String token = trim(account.getToken());
        if (token.isBlank()) {
            throw new IllegalArgumentException("Missing Loyverse API token");
        }

        String endpoint = trim(account.getBaseUrl());
        if (endpoint.isBlank()) {
            endpoint = DEFAULT_RECEIPTS_URL;
        }

        BigDecimal grossSalesToday = fetchGrossSalesForDate(token, endpoint, context.reportDate(), context.zoneId());
        return SalesAccountResult.success(account, SalesPlatform.LOYVERSE, SalesPlatform.LOYVERSE.getMetricLabel(), grossSalesToday);
    }

    private BigDecimal fetchGrossSalesForDate(String token, String endpoint, LocalDate reportDate, ZoneId zoneId) {
        String nextUrl = endpoint;
        int page = 0;
        BigDecimal total = BigDecimal.ZERO;
        boolean hasAnyIncluded = false;

        while (nextUrl != null && !nextUrl.isBlank()) {
            page++;
            if (page > 300) {
                throw new IllegalStateException("Loyverse pagination exceeded safety limit");
            }

            String responseBody = restClient.get()
                    .uri(URI.create(nextUrl))
                    .header("Authorization", "Bearer " + token)
                    .retrieve()
                    .body(String.class);

            if (responseBody == null || responseBody.isBlank()) {
                throw new IllegalStateException("Loyverse API returned empty response");
            }

            PageResult pageResult = parseGrossSalesPageForDate(responseBody, reportDate, zoneId);
            total = total.add(pageResult.amount());
            if (pageResult.includedCount() > 0) {
                hasAnyIncluded = true;
            }
            nextUrl = resolveNextUrl(endpoint, pageResult.cursor());
        }

        if (!hasAnyIncluded) {
            return BigDecimal.ZERO;
        }
        return total;
    }

    private PageResult parseGrossSalesPageForDate(String rawJson, LocalDate reportDate, ZoneId zoneId) {
        try {
            JsonNode root = objectMapper.readTree(rawJson);
            List<JsonNode> receipts = resolveReceiptNodes(root);
            if (receipts.isEmpty()) {
                return new PageResult(BigDecimal.ZERO, 0, readCursor(root));
            }

            BigDecimal pageTotal = BigDecimal.ZERO;
            int includedCount = 0;
            for (JsonNode receipt : receipts) {
                if (!isReceiptOnReportDate(receipt, reportDate, zoneId)) {
                    continue;
                }
                if (isRefundReceipt(receipt)) {
                    continue;
                }
                includedCount++;
                pageTotal = pageTotal.add(extractGrossAmount(receipt));
            }

            return new PageResult(pageTotal, includedCount, readCursor(root));
        } catch (Exception ex) {
            throw new IllegalStateException("Failed parsing Loyverse response: " + ex.getMessage(), ex);
        }
    }

    private List<JsonNode> resolveReceiptNodes(JsonNode root) {
        List<JsonNode> nodes = new ArrayList<>();
        if (root == null || root.isNull()) {
            return nodes;
        }

        if (root.isArray()) {
            root.forEach(nodes::add);
            return nodes;
        }

        JsonNode receipts = root.get("receipts");
        if (receipts != null && receipts.isArray()) {
            receipts.forEach(nodes::add);
            return nodes;
        }

        JsonNode items = root.get("items");
        if (items != null && items.isArray()) {
            items.forEach(nodes::add);
        }

        return nodes;
    }

    private boolean isReceiptOnReportDate(JsonNode receipt, LocalDate reportDate, ZoneId zoneId) {
        String[] dateFields = {"created_at", "open_time", "close_time", "receipt_date", "date"};
        for (String field : dateFields) {
            JsonNode candidate = receipt.get(field);
            if (candidate == null || candidate.isNull()) {
                continue;
            }
            String raw = trim(candidate.asText());
            if (raw.isBlank()) {
                continue;
            }
            try {
                LocalDate parsed = OffsetDateTime.parse(raw).atZoneSameInstant(zoneId).toLocalDate();
                return parsed.equals(reportDate);
            } catch (Exception ignored) {
                if (raw.length() >= 10) {
                    try {
                        LocalDate parsed = LocalDate.parse(raw.substring(0, 10));
                        return parsed.equals(reportDate);
                    } catch (Exception ignoredAgain) {
                        // continue trying other fields
                    }
                }
            }
        }

        return true;
    }

    private BigDecimal extractGrossAmount(JsonNode receipt) {
        BigDecimal fromLineItems = sumLineItemGross(receipt);
        if (fromLineItems != null) {
            return fromLineItems;
        }

        String[] candidates = {
                "gross_total_money",
                "gross_total",
                "gross_sales",
                "total"
        };

        for (String field : candidates) {
            JsonNode node = receipt.get(field);
            BigDecimal amount = parseMoneyNode(node);
            if (amount != null) {
                return amount;
            }
        }

        Iterator<JsonNode> elements = receipt.elements();
        while (elements.hasNext()) {
            JsonNode nested = elements.next();
            BigDecimal nestedAmount = parseMoneyNode(nested);
            if (nestedAmount != null) {
                return nestedAmount;
            }
        }

        return BigDecimal.ZERO;
    }

    private BigDecimal sumLineItemGross(JsonNode receipt) {
        JsonNode lineItems = receipt.get("line_items");
        if (lineItems == null || !lineItems.isArray() || lineItems.isEmpty()) {
            return null;
        }

        BigDecimal total = BigDecimal.ZERO;
        boolean hasAtLeastOne = false;
        for (JsonNode lineItem : lineItems) {
            BigDecimal amount = parseMoneyNode(lineItem.get("gross_total_money"));
            if (amount != null) {
                total = total.add(amount);
                hasAtLeastOne = true;
            }
        }
        return hasAtLeastOne ? total : null;
    }

    private BigDecimal parseMoneyNode(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }

        if (node.isNumber()) {
            if (node.isIntegralNumber()) {
                return BigDecimal.valueOf(node.asLong()).movePointLeft(2);
            }
            return node.decimalValue();
        }

        if (node.isTextual()) {
            String raw = trim(node.asText()).replace(",", "");
            if (raw.isBlank()) {
                return null;
            }
            try {
                return new BigDecimal(raw);
            } catch (Exception ignored) {
                return null;
            }
        }

        if (node.isObject()) {
            JsonNode amount = node.get("amount");
            if (amount != null && amount.isNumber()) {
                if (amount.isIntegralNumber()) {
                    return BigDecimal.valueOf(amount.asLong()).movePointLeft(2);
                }
                return amount.decimalValue();
            }
        }

        return null;
    }

    private String trim(String value) {
        return value == null ? "" : value.trim();
    }

    private boolean isRefundReceipt(JsonNode receipt) {
        String type = trim(receipt.path("receipt_type").asText(""));
        return "REFUND".equalsIgnoreCase(type);
    }

    private String readCursor(JsonNode root) {
        if (root == null || root.isNull()) {
            return "";
        }
        return trim(root.path("cursor").asText(""));
    }

    private String resolveNextUrl(String endpoint, String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return "";
        }
        String separator = endpoint.contains("?") ? "&" : "?";
        return endpoint + separator + "cursor=" + encodeValue(cursor);
    }

    private String encodeValue(String raw) {
        String value = trim(raw);
        if (value.isBlank()) {
            return "";
        }
        return java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8)
                .replace("+", "%20");
    }

    private record PageResult(BigDecimal amount, int includedCount, String cursor) {
    }
}
