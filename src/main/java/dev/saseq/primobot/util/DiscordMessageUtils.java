package dev.saseq.primobot.util;

import java.util.ArrayList;
import java.util.List;

public final class DiscordMessageUtils {
    private DiscordMessageUtils() {
    }

    public static List<String> chunkMessage(String content, int maxLength) {
        if (content == null || content.isBlank()) {
            return List.of("(no content)");
        }

        if (content.length() <= maxLength) {
            return List.of(content);
        }

        List<String> chunks = new ArrayList<>();
        String[] lines = content.split("\\n", -1);
        StringBuilder current = new StringBuilder();

        for (String line : lines) {
            String candidate = current.length() == 0 ? line : current + "\n" + line;
            if (candidate.length() <= maxLength) {
                if (current.length() > 0) {
                    current.append('\n');
                }
                current.append(line);
                continue;
            }

            if (current.length() > 0) {
                chunks.add(current.toString());
                current.setLength(0);
            }

            if (line.length() <= maxLength) {
                current.append(line);
                continue;
            }

            int start = 0;
            while (start < line.length()) {
                int end = Math.min(start + maxLength, line.length());
                chunks.add(line.substring(start, end));
                start = end;
            }
        }

        if (current.length() > 0) {
            chunks.add(current.toString());
        }

        return chunks.isEmpty() ? List.of("(no content)") : chunks;
    }
}
