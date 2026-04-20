package dev.saseq.primobot.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

@Component
public class RecipeCalculatorClient {
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(20);

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final String baseUrl;
    private final String email;
    private final String password;

    public RecipeCalculatorClient(ObjectMapper objectMapper,
                                  @Value("${RECIPE_CALCULATOR_BASE_URL:http://127.0.0.1:3001}") String baseUrl,
                                  @Value("${RECIPE_CALCULATOR_EMAIL:}") String email,
                                  @Value("${RECIPE_CALCULATOR_PASSWORD:}") String password) {
        this.objectMapper = objectMapper;
        this.baseUrl = sanitizeBaseUrl(baseUrl);
        this.email = email == null ? "" : email.trim();
        this.password = password == null ? "" : password;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .build();
    }

    public List<JsonNode> fetchCostingRecords() {
        String cookieHeader = loginAndGetSessionCookie();
        return getArray("/api/costing-records", cookieHeader);
    }

    public List<JsonNode> fetchSuppliers() {
        String cookieHeader = loginAndGetSessionCookie();
        return getArray("/api/suppliers", cookieHeader);
    }

    public List<JsonNode> fetchCostingRecordsById(List<Long> recordIds) {
        if (recordIds == null || recordIds.isEmpty()) {
            return List.of();
        }

        String cookieHeader = loginAndGetSessionCookie();
        List<JsonNode> details = new ArrayList<>();
        for (Long recordId : recordIds) {
            if (recordId == null || recordId <= 0) {
                continue;
            }
            details.add(getObject("/api/costing-records/" + recordId, cookieHeader));
        }
        return details;
    }

    private String loginAndGetSessionCookie() {
        if (email.isBlank() || password.isBlank()) {
            throw new RecipeCalculatorClientException("Recipe API credentials are missing. Set RECIPE_CALCULATOR_EMAIL and RECIPE_CALCULATOR_PASSWORD.");
        }

        try {
            String requestBody = objectMapper.writeValueAsString(Map.of(
                    "email", email,
                    "password", password,
                    "rememberMe", true
            ));

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/api/auth/login"))
                    .timeout(REQUEST_TIMEOUT)
                    .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new RecipeCalculatorClientException("Recipe API login failed: " + extractError(response));
            }

            List<String> setCookieValues = response.headers().allValues("Set-Cookie");
            String cookieHeader = setCookieValues.stream()
                    .map(value -> value.split(";", 2)[0])
                    .collect(Collectors.joining("; "));

            if (cookieHeader.isBlank()) {
                throw new RecipeCalculatorClientException("Recipe API login succeeded but no session cookie was returned.");
            }

            return cookieHeader;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new RecipeCalculatorClientException("Failed to authenticate with Recipe API.", ex);
        } catch (IOException ex) {
            throw new RecipeCalculatorClientException("Failed to authenticate with Recipe API.", ex);
        }
    }

    private JsonNode getObject(String path, String cookieHeader) {
        JsonNode response = getJson(path, cookieHeader);
        if (response == null || !response.isObject()) {
            throw new RecipeCalculatorClientException("Recipe API returned an unexpected response for " + path + ".");
        }
        return response;
    }

    private List<JsonNode> getArray(String path, String cookieHeader) {
        JsonNode response = getJson(path, cookieHeader);
        if (response == null || !response.isArray()) {
            throw new RecipeCalculatorClientException("Recipe API returned an unexpected list response for " + path + ".");
        }
        return StreamSupport.stream(response.spliterator(), false).toList();
    }

    private JsonNode getJson(String path, String cookieHeader) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + path))
                    .timeout(REQUEST_TIMEOUT)
                    .header("Cookie", cookieHeader)
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new RecipeCalculatorClientException("Recipe API request failed (%s): %s"
                        .formatted(path, extractError(response)));
            }

            return objectMapper.readTree(response.body());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new RecipeCalculatorClientException("Recipe API request failed for " + path + ".", ex);
        } catch (IOException ex) {
            throw new RecipeCalculatorClientException("Recipe API request failed for " + path + ".", ex);
        }
    }

    private String extractError(HttpResponse<String> response) {
        try {
            JsonNode body = objectMapper.readTree(response.body());
            if (body != null && body.hasNonNull("error")) {
                return "HTTP %d: %s".formatted(response.statusCode(), body.get("error").asText());
            }
        } catch (Exception ignored) {
            // Fall back to raw response body.
        }

        String body = response.body() == null ? "" : response.body().trim();
        if (body.length() > 180) {
            body = body.substring(0, 180) + "...";
        }
        if (body.isBlank()) {
            body = "no response body";
        }
        return "HTTP %d: %s".formatted(response.statusCode(), body);
    }

    private String sanitizeBaseUrl(String rawBaseUrl) {
        if (rawBaseUrl == null || rawBaseUrl.isBlank()) {
            return "http://127.0.0.1:3001";
        }

        String sanitized = rawBaseUrl.trim();
        while (sanitized.endsWith("/")) {
            sanitized = sanitized.substring(0, sanitized.length() - 1);
        }
        return sanitized;
    }
}
