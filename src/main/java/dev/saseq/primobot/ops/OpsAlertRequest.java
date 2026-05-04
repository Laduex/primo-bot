package dev.saseq.primobot.ops;

public record OpsAlertRequest(String severity,
                              String title,
                              String message) {
}
