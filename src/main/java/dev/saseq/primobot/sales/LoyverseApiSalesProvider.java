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
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Component
public class LoyverseApiSalesProvider implements SalesProvider {
    private static final String DEFAULT_RECEIPTS_URL = "https://api.loyverse.com/v1.0/receipts";
    private static final DateTimeFormatter LOYVERSE_DATETIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'");

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

        FetchResult fetchResult = fetchGrossSalesForDate(token, endpoint, context.reportDate(), context.zoneId());
        return SalesAccountResult.success(
                account,
                SalesPlatform.LOYVERSE,
                SalesPlatform.LOYVERSE.getMetricLabel(),
                fetchResult.amount(),
                fetchResult.skuSales());
    }

    private FetchResult fetchGrossSalesForDate(String token, String endpoint, LocalDate reportDate, ZoneId zoneId) {
        String filteredEndpoint = buildDateFilteredEndpoint(endpoint, reportDate, zoneId);
        String nextUrl = filteredEndpoint;
        int page = 0;
        BigDecimal total = BigDecimal.ZERO;
        boolean hasAnyIncluded = false;
        Map<String, SkuAccumulator> skuTotals = new LinkedHashMap<>();

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

            PageResult pageResult = parseSalesPageForDate(responseBody, reportDate, zoneId);
            total = total.add(pageResult.amount());
            if (pageResult.includedCount() > 0) {
                hasAnyIncluded = true;
            }
            mergeSkuTotals(skuTotals, pageResult.skuSales());
            nextUrl = resolveNextUrl(filteredEndpoint, pageResult.cursor());
        }

        if (!hasAnyIncluded) {
            return new FetchResult(BigDecimal.ZERO, List.of());
        }
        return new FetchResult(total, toSkuSalesEntries(skuTotals));
    }

    private String buildDateFilteredEndpoint(String endpoint, LocalDate reportDate, ZoneId zoneId) {
        ZonedDateTime startLocal = reportDate.atStartOfDay(zoneId);
        ZonedDateTime endLocal = reportDate.plusDays(1).atStartOfDay(zoneId).minusSeconds(1);

        String createdAtMin = startLocal.withZoneSameInstant(ZoneOffset.UTC).format(LOYVERSE_DATETIME_FORMATTER);
        String createdAtMax = endLocal.withZoneSameInstant(ZoneOffset.UTC).format(LOYVERSE_DATETIME_FORMATTER);

        String separator = endpoint.contains("?") ? "&" : "?";
        return endpoint
                + separator
                + "created_at_min=" + createdAtMin
                + "&created_at_max=" + createdAtMax;
    }

    PageResult parseSalesPageForDate(String rawJson, LocalDate reportDate, ZoneId zoneId) {
        try {
            JsonNode root = objectMapper.readTree(rawJson);
            List<JsonNode> receipts = resolveReceiptNodes(root);
            if (receipts.isEmpty()) {
                return new PageResult(BigDecimal.ZERO, 0, readCursor(root), List.of());
            }

            BigDecimal pageTotal = BigDecimal.ZERO;
            int includedCount = 0;
            Map<String, SkuAccumulator> pageSkuTotals = new LinkedHashMap<>();
            for (JsonNode receipt : receipts) {
                if (!isReceiptOnReportDate(receipt, reportDate, zoneId)) {
                    continue;
                }
                if (isRefundReceipt(receipt)) {
                    continue;
                }
                includedCount++;
                pageTotal = pageTotal.add(extractGrossAmount(receipt));
                accumulateSkuSales(receipt, pageSkuTotals);
            }

            return new PageResult(pageTotal, includedCount, readCursor(root), toSkuSalesEntries(pageSkuTotals));
        } catch (Exception ex) {
            throw new IllegalStateException("Failed parsing Loyverse response: " + ex.getMessage(), ex);
        }
    }

    private void mergeSkuTotals(Map<String, SkuAccumulator> totals, List<SkuSalesEntry> additions) {
        if (additions == null || additions.isEmpty()) {
            return;
        }
        for (SkuSalesEntry addition : additions) {
            if (addition == null) {
                continue;
            }

            String rawSkuKey = trim(addition.skuKey());
            String normalizedSkuKey = normalizeSkuKey(rawSkuKey);
            if (normalizedSkuKey.isBlank()) {
                continue;
            }

            BigDecimal amount = addition.salesAmount() == null ? BigDecimal.ZERO : addition.salesAmount();
            SkuAccumulator current = totals.get(normalizedSkuKey);
            if (current == null) {
                String displayName = chooseBetterDisplayName("", addition.displayName(), rawSkuKey);
                totals.put(normalizedSkuKey, new SkuAccumulator(rawSkuKey, displayName, amount));
                continue;
            }

            String skuKey = current.skuKey().isBlank() ? rawSkuKey : current.skuKey();
            String displayName = chooseBetterDisplayName(current.displayName(), addition.displayName(), skuKey);
            totals.put(normalizedSkuKey, new SkuAccumulator(skuKey, displayName, current.salesAmount().add(amount)));
        }
    }

    private void accumulateSkuSales(JsonNode receipt, Map<String, SkuAccumulator> skuTotals) {
        JsonNode lineItems = receipt.get("line_items");
        if (lineItems == null || !lineItems.isArray() || lineItems.isEmpty()) {
            return;
        }

        for (JsonNode lineItem : lineItems) {
            SkuIdentity identity = resolveSkuIdentity(lineItem);
            if (identity == null) {
                continue;
            }

            BigDecimal lineAmount = extractLineItemAmount(lineItem);
            if (lineAmount == null) {
                continue;
            }

            String normalizedSkuKey = normalizeSkuKey(identity.skuKey());
            SkuAccumulator current = skuTotals.get(normalizedSkuKey);
            if (current == null) {
                String displayName = chooseBetterDisplayName("", identity.displayName(), identity.skuKey());
                skuTotals.put(normalizedSkuKey, new SkuAccumulator(identity.skuKey(), displayName, lineAmount));
                continue;
            }

            String displayName = chooseBetterDisplayName(current.displayName(), identity.displayName(), current.skuKey());
            skuTotals.put(
                    normalizedSkuKey,
                    new SkuAccumulator(current.skuKey(), displayName, current.salesAmount().add(lineAmount)));
        }
    }

    private SkuIdentity resolveSkuIdentity(JsonNode lineItem) {
        if (lineItem == null || lineItem.isNull() || !lineItem.isObject()) {
            return null;
        }

        String baseSku = firstNonBlank(
                readText(lineItem, "sku"),
                readText(lineItem, "item_id"),
                readText(lineItem, "item_code"),
                readText(lineItem, "item_name"),
                readText(lineItem, "name")
        );
        if (baseSku.isBlank()) {
            return null;
        }

        String variantKey = firstNonBlank(readText(lineItem, "variant_id"), readText(lineItem, "variant_name"));
        String skuKey = variantKey.isBlank() ? baseSku : baseSku + "::" + variantKey;

        String itemName = firstNonBlank(readText(lineItem, "item_name"), readText(lineItem, "name"), baseSku);
        String variantName = firstNonBlank(readText(lineItem, "variant_name"), readText(lineItem, "variant_id"));
        String displayName = buildDisplayName(itemName, variantName);

        return new SkuIdentity(skuKey, displayName);
    }

    private List<SkuSalesEntry> toSkuSalesEntries(Map<String, SkuAccumulator> totals) {
        if (totals == null || totals.isEmpty()) {
            return List.of();
        }

        List<SkuSalesEntry> entries = new ArrayList<>();
        for (SkuAccumulator value : totals.values()) {
            if (value == null || value.skuKey().isBlank()) {
                continue;
            }
            entries.add(new SkuSalesEntry(
                    value.skuKey(),
                    chooseBetterDisplayName("", value.displayName(), value.skuKey()),
                    value.salesAmount() == null ? BigDecimal.ZERO : value.salesAmount()));
        }
        return entries;
    }

    private String chooseBetterDisplayName(String current, String candidate, String skuKey) {
        String safeCurrent = trim(current);
        String safeCandidate = trim(candidate);
        String safeSkuKey = trim(skuKey);

        if (safeCurrent.isBlank()) {
            return safeCandidate.isBlank() ? safeSkuKey : safeCandidate;
        }
        if (safeCandidate.isBlank()) {
            return safeCurrent;
        }
        if (safeCurrent.equalsIgnoreCase(safeSkuKey) && !safeCandidate.equalsIgnoreCase(safeSkuKey)) {
            return safeCandidate;
        }
        return safeCurrent;
    }

    private String normalizeSkuKey(String skuKey) {
        String value = trim(skuKey);
        if (value.isBlank()) {
            return "";
        }
        return value.toLowerCase(Locale.ENGLISH);
    }

    private String readText(JsonNode node, String fieldName) {
        if (node == null || fieldName == null || fieldName.isBlank()) {
            return "";
        }
        JsonNode value = node.get(fieldName);
        if (value == null || value.isNull()) {
            return "";
        }
        return trim(value.asText(""));
    }

    private String firstNonBlank(String... values) {
        if (values == null || values.length == 0) {
            return "";
        }
        for (String value : values) {
            String candidate = trim(value);
            if (!candidate.isBlank()) {
                return candidate;
            }
        }
        return "";
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
        LocalDate saleDate = firstParsedDate(receipt, zoneId, "receipt_date");
        if (saleDate != null) {
            return saleDate.equals(reportDate);
        }

        JsonNode payments = receipt.get("payments");
        if (payments != null && payments.isArray()) {
            for (JsonNode payment : payments) {
                if (payment == null || payment.isNull()) {
                    continue;
                }
                LocalDate paidDate = firstParsedDate(payment, zoneId, "paid_at");
                if (paidDate != null) {
                    return paidDate.equals(reportDate);
                }
            }
        }

        LocalDate fallbackDate = firstParsedDate(receipt, zoneId, "open_time", "close_time", "created_at", "date");
        return fallbackDate == null || fallbackDate.equals(reportDate);
    }

    private LocalDate firstParsedDate(JsonNode node, ZoneId zoneId, String... fieldNames) {
        if (node == null || node.isNull() || fieldNames == null || fieldNames.length == 0) {
            return null;
        }

        for (String field : fieldNames) {
            LocalDate parsed = parseLocalDate(node.path(field).asText(""), zoneId);
            if (parsed != null) {
                return parsed;
            }
        }
        return null;
    }

    private LocalDate parseLocalDate(String rawValue, ZoneId zoneId) {
        String raw = trim(rawValue);
        if (raw.isBlank()) {
            return null;
        }

        try {
            return OffsetDateTime.parse(raw).atZoneSameInstant(zoneId).toLocalDate();
        } catch (Exception ignored) {
            if (raw.length() >= 10) {
                try {
                    return LocalDate.parse(raw.substring(0, 10));
                } catch (Exception ignoredAgain) {
                    return null;
                }
            }
            return null;
        }
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
            BigDecimal amount = extractLineItemAmount(lineItem);
            if (amount != null) {
                total = total.add(amount);
                hasAtLeastOne = true;
            }
        }
        return hasAtLeastOne ? total : null;
    }

    private BigDecimal extractLineItemAmount(JsonNode lineItem) {
        if (lineItem == null || lineItem.isNull() || !lineItem.isObject()) {
            return null;
        }

        String[] directAmountCandidates = {
                "gross_total_money",
                "gross_total",
                "total_money",
                "total",
                "amount_money",
                "amount"
        };
        for (String field : directAmountCandidates) {
            BigDecimal amount = parseMoneyNode(lineItem.get(field));
            if (amount != null) {
                return amount;
            }
        }

        BigDecimal quantity = parseDecimalNode(lineItem.get("quantity"));
        BigDecimal unitPrice = parseMoneyNode(lineItem.get("price_money"));
        if (unitPrice == null) {
            unitPrice = parseDecimalNode(lineItem.get("price"));
        }
        if (unitPrice == null) {
            unitPrice = parseMoneyNode(lineItem.get("price"));
        }

        if (quantity != null && unitPrice != null) {
            return unitPrice.multiply(quantity);
        }

        return null;
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

    private BigDecimal parseDecimalNode(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isNumber()) {
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
        return null;
    }

    private String buildDisplayName(String itemName, String variantName) {
        String safeItemName = trim(itemName);
        String safeVariantName = trim(variantName);
        if (safeItemName.isBlank()) {
            return safeVariantName;
        }
        if (safeVariantName.isBlank() || safeVariantName.equalsIgnoreCase(safeItemName)) {
            return safeItemName;
        }
        return safeItemName + " (" + safeVariantName + ")";
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

    record PageResult(BigDecimal amount,
                      int includedCount,
                      String cursor,
                      List<SkuSalesEntry> skuSales) {
    }

    private record FetchResult(BigDecimal amount, List<SkuSalesEntry> skuSales) {
    }

    private record SkuIdentity(String skuKey, String displayName) {
    }

    private record SkuAccumulator(String skuKey, String displayName, BigDecimal salesAmount) {
    }
}
