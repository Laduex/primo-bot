package dev.saseq.primobot.sales;

import org.htmlunit.BrowserVersion;
import org.htmlunit.WebClient;
import org.htmlunit.html.HtmlButton;
import org.htmlunit.html.HtmlForm;
import org.htmlunit.html.HtmlInput;
import org.htmlunit.html.HtmlPage;
import org.htmlunit.html.HtmlPasswordInput;
import org.htmlunit.html.HtmlSubmitInput;
import org.htmlunit.html.HtmlTextInput;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class UtakBrowserSalesProvider implements SalesProvider {
    private static final Pattern NET_SALES_PATTERN = Pattern.compile("(?i)Total\\s*Net\\s*Sales[^\\d-]*([\\d,]+(?:\\.\\d{1,2})?)");
    private static final int JS_WAIT_MS = 7000;

    @Override
    public SalesPlatform platform() {
        return SalesPlatform.UTAK;
    }

    @Override
    public SalesAccountResult fetchTodayCumulative(SalesAccountConfig account, SalesFetchContext context) {
        String entryUrl = firstNonBlank(account.getSalesPageUrl(), account.getBaseUrl());
        if (entryUrl.isBlank()) {
            throw new IllegalArgumentException("Missing UTAK base URL or sales page URL");
        }

        try (WebClient webClient = new WebClient(BrowserVersion.BEST_SUPPORTED)) {
            webClient.getOptions().setCssEnabled(true);
            webClient.getOptions().setJavaScriptEnabled(true);
            webClient.getOptions().setThrowExceptionOnScriptError(false);
            webClient.getOptions().setThrowExceptionOnFailingStatusCode(false);
            webClient.getOptions().setTimeout(15000);

            HtmlPage page = webClient.getPage(entryUrl);
            webClient.waitForBackgroundJavaScript(JS_WAIT_MS);

            page = tryLogin(page, webClient, account);

            String salesUrl = account.getSalesPageUrl() == null ? "" : account.getSalesPageUrl().trim();
            if (!salesUrl.isBlank() && !salesUrl.equalsIgnoreCase(page.getUrl().toString())) {
                page = webClient.getPage(salesUrl);
                webClient.waitForBackgroundJavaScript(JS_WAIT_MS);
            }

            String amountText = extractTotalNetSales(page);
            BigDecimal amount = parseMoney(amountText);
            return SalesAccountResult.success(account, SalesPlatform.UTAK, SalesPlatform.UTAK.getMetricLabel(), amount);
        } catch (Exception ex) {
            throw new IllegalStateException("Failed UTAK fetch for account '%s': %s"
                    .formatted(account.getName(), ex.getMessage()), ex);
        }
    }

    private HtmlPage tryLogin(HtmlPage page, WebClient webClient, SalesAccountConfig account) {
        String username = account.getUsername() == null ? "" : account.getUsername().trim();
        String password = account.getPassword() == null ? "" : account.getPassword().trim();
        if (username.isBlank() || password.isBlank()) {
            return page;
        }

        try {
            String pageText = page.asNormalizedText().toLowerCase(Locale.ENGLISH);
            boolean mightNeedLogin = pageText.contains("login")
                    || pageText.contains("sign in")
                    || !page.getByXPath("//input[@type='password']").isEmpty();
            if (!mightNeedLogin) {
                return page;
            }

            HtmlForm targetForm = page.getForms().isEmpty() ? null : page.getForms().get(0);
            HtmlTextInput usernameInput = findUsernameInput(page, targetForm);
            HtmlPasswordInput passwordInput = findPasswordInput(page, targetForm);
            if (usernameInput == null || passwordInput == null) {
                return page;
            }

            usernameInput.setValueAttribute(username);
            passwordInput.setValueAttribute(password);

            HtmlPage nextPage = clickSubmit(page, targetForm);
            webClient.waitForBackgroundJavaScript(JS_WAIT_MS);
            return nextPage == null ? page : nextPage;
        } catch (Exception ignored) {
            return page;
        }
    }

    private HtmlTextInput findUsernameInput(HtmlPage page, HtmlForm form) {
        List<String> names = List.of("username", "email", "user", "login");
        if (form != null) {
            for (String name : names) {
                try {
                    HtmlInput input = form.getInputByName(name);
                    if (input instanceof HtmlTextInput textInput) {
                        return textInput;
                    }
                } catch (Exception ignored) {
                    // try the next common field name
                }
            }
        }

        for (Object node : page.getByXPath("//input[@type='text' or @type='email']")) {
            if (node instanceof HtmlTextInput textInput) {
                String name = textInput.getNameAttribute().toLowerCase(Locale.ENGLISH);
                if (names.stream().anyMatch(name::contains)) {
                    return textInput;
                }
            }
        }
        return null;
    }

    private HtmlPasswordInput findPasswordInput(HtmlPage page, HtmlForm form) {
        if (form != null) {
            for (Object node : form.getByXPath(".//input[@type='password']")) {
                if (node instanceof HtmlPasswordInput input) {
                    return input;
                }
            }
        }

        for (Object node : page.getByXPath("//input[@type='password']")) {
            if (node instanceof HtmlPasswordInput input) {
                return input;
            }
        }
        return null;
    }

    private HtmlPage clickSubmit(HtmlPage page, HtmlForm form) {
        try {
            if (form != null) {
                for (Object node : form.getByXPath(".//button[@type='submit']")) {
                    if (node instanceof HtmlButton button) {
                        return button.click();
                    }
                }
                for (Object node : form.getByXPath(".//input[@type='submit']")) {
                    if (node instanceof HtmlSubmitInput input) {
                        return input.click();
                    }
                }
            }

            for (Object node : page.getByXPath("//button[@type='submit']")) {
                if (node instanceof HtmlButton button) {
                    return button.click();
                }
            }
        } catch (Exception ignored) {
            return page;
        }
        return page;
    }

    private String extractTotalNetSales(HtmlPage page) {
        String normalizedText = page.asNormalizedText();
        Matcher matcher = NET_SALES_PATTERN.matcher(normalizedText);
        if (matcher.find()) {
            return matcher.group(1);
        }

        String rawXml = page.asXml();
        matcher = NET_SALES_PATTERN.matcher(rawXml);
        if (matcher.find()) {
            return matcher.group(1);
        }

        throw new IllegalStateException("Could not locate 'Total Net Sales' on page");
    }

    private BigDecimal parseMoney(String rawValue) {
        if (rawValue == null || rawValue.isBlank()) {
            throw new IllegalArgumentException("Empty sales value");
        }

        String normalized = rawValue.replace(",", "").trim();
        return new BigDecimal(normalized);
    }

    private String firstNonBlank(String first, String second) {
        if (first != null && !first.trim().isBlank()) {
            return first.trim();
        }
        if (second != null && !second.trim().isBlank()) {
            return second.trim();
        }
        return "";
    }
}
