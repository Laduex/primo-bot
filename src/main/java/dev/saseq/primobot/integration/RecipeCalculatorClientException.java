package dev.saseq.primobot.integration;

public class RecipeCalculatorClientException extends RuntimeException {
    public RecipeCalculatorClientException(String message) {
        super(message);
    }

    public RecipeCalculatorClientException(String message, Throwable cause) {
        super(message, cause);
    }
}
