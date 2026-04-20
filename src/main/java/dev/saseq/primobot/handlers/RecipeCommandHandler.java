package dev.saseq.primobot.handlers;

import com.fasterxml.jackson.databind.JsonNode;
import dev.saseq.primobot.commands.PrimoCommands;
import dev.saseq.primobot.integration.RecipeCalculatorClient;
import dev.saseq.primobot.integration.RecipeCalculatorClientException;
import dev.saseq.primobot.security.AdminAccessService;
import dev.saseq.primobot.util.DiscordMessageUtils;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import org.springframework.stereotype.Component;

import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

@Component
public class RecipeCommandHandler {
    private static final int DISCORD_MAX_MESSAGE_LENGTH = 2000;
    private static final int DEFAULT_RECIPE_LIST_LIMIT = 30;
    private static final int DETAIL_MATCH_LIMIT = 3;

    private final RecipeCalculatorClient recipeCalculatorClient;
    private final AdminAccessService adminAccessService;

    public RecipeCommandHandler(RecipeCalculatorClient recipeCalculatorClient,
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
            List<JsonNode> records = recipeCalculatorClient.fetchCostingRecords();
            String response = query.isBlank()
                    ? buildRecipeListResponse(records)
                    : buildRecipeSearchResponse(query, records);
            sendChunkedResponse(event, response);
        } catch (RecipeCalculatorClientException ex) {
            event.getHook().editOriginal("Failed to fetch recipes: " + ex.getMessage()).queue();
        } catch (Exception ex) {
            event.getHook().editOriginal("Unexpected error while running `/recipe`: " + ex.getMessage()).queue();
        }
    }

    private String buildRecipeListResponse(List<JsonNode> records) {
        if (records.isEmpty()) {
            return "No recipes found in Recipe Calculator.";
        }

        List<JsonNode> sorted = records.stream()
                .sorted(Comparator.comparing(node -> node.path("name").asText(""), String.CASE_INSENSITIVE_ORDER))
                .toList();

        StringBuilder message = new StringBuilder("**Recipe List**\n");
        int count = Math.min(DEFAULT_RECIPE_LIST_LIMIT, sorted.size());

        for (int i = 0; i < count; i++) {
            JsonNode record = sorted.get(i);
            message.append(i + 1)
                    .append(") ")
                    .append(record.path("name").asText("Unnamed"))
                    .append(" | Cost: ").append(formatMoney(record.path("total_cost")))
                    .append(" | Selling: ").append(formatOptionalMoney(record.path("selling_price")))
                    .append(" | Margin: ").append(formatOptionalPercent(record.path("margin_percentage")))
                    .append(" | Variations: ").append(record.path("variations").isArray() ? record.path("variations").size() : 0)
                    .append('\n');
        }

        if (sorted.size() > count) {
            message.append("\nShowing ").append(count).append(" of ").append(sorted.size())
                    .append(" recipes. Use `/recipe query:<name>` for detailed ingredients and variations.");
        }

        return message.toString();
    }

    private String buildRecipeSearchResponse(String query, List<JsonNode> records) {
        String normalizedQuery = query.toLowerCase(Locale.ENGLISH);

        List<JsonNode> matches = records.stream()
                .filter(record -> matchesRecord(record, normalizedQuery))
                .sorted(Comparator.comparing(node -> node.path("name").asText(""), String.CASE_INSENSITIVE_ORDER))
                .toList();

        if (matches.isEmpty()) {
            return "No recipes or variations matched `" + query + "`.";
        }

        StringBuilder message = new StringBuilder();
        message.append("**Recipe Search Results**\n")
                .append("Query: `").append(query).append("`\n")
                .append("Matches: ").append(matches.size()).append("\n\n");

        int detailCount = Math.min(DETAIL_MATCH_LIMIT, matches.size());
        List<Long> detailIds = matches.stream()
                .limit(detailCount)
                .map(match -> match.path("id").asLong(-1))
                .filter(id -> id > 0)
                .collect(Collectors.toList());
        List<JsonNode> detailedRecords = recipeCalculatorClient.fetchCostingRecordsById(detailIds);
        Map<Long, JsonNode> detailById = new HashMap<>();
        for (JsonNode detailedRecord : detailedRecords) {
            long id = detailedRecord.path("id").asLong(-1);
            if (id > 0) {
                detailById.put(id, detailedRecord);
            }
        }

        for (int i = 0; i < detailCount; i++) {
            JsonNode summary = matches.get(i);
            long recordId = summary.path("id").asLong(-1);
            if (recordId <= 0) {
                continue;
            }

            JsonNode detail = detailById.get(recordId);
            if (detail == null) {
                continue;
            }
            appendRecipeDetail(message, i + 1, detail);
            message.append("\n");
        }

        if (matches.size() > detailCount) {
            message.append("Showing detailed output for ").append(detailCount)
                    .append(" matches. Narrow your query to inspect more recipes.");
        }

        return message.toString();
    }

    private void appendRecipeDetail(StringBuilder message, int index, JsonNode recipe) {
        message.append(index)
                .append(") **")
                .append(recipe.path("name").asText("Unnamed"))
                .append("** (ID ")
                .append(recipe.path("id").asText("-"))
                .append(")\n")
                .append("Type: ").append(recipe.path("record_type").asText("product")).append("\n")
                .append("Total Cost: ").append(formatMoney(recipe.path("total_cost"))).append("\n")
                .append("Selling Price: ").append(formatOptionalMoney(recipe.path("selling_price"))).append("\n")
                .append("Margin: ").append(formatOptionalPercent(recipe.path("margin_percentage"))).append("\n");

        JsonNode ingredients = recipe.path("items");
        message.append("Ingredients:\n");
        if (!ingredients.isArray() || ingredients.isEmpty()) {
            message.append("- No base ingredients configured\n");
        } else {
            for (JsonNode item : ingredients) {
                String ingredientName = item.path("ingredient_name").asText("Unnamed ingredient");
                String quantity = formatQuantity(item.path("quantity"), item.path("unit").asText(""));
                String unitCost = formatMoney(item.path("unit_price"));
                String totalCost = formatMoney(item.path("total_price"));
                message.append("- ").append(ingredientName)
                        .append(" | Qty: ").append(quantity)
                        .append(" | Unit: ").append(unitCost)
                        .append(" | Total: ").append(totalCost)
                        .append('\n');
            }
        }

        JsonNode variations = recipe.path("variations");
        message.append("Variations:\n");
        if (!variations.isArray() || variations.isEmpty()) {
            message.append("- No variations\n");
            return;
        }

        for (JsonNode variation : variations) {
            message.append("- ").append(variation.path("name").asText("Unnamed variation"))
                    .append(" | Cost: ").append(formatMoney(variation.path("total_cost")))
                    .append('\n');

            JsonNode variationItems = variation.path("items");
            if (variationItems.isArray() && !variationItems.isEmpty()) {
                for (JsonNode item : variationItems) {
                    String ingredientName = item.path("ingredient_name").asText("Unnamed ingredient");
                    String quantity = formatQuantity(item.path("quantity"), item.path("unit").asText(""));
                    String unitCost = formatMoney(item.path("unit_price"));
                    String totalCost = formatMoney(item.path("total_price"));
                    message.append("  - ").append(ingredientName)
                            .append(" | Qty: ").append(quantity)
                            .append(" | Unit: ").append(unitCost)
                            .append(" | Total: ").append(totalCost)
                            .append('\n');
                }
            }

            JsonNode platforms = variation.path("platforms");
            if (platforms.isArray() && !platforms.isEmpty()) {
                List<String> prices = new ArrayList<>();
                for (JsonNode platform : platforms) {
                    String platformName = platform.path("name").asText("Platform");
                    String sellingPrice = formatOptionalMoney(platform.path("selling_price"));
                    prices.add(platformName + ": " + sellingPrice);
                }
                message.append("  Platform Prices: ").append(String.join(" | ", prices)).append('\n');
            }
        }
    }

    private boolean matchesRecord(JsonNode record, String normalizedQuery) {
        String recipeName = record.path("name").asText("").toLowerCase(Locale.ENGLISH);
        if (recipeName.contains(normalizedQuery)) {
            return true;
        }

        JsonNode variations = record.path("variations");
        if (!variations.isArray()) {
            return false;
        }

        for (JsonNode variation : variations) {
            String variationName = variation.path("name").asText("").toLowerCase(Locale.ENGLISH);
            if (variationName.contains(normalizedQuery)) {
                return true;
            }
        }
        return false;
    }

    private String readQuery(SlashCommandInteractionEvent event) {
        var option = event.getOption(PrimoCommands.RECIPE_QUERY_OPTION);
        return option == null ? "" : option.getAsString().trim();
    }

    private String formatOptionalMoney(JsonNode value) {
        if (value == null || value.isNull()) {
            return "not set";
        }
        return formatMoney(value);
    }

    private String formatMoney(JsonNode value) {
        DecimalFormat format = new DecimalFormat("#,##0.00");
        if (value == null || value.isNull()) {
            return "PHP 0.00";
        }
        return "PHP " + format.format(value.asDouble(0));
    }

    private String formatOptionalPercent(JsonNode value) {
        if (value == null || value.isNull()) {
            return "not set";
        }
        DecimalFormat format = new DecimalFormat("#,##0.##");
        return format.format(value.asDouble(0)) + "%";
    }

    private String formatQuantity(JsonNode quantityNode, String unit) {
        DecimalFormat format = new DecimalFormat("#,##0.####");
        String quantity = format.format(quantityNode.asDouble(0));
        String normalizedUnit = unit == null ? "" : unit.trim();
        return normalizedUnit.isBlank() ? quantity : quantity + " " + normalizedUnit;
    }

    private void sendChunkedResponse(SlashCommandInteractionEvent event, String response) {
        List<String> chunks = DiscordMessageUtils.chunkMessage(response, DISCORD_MAX_MESSAGE_LENGTH);
        event.getHook().editOriginal(chunks.get(0)).queue();
        for (int i = 1; i < chunks.size(); i++) {
            event.getHook().sendMessage(chunks.get(i)).queue();
        }
    }
}
