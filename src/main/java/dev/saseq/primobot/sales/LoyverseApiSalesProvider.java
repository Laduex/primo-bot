package dev.saseq.primobot.sales;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

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

        String responseBody = restClient.get()
                .uri(endpoint)
                .header("Authorization", "Bearer " + token)
                .retrieve()
                .body(String.class);

        if (responseBody == null || responseBody.isBlank()) {
            throw new IllegalStateException("Loyverse API returned empty response");
        }

        BigDecimal grossSalesToday = parseGrossSalesForDate(responseBody, context.reportDate(), context.zoneId());
        return SalesAccountResult.success(account, SalesPlatform.LOYVERSE, SalesPlatform.LOYVERSE.getMetricLabel(), grossSalesToday);
    }

    private BigDecimal parseGrossSalesForDate(String rawJson, LocalDate reportDate, ZoneId zoneId) {
        try {
            JsonNode root = objectMapper.readTree(rawJson);
            List<JsonNode> receipts = resolveReceiptNodes(root);
            if (receipts.isEmpty()) {
                throw new IllegalStateException("No receipts found in Loyverse response");
            }

            BigDecimal total = BigDecimal.ZERO;
            boolean hasAnyIncluded = false;
            for (JsonNode receipt : receipts) {
                if (!isReceiptOnReportDate(receipt, reportDate, zoneId)) {
                    continue;
                }
                hasAnyIncluded = true;
                total = total.add(extractGrossAmount(receipt));
            }

            if (!hasAnyIncluded) {
                return BigDecimal.ZERO;
            }
            return total;
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
        String[] candidates = {
                "gross_total_money",
                "gross_total",
                "gross_sales",
                "total_money",
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
}
