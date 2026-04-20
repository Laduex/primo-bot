package dev.saseq.primobot.handlers;

import com.fasterxml.jackson.databind.JsonNode;
import dev.saseq.primobot.commands.PrimoCommands;
import dev.saseq.primobot.integration.RecipeCalculatorClient;
import dev.saseq.primobot.integration.RecipeCalculatorClientException;
import dev.saseq.primobot.security.AdminAccessService;
import dev.saseq.primobot.util.DiscordMessageUtils;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;

@Component
public class SupplierCommandHandler {
    private static final int DISCORD_MAX_MESSAGE_LENGTH = 2000;
    private static final int MAX_SUPPLIER_DETAILS = 30;

    private final RecipeCalculatorClient recipeCalculatorClient;
    private final AdminAccessService adminAccessService;

    public SupplierCommandHandler(RecipeCalculatorClient recipeCalculatorClient,
                                  AdminAccessService adminAccessService) {
        this.recipeCalculatorClient = recipeCalculatorClient;
        this.adminAccessService = adminAccessService;
    }

    public void handle(SlashCommandInteractionEvent event) {
        if (!adminAccessService.hasAccess(event)) {
            event.reply(adminAccessService.noAccessMessage()).setEphemeral(true).queue();
            return;
        }

        event.deferReply().queue();

        String query = readQuery(event);
        try {
            List<JsonNode> suppliers = recipeCalculatorClient.fetchSuppliers();
            String response = buildSupplierResponse(suppliers, query);
            sendChunkedResponse(event, response);
        } catch (RecipeCalculatorClientException ex) {
            event.getHook().editOriginal("Failed to fetch suppliers: " + ex.getMessage()).queue();
        } catch (Exception ex) {
            event.getHook().editOriginal("Unexpected error while running `/supplier`: " + ex.getMessage()).queue();
        }
    }

    private String buildSupplierResponse(List<JsonNode> suppliers, String query) {
        if (suppliers.isEmpty()) {
            return "No suppliers found in Recipe Calculator.";
        }

        String normalizedQuery = query.toLowerCase(Locale.ENGLISH);

        List<JsonNode> matches = suppliers.stream()
                .filter(supplier -> normalizedQuery.isBlank() || matchesSupplier(supplier, normalizedQuery))
                .sorted(Comparator.comparing(node -> node.path("name").asText(""), String.CASE_INSENSITIVE_ORDER))
                .toList();

        if (matches.isEmpty()) {
            return "No suppliers matched `" + query + "`.";
        }

        StringBuilder message = new StringBuilder("**Supplier Results**\n");
        if (!query.isBlank()) {
            message.append("Query: `").append(query).append("`\n");
        }
        message.append("Matches: ").append(matches.size()).append("\n\n");

        int limit = Math.min(MAX_SUPPLIER_DETAILS, matches.size());
        for (int i = 0; i < limit; i++) {
            appendSupplierDetails(message, i + 1, matches.get(i));
            message.append("\n");
        }

        if (matches.size() > limit) {
            message.append("Showing ").append(limit).append(" of ").append(matches.size())
                    .append(" suppliers. Use `/supplier query:<name>` to narrow results.");
        }

        return message.toString();
    }

    private void appendSupplierDetails(StringBuilder message, int index, JsonNode supplier) {
        message.append(index)
                .append(") **")
                .append(readField(supplier, "name", "Unnamed supplier"))
                .append("** (ID ")
                .append(readField(supplier, "id", "-"))
                .append(")\n")
                .append("Contact: ").append(readField(supplier, "contact_name", "not set")).append("\n")
                .append("Email: ").append(readField(supplier, "email", "not set")).append("\n")
                .append("Phone: ").append(readField(supplier, "phone", "not set")).append("\n")
                .append("Address: ").append(readField(supplier, "address", "not set")).append("\n")
                .append("Platform: ").append(readField(supplier, "platform", "not set")).append("\n")
                .append("Website: ").append(readField(supplier, "website", "not set")).append("\n")
                .append("Tax TIN: ").append(readField(supplier, "tax_tin", "not set")).append("\n")
                .append("Bank Details: ").append(readField(supplier, "bank_details", "not set")).append("\n")
                .append("Payment Details: ").append(readField(supplier, "payment_details", "not set")).append("\n")
                .append("Notes: ").append(readField(supplier, "notes", "not set")).append("\n")
                .append("Linked Ingredients: ").append(readField(supplier, "ingredient_count", "0"));

        JsonNode paymentMethods = supplier.path("payment_methods");
        if (paymentMethods.isArray() && !paymentMethods.isEmpty()) {
            message.append("\nPayment Methods: ");
            for (int i = 0; i < paymentMethods.size(); i++) {
                JsonNode method = paymentMethods.get(i);
                String name = readField(method, "method", "method");
                String reference = readField(method, "payment_details", "");
                if (i > 0) {
                    message.append(" | ");
                }
                message.append(reference.isBlank() ? name : name + " (" + reference + ")");
            }
        }
    }

    private String readField(JsonNode node, String fieldName, String fallback) {
        if (node == null) {
            return fallback;
        }

        JsonNode value = node.path(fieldName);
        if (value.isMissingNode() || value.isNull()) {
            return fallback;
        }

        String text = value.asText("").trim();
        return text.isBlank() ? fallback : text;
    }

    private boolean matchesSupplier(JsonNode supplier, String normalizedQuery) {
        return containsField(supplier, "name", normalizedQuery)
                || containsField(supplier, "contact_name", normalizedQuery)
                || containsField(supplier, "email", normalizedQuery)
                || containsField(supplier, "phone", normalizedQuery)
                || containsField(supplier, "address", normalizedQuery)
                || containsField(supplier, "website", normalizedQuery)
                || containsField(supplier, "notes", normalizedQuery);
    }

    private boolean containsField(JsonNode node, String field, String normalizedQuery) {
        String value = node.path(field).asText("").toLowerCase(Locale.ENGLISH);
        return value.contains(normalizedQuery);
    }

    private String readQuery(SlashCommandInteractionEvent event) {
        var option = event.getOption(PrimoCommands.SUPPLIER_QUERY_OPTION);
        return option == null ? "" : option.getAsString().trim();
    }

    private void sendChunkedResponse(SlashCommandInteractionEvent event, String response) {
        List<String> chunks = DiscordMessageUtils.chunkMessage(response, DISCORD_MAX_MESSAGE_LENGTH);
        event.getHook().editOriginal(chunks.get(0)).queue();
        for (int i = 1; i < chunks.size(); i++) {
            event.getHook().sendMessage(chunks.get(i)).queue();
        }
    }
}
